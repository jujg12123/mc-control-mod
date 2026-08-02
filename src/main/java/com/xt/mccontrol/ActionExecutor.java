package com.xt.mccontrol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 动作执行器。
 * <p>
 * 修复要点（#1 / #2）：
 * - 所有长动作（寻路、挖掘、下挖、回地面）改为<b>主线程 tick 驱动的状态机</b>，
 *   由 {@link MCControlMod} 的客户端 tick 回调推进 {@link #tick(MinecraftClient)}。
 * - 彻底消除后台线程对 player/world 对象的读取，不再用 Thread.sleep 等待
 *   client.execute() 完成。
 * - {@link #actionInProgress} 作为统一动作锁（#3），供 {@link AutoBehaviorManager}
 *   判断是否应让位。
 */
public class ActionExecutor {
    static final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2);

    private static ActionTask currentTask = null;
    /** 统一动作锁：长任务进行中为 true，自动行为应让位（保命除外） */
    private static volatile boolean actionInProgress = false;
    static volatile boolean navCancelled = false;
    static volatile long actionVersion = 0;
    /** 当前动作的 call_id（由插件生成，回传结果时原样带回） */
    static long currentCallId = 0;
    /** 当前长任务的来源动作名与 call_id（用于任务被打断时回传结果） */
    private static String currentTaskActionName = "unknown";
    private static long currentTaskCallId = 0;

    // 白名单：只有这些方块在导航时会被尝试破坏
    static final Set<String> BREAKABLE_BLOCKS = Set.of(
        "dirt", "grass_block", "sand", "gravel", "tall_grass", "leaves",
        "cobblestone", "stone", "oak_planks", "oak_log", "birch_log", "spruce_log",
        "netherrack", "end_stone"
    );

    // === 连续挖掘标注（树/矿脉）===
    // go_to_block 命中原木/矿物时，BFS 收集相连同 ID 方块存到这里，
    // 之后 mc_digBlock 会连续挖完这些方块（自动清理遮挡、自动垫脚）
    static final LinkedHashSet<BlockPos> markedBlocks = new LinkedHashSet<>();
    static String markTag = "";
    static final Set<String> MARK_KEYWORDS = Set.of("log", "ore");
    static final int MAX_MARKED = 64;
    private static final int[] MARK_DIR_X = {1, -1, 0, 0, 0, 0};
    private static final int[] MARK_DIR_Y = {0, 0, 1, -1, 0, 0};
    private static final int[] MARK_DIR_Z = {0, 0, 0, 0, 1, -1};
    // 不能拿来垫脚的 BlockItem（门/火把/红石等无法踩踏的方块）
    static final Set<String> NON_PILLAR_KEYWORDS = Set.of(
        "door", "fence", "torch", "button", "pressure", "plate", "rail", "redstone",
        "flower", "sapling", "carpet", "banner", "sign", "trapdoor", "ladder",
        "vine", "lever", "torchflower", "moss_carpet", "mud"
    );

    public static void execute(String commandJson) {
        String actionName = "unknown";
        try {
            JsonObject cmd = JsonParser.parseString(commandJson).getAsJsonObject();
            actionName = cmd.get("action").getAsString();
            String action = actionName;
            currentCallId = cmd.has("call_id") ? cmd.get("call_id").getAsLong() : 0;
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            if (player == null) return;

            // --- 配置类动作：不干扰进行中的任务 ---
            if (action.equals("enable_auto")) {
                AutoBehaviorManager.setEnabled(true);
                sendResult("enable_auto", true, "自动行为已启用");
                return;
            }
            if (action.equals("disable_auto")) {
                AutoBehaviorManager.setEnabled(false);
                sendResult("disable_auto", true, "自动行为已禁用");
                return;
            }
            if (action.equals("enable_reflex") || action.equals("disable_reflex")) {
                String reflexName = cmd.has("name") ? cmd.get("name").getAsString() : "";
                boolean enable = action.equals("enable_reflex");
                String result = AutoBehaviorManager.setReflexEnabled(reflexName, enable);
                sendResult(action, result != null, result != null ? result : "未知反射: " + reflexName
                        + "（可选: self_defense, eat, stuck, pickup, mlg, breath）");
                return;
            }

            // --- 停止类动作：取消当前长任务 ---
            if (action.equals("stop_nav")) {
                String stoppedAction = currentTaskActionName;
                long stoppedCallId = currentTaskCallId;
                boolean hadTask = currentTask != null;
                cancelCurrentTask(client);
                sendResult("stop_nav", true, "已停止寻路");
                // 被停止的旧任务也要回传结果，避免插件侧一直等到超时
                if (hadTask && stoppedCallId != 0) {
                    sendResult(stoppedAction, stoppedCallId, false, "寻路已被停止");
                }
                return;
            }

            // --- 其它动作：先取消旧的长任务（防按键冲突），再执行 ---
            actionVersion++;
            navCancelled = false;
            if (currentTask != null) {
                String cancelledAction = currentTaskActionName;
                long cancelledCallId = currentTaskCallId;
                releaseAllKeys(client);
                currentTask = null;
                actionInProgress = false;
                sendResult(cancelledAction, cancelledCallId, false, "上一个任务已被新动作打断");
            }

            switch (action) {
                case "move_forward" -> {
                    String dir = cmd.has("direction")
                            ? cmd.get("direction").getAsString() : "forward";
                    double duration = cmd.has("duration")
                            ? cmd.get("duration").getAsDouble() : 0.5;
                    move(player, dir, duration);
                    sendResult("move_forward", true, "向" + dir + "移动 " + duration + "秒");
                }
                case "attack" -> {
                    double duration = cmd.has("duration")
                            ? cmd.get("duration").getAsDouble() : 2.0;
                    client.options.attackKey.setPressed(true);
                    scheduler.schedule(() ->
                            client.execute(() ->
                                    client.options.attackKey.setPressed(false)),
                            (long) (duration * 1000), TimeUnit.MILLISECONDS);
                    sendResult("attack", true, "攻击/挖掘 " + duration + "秒");
                }
                case "place" -> {
                    // 支持指定物品名称：自动在背包查找并切换到快捷栏对应槽位
                    String itemName = cmd.has("item_name")
                            ? cmd.get("item_name").getAsString() : "";
                    if (!itemName.isEmpty()) {
                        PlayerInventory inv = player.getInventory();
                        int foundSlot = -1;
                        // 先在快捷栏 0-8 查找
                        for (int i = 0; i < 9; i++) {
                            ItemStack stack = inv.getStack(i);
                            if (!stack.isEmpty()) {
                                String name = stack.getItem().getName().getString().toLowerCase();
                                Identifier id = Registries.ITEM.getId(stack.getItem());
                                String idPath = id != null ? id.getPath().toLowerCase() : "";
                                if (name.contains(itemName.toLowerCase()) || idPath.contains(itemName.toLowerCase())) {
                                    foundSlot = i;
                                    break;
                                }
                            }
                        }
                        if (foundSlot >= 0) {
                            inv.selectedSlot = foundSlot;
                        } else {
                            // 快捷栏没有，在背包 9-35 查找并交换到快捷栏
                            for (int i = 9; i < 36; i++) {
                                ItemStack stack = inv.getStack(i);
                                if (!stack.isEmpty()) {
                                    String name = stack.getItem().getName().getString().toLowerCase();
                                    Identifier id = Registries.ITEM.getId(stack.getItem());
                                    String idPath = id != null ? id.getPath().toLowerCase() : "";
                                    if (name.contains(itemName.toLowerCase()) || idPath.contains(itemName.toLowerCase())) {
                                        client.interactionManager.pickFromInventory(i);
                                        foundSlot = i;
                                        break;
                                    }
                                }
                            }
                        }
                        if (foundSlot < 0) {
                            sendResult("place", false, "背包中未找到: " + itemName);
                            return;
                        }
                    }
                    // 使用 PlaceTask：自动后退拉开距离、自动抬平视角后放置
                    // （AI 导航常紧贴目标方块/视角朝下，直接放置会失败）
                    startTask(client, new PlaceTask(), "place");
                }
                case "switch_slot" -> {
                    int slot = cmd.get("slot").getAsInt();
                    if (slot >= 0 && slot <= 8) {
                        player.getInventory().selectedSlot = slot;
                        sendResult("switch_slot", true, "切换到槽位 " + slot);
                    } else {
                        sendResult("switch_slot", false, "槽位超出范围: " + slot);
                    }
                }
                case "jump" -> {
                    client.options.jumpKey.setPressed(true);
                    scheduler.schedule(() ->
                            client.execute(() ->
                                    client.options.jumpKey.setPressed(false)),
                            100, TimeUnit.MILLISECONDS);
                    sendResult("jump", true, "已跳跃");
                }
                case "look_at" -> {
                    float yaw = cmd.get("yaw").getAsFloat();
                    float pitch = cmd.get("pitch").getAsFloat();
                    // 使用平滑视角任务（模拟鼠标移动，多个 tick 渐进转向）
                    startTask(client, new SmoothLookTask(yaw, pitch), "look_at");
                }
                case "sneak" -> {
                    client.options.sneakKey.setPressed(true);
                    sendResult("sneak", true, "已进入潜行");
                }
                case "unsneak" -> {
                    client.options.sneakKey.setPressed(false);
                    sendResult("unsneak", true, "已退出潜行");
                }
                case "use" -> {
                    double duration = cmd.has("duration")
                            ? cmd.get("duration").getAsDouble() : 0.0;
                    String hand = cmd.has("hand")
                            ? cmd.get("hand").getAsString() : "main_hand";
                    Hand useHand = hand.equals("off_hand")
                            ? Hand.OFF_HAND : Hand.MAIN_HAND;

                    if (duration > 0) {
                        client.options.useKey.setPressed(true);
                        scheduler.schedule(() ->
                                client.execute(() ->
                                        client.options.useKey.setPressed(false)),
                                (long) (duration * 1000), TimeUnit.MILLISECONDS);
                        sendResult("use", true, "持续使用中，持续 " + duration + "秒");
                    } else {
                        HitResult hit = player.raycast(5.0, 0, false);
                        boolean interacted = false;
                        if (hit.getType() == HitResult.Type.BLOCK) {
                            BlockHitResult blockHit = (BlockHitResult) hit;
                            ActionResult result = client.interactionManager
                                    .interactBlock(player, useHand, blockHit);
                            interacted = result.isAccepted();
                            if (!interacted) {
                                client.interactionManager.interactItem(player, useHand);
                                interacted = true;
                            }
                        } else if (hit.getType() == HitResult.Type.ENTITY) {
                            EntityHitResult entityHit = (EntityHitResult) hit;
                            client.interactionManager
                                    .interactEntity(player, entityHit.getEntity(), useHand);
                            interacted = true;
                        } else {
                            client.interactionManager.interactItem(player, useHand);
                            interacted = true;
                        }
                        sendResult("use", interacted, interacted ? "已使用" : "使用失败");
                    }
                }
                case "drop" -> {
                    player.dropSelectedItem(false);
                    sendResult("drop", true, "已丢弃物品");
                }

                // === 寻路（tick 状态机） ===
                case "go_to_block" -> {
                    String blockType = cmd.get("block_type").getAsString();
                    double range = cmd.has("range")
                            ? cmd.get("range").getAsDouble() : 32;
                    goToBlock(player, blockType, range);
                }
                case "go_to_pos" -> {
                    double tx = cmd.get("x").getAsDouble();
                    double ty = cmd.get("y").getAsDouble();
                    double tz = cmd.get("z").getAsDouble();
                    startTask(client, new NavTask(player, tx, ty, tz, true, "go_to_pos"), "go_to_pos");
                }

                // === 持续挖掘直到破坏 ===
                case "dig_block" -> {
                    double timeout = cmd.has("timeout")
                            ? cmd.get("timeout").getAsDouble() : 10.0;
                    if (!markedBlocks.isEmpty()) {
                        // 有 go_to_block 标注的树/矿脉：连续挖完所有标注方块
                        startTask(client, new ChainDigTask(), "dig_block");
                    } else {
                        startTask(client, new DigBlockTask(timeout), "dig_block");
                    }
                }

                // === 向下挖掘（安全） ===
                case "dig_down" -> {
                    int distance = cmd.has("distance")
                            ? cmd.get("distance").getAsInt() : 1;
                    startTask(client, new DigDownTask(distance), "dig_down");
                }

                // === 回到地面（向上挖） ===
                case "go_to_surface" -> startTask(client, new GoToSurfaceTask(), "go_to_surface");

                // === 攻击实体 ===
                case "attack_entity" -> {
                    String type = cmd.has("type")
                            ? cmd.get("type").getAsString() : "";
                    double range = cmd.has("range")
                            ? cmd.get("range").getAsDouble() : 16;
                    attackEntity(player, type, range);
                }

                // === 装备物品 ===
                case "equip" -> {
                    String itemName = cmd.get("item_name").getAsString();
                    equipItem(player, itemName);
                }

                // === 吃/喝 ===
                case "consume" -> {
                    String itemName = cmd.has("item_name")
                            ? cmd.get("item_name").getAsString() : "";
                    consumeItem(player, itemName);
                }

                // === 合成 ===
                case "craft" -> {
                    String recipe = cmd.has("recipe")
                            ? cmd.get("recipe").getAsString() : "";
                    int count = cmd.has("count")
                            ? cmd.get("count").getAsInt() : 1;
                    craftItem(client, player, recipe, count);
                }

                // === 查询配方（动态） ===
                case "query_recipe" -> {
                    String item = cmd.has("item")
                            ? cmd.get("item").getAsString() : "";
                    queryRecipe(item);
                }

                // === 批量建造（set/box/walls/line/clear） ===
                case "build" -> {
                    if (!cmd.has("ops") || !cmd.get("ops").isJsonArray()) {
                        sendResult("build", false, "缺少 ops 数组参数");
                        return;
                    }
                    BuildTask bt = BuildTask.fromJson(cmd.getAsJsonArray("ops"));
                    if (bt == null) {
                        sendResult("build", false, "build 参数解析失败，请检查 ops 结构");
                        return;
                    }
                    String precheck = bt.precheck(player);
                    if (precheck != null) {
                        sendResult("build", false, precheck);
                        return;
                    }
                    startTask(client, bt, "build");
                }

                default -> {
                    System.out.println("[MC-Control] Unknown action: " + action);
                    sendResult(action, false, "未知动作: " + action);
                }
            }
        } catch (Exception e) {
            System.err.println("[MC-Control] Action failed: " + e.getMessage());
            sendResult(actionName, false, "执行失败: " + e.getMessage());
        }
    }

    // ======================== 任务状态机驱动 ========================

    /**
     * 由 {@link MCControlMod} 的客户端 tick 回调调用，推进当前长任务。
     * 必须在主线程执行。
     */
    public static void tick(MinecraftClient client) {
        if (currentTask == null) return;
        ClientPlayerEntity player = client.player;
        if (player == null) {
            cancelCurrentTask(client);
            return;
        }
        try {
            boolean done = currentTask.tick(client, player);
            if (done) {
                releaseAllKeys(client);
                currentTask = null;
                actionInProgress = false;
            }
        } catch (Exception e) {
            System.err.println("[MC-Control] Task tick error: " + e.getMessage());
            e.printStackTrace();
            releaseAllKeys(client);
            currentTask = null;
            actionInProgress = false;
        }
    }

    /** 启动一个长任务（调用前 execute 已清理旧任务并 actionVersion++） */
    static void startTask(MinecraftClient client, ActionTask task, String actionName) {
        releaseAllKeys(client);
        actionInProgress = true;
        currentTask = task;
        currentTaskActionName = actionName;
        currentTaskCallId = currentCallId;
    }

    private static void cancelCurrentTask(MinecraftClient client) {
        if (currentTask != null) {
            releaseAllKeys(client);
        }
        currentTask = null;
        actionInProgress = false;
    }

    /** 统一动作锁：长任务进行中时为 true */
    public static boolean isActionInProgress() {
        return actionInProgress;
    }
    /**
     * 成本优先寻路（Dijkstra）：8 方向（含斜走）+ 1 格跳跃 + 2 格高台阶（触发垫脚）+
     * 下一格台阶。成本模型参考 Numen ActionCosts：平走 10、斜走 14、上跳 1 格 +6、
     * 下台阶 -1 格 +2、2 格高台阶 +40（路径点会触发 NavTask 垫脚）。
     */
    static List<BlockPos> findPath(ClientPlayerEntity player, double tx, double ty, double tz) {
        World world = player.getWorld();
        BlockPos start = player.getBlockPos();
        BlockPos goal = new BlockPos((int) Math.floor(tx), (int) Math.floor(ty), (int) Math.floor(tz));
        int range = 48;
        if (Math.abs(goal.getX() - start.getX()) > range || Math.abs(goal.getZ() - start.getZ()) > range) {
            return null;
        }
        if (!isStandable(world, start)) return null;

        // 成本优先队列（Dijkstra）：每次扩展当前最小成本节点
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Integer> costMap = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        PriorityQueue<BlockPos> queue = new PriorityQueue<>(
                Comparator.comparingInt(p -> costMap.getOrDefault(p, Integer.MAX_VALUE)));
        queue.add(start);
        costMap.put(start, 0);
        int maxNodes = 24000;

        // 8 方向：前后左右 + 4 个斜向
        int[] dx = {1, -1, 0, 0, 1, 1, -1, -1};
        int[] dz = {0, 0, 1, -1, 1, -1, 1, -1};

        BlockPos found = null;
        while (!queue.isEmpty() && visited.size() < maxNodes) {
            BlockPos cur = queue.poll();
            if (cur == null || visited.contains(cur)) continue; // 跳过过期条目
            visited.add(cur);
            // 到达判定：水平 ≤1 格、垂直差 ≤2 格
            if (Math.abs(cur.getX() - goal.getX()) <= 1
                    && Math.abs(cur.getZ() - goal.getZ()) <= 1
                    && Math.abs(cur.getY() - goal.getY()) <= 2) {
                found = cur;
                break;
            }
            int curCost = costMap.getOrDefault(cur, 0);
            for (int d = 0; d < 8; d++) {
                int nx = cur.getX() + dx[d];
                int nz = cur.getZ() + dz[d];
                boolean diagonal = d >= 4;
                if (diagonal) {
                    // 斜走要求两个相邻直行格不能同时是实体墙（MC 不允许穿过墙角）
                    BlockPos mid1 = new BlockPos(cur.getX() + dx[d], cur.getY(), nz);
                    BlockPos mid2 = new BlockPos(nx, cur.getY(), cur.getZ() + dz[d]);
                    if (isSolid(world, mid1) && isSolid(world, mid2)) continue;
                }
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos cand = new BlockPos(nx, cur.getY() + dy, nz);
                    if (cand.getY() < world.getBottomY() + 1 || cand.getY() > world.getTopY()) continue;
                    if (!isStandable(world, cand)) continue;
                    int stepCost = 10;
                    if (diagonal) stepCost = 14;
                    if (dy == 1) stepCost += 6;       // 跳上 1 格台阶
                    else if (dy == 2) stepCost += 40; // 2 格高台阶：路径点触发垫脚
                    else if (dy == -1) stepCost += 2; // 走下一格台阶
                    int newCost = curCost + stepCost;
                    if (visited.contains(cand)) continue;
                    Integer old = costMap.get(cand);
                    if (old == null || newCost < old) {
                        cameFrom.put(cand, cur);
                        costMap.put(cand, newCost);
                        queue.add(cand);
                    }
                }
            }
        }
        if (found == null) return null;

        // 回溯路径（不含起点）
        List<BlockPos> path = new ArrayList<>();
        BlockPos cur = found;
        while (cur != null && !cur.equals(start)) {
            path.add(0, cur);
            cur = cameFrom.get(cur);
        }
        return path;
    }

    /** 玩家脚部可站立在该位置：脚下有支撑、身体空间（本格+上方 1 格）非固体 */
    static boolean isStandable(World world, BlockPos pos) {
        if (!isSolid(world, pos) && !isSolid(world, pos.up())) {
            return isSolid(world, pos.down());
        }
        return false;
    }

    /** 是否实心方块（可站立/阻挡的完整固体，如石头/泥土/原木） */
    static boolean isSolid(World world, BlockPos pos) {
        BlockState s = world.getBlockState(pos);
        if (s.isAir()) return false;
        return s.isSolidBlock(world, pos);
    }

    /** 从背包找一个能当垫脚的方块槽位（优先手持/快捷栏，其次背包并交换到快捷栏） */
    static int findPillarSlot(MinecraftClient client, ClientPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        // 1) 当前手持
        ItemStack held = player.getMainHandStack();
        if (!held.isEmpty() && isPillarBlock(held)) {
            return inv.selectedSlot;
        }
        // 2) 快捷栏 0-8
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && isPillarBlock(stack)) {
                return i;
            }
        }
        // 3) 背包 9-35：交换到快捷栏 0
        for (int i = 9; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && isPillarBlock(stack)) {
                inv.selectedSlot = 0;
                client.interactionManager.pickFromInventory(i);
                return 0;
            }
        }
        return -1;
    }

    static boolean isPillarBlock(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem)) return false;
        Identifier id = Registries.ITEM.getId(stack.getItem());
        String idPath = id != null ? id.getPath().toLowerCase() : "";
        for (String k : NON_PILLAR_KEYWORDS) {
            if (idPath.contains(k)) return false;
        }
        return true;
    }

    /**
     * 挖掘前调用：确保手持背包中对目标方块最快的工具（镐/斧/锹等）。
     * 快捷栏有更快工具时直接切换；背包（9-35）有更快工具时交换到快捷栏。
     * 返回是否发生了切换。
     */
    static boolean ensureBestTool(ClientPlayerEntity player, BlockState targetState) {
        PlayerInventory inv = player.getInventory();
        ItemStack held = player.getMainHandStack();
        float heldScore = toolScore(held, targetState);
        int bestSlot = inv.selectedSlot;
        float bestScore = heldScore;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            float sc = toolScore(s, targetState);
            if (sc > bestScore) {
                bestScore = sc;
                bestSlot = i;
            }
        }
        if (bestSlot != inv.selectedSlot) {
            inv.selectedSlot = bestSlot;
            StateCollector.addBehaviorLog("挖掘前自动切换到更合适的工具（槽位 " + bestSlot + "）");
            return true;
        }
        // 快捷栏没有明显更快的：从背包找
        if (bestScore <= 1.05f) {
            for (int i = 9; i < 36; i++) {
                ItemStack s = inv.getStack(i);
                if (s.isEmpty()) continue;
                float sc = toolScore(s, targetState);
                if (sc > bestScore && sc > 1.05f) {
                    MinecraftClient.getInstance().interactionManager.pickFromInventory(i);
                    StateCollector.addBehaviorLog("挖掘前从背包切换更合适的工具");
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 工具评分（借鉴 Numen ToolSet）：综合挖掘速度 + 效率附魔加成；
     * 方块需要正确工具而当前工具不合适（挖了不掉落）时严重降权。
     */
    private static float toolScore(ItemStack stack, BlockState targetState) {
        if (stack.isEmpty()) return 0f;
        float speed = stack.getMiningSpeedMultiplier(targetState);
        // 效率附魔加成：原版逻辑为 speed>1 时 + (level^2+1)
        if (speed > 1.0f) {
            int eff = EnchantmentHelper.getLevel(Enchantments.EFFICIENCY, stack);
            if (eff > 0) speed += eff * eff + 1.0f;
        }
        boolean suitable = stack.isSuitableFor(targetState);
        boolean toolRequired = targetState.isToolRequired();
        if (toolRequired && !suitable) {
            return speed * 0.3f; // 非正确工具挖了不掉落，基本不选
        }
        return speed;
    }

    /** 水平搜索最近的露天出口（同层可站立且头顶 20 格连续空气），找不到返回 null */
    static BlockPos findOpenSkyExit(ClientPlayerEntity player, int range) {
        World world = player.getWorld();
        BlockPos playerPos = player.getBlockPos();
        for (int radius = 1; radius <= range; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    BlockPos p = new BlockPos(playerPos.getX() + dx, playerPos.getY(), playerPos.getZ() + dz);
                    if (!isStandable(world, p)) continue;
                    boolean open = true;
                    for (int y = p.getY() + 1; y < p.getY() + 20 && y < 320; y++) {
                        if (!world.getBlockState(new BlockPos(p.getX(), y, p.getZ())).isAir()) {
                            open = false;
                            break;
                        }
                    }
                    if (open) return p;
                }
            }
        }
        return null;
    }

    /** 查找玩家正前方（行进方向）的阻挡方块：先查相邻脚部/身体高度，其次视线射线 */
    static BlockPos findBlockInFront(ClientPlayerEntity player,
                                             double tx, double ty, double tz) {
        World world = player.getWorld();
        BlockPos pos = player.getBlockPos();
        double rad = Math.toRadians(player.getYaw());
        int dx = (int) Math.round(-Math.sin(rad));
        int dz = (int) Math.round(Math.cos(rad));
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos front = new BlockPos(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
            if (!world.getBlockState(front).isAir()) {
                return front;
            }
        }
        // 相邻一格没有方块：用视线射线找更远处的墙
        return findObstacleInFront(player, tx, ty, tz);
    }

    /** 在主线程同步查找前方障碍方块（排除目标本身） */
    static BlockPos findObstacleInFront(ClientPlayerEntity player,
                                                double tx, double ty, double tz) {
        HitResult hit = player.raycast(4.0, 0, false);
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos ob = ((BlockHitResult) hit).getBlockPos();
        if (Math.abs(ob.getX() - tx) < 1 &&
            Math.abs(ob.getY() - ty) < 1 &&
            Math.abs(ob.getZ() - tz) < 1) {
            return null; // 这就是目标
        }
        return ob;
    }

    static boolean isBreakable(ClientPlayerEntity player, BlockPos pos) {
        BlockState state = player.getWorld().getBlockState(pos);
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        String idStr = id != null ? id.getPath() : "";
        for (String key : BREAKABLE_BLOCKS) {
            if (idStr.contains(key)) return true;
        }
        return false;
    }

    /** 玩家当前面朝的水平方向（yaw 约定：0=南/+Z，顺时针） */
    static Direction facingDir(ClientPlayerEntity player) {
        int rot = Math.floorMod((int) Math.round(player.getYaw() / 90.0) + 2, 4);
        return Direction.fromHorizontal(rot);
    }

    /**
     * 链式挖掘标注：go_to_block 命中原木/矿物时，BFS 收集与之相连的相同 ID 方块，
     * 存入 {@link #markedBlocks}，之后 mc_digBlock 会连续挖完（树/整条矿脉）。
     * 排除树叶等不连续方块：只沿同 ID 传播。
     */
    static void markConnectedIfNeeded(ClientPlayerEntity player, BlockPos origin) {
        BlockState state = player.getWorld().getBlockState(origin);
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        if (id == null) return;
        String idPath = id.getPath().toLowerCase();
        boolean chainable = false;
        for (String k : MARK_KEYWORDS) {
            if (idPath.contains(k)) {
                chainable = true;
                break;
            }
        }
        if (!chainable) return;

        World world = player.getWorld();
        markedBlocks.clear();
        markTag = idPath;
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(origin);
        visited.add(origin);
        while (!queue.isEmpty() && markedBlocks.size() < MAX_MARKED) {
            BlockPos cur = queue.poll();
            markedBlocks.add(cur);
            for (int d = 0; d < 6; d++) {
                BlockPos nb = cur.add(MARK_DIR_X[d], MARK_DIR_Y[d], MARK_DIR_Z[d]);
                if (visited.add(nb)) {
                    Identifier nid = Registries.BLOCK.getId(world.getBlockState(nb).getBlock());
                    if (nid != null && nid.getPath().toLowerCase().equals(idPath)) {
                        queue.add(nb);
                    }
                }
            }
        }
        StateCollector.addBehaviorLog(
                "已标注 " + markedBlocks.size() + " 个相连方块(" + idPath + ")，调用 mc_digBlock 可连续挖完");
    }

    // ======================== 动作结果回传 ========================

    static void sendResult(String action, boolean success, String message) {
        sendResult(action, currentCallId, success, message);
    }

    static void sendResult(String action, long callId, boolean success, String message) {
        try {
            ControlServer server = MCControlMod.getServer();
            if (server != null) {
                JsonObject result = new JsonObject();
                result.addProperty("type", "action_result");
                result.addProperty("action", action);
                if (callId != 0) result.addProperty("call_id", callId);
                result.addProperty("success", success);
                result.addProperty("message", message);
                result.addProperty("timestamp", System.currentTimeMillis());
                server.sendActionResult(result.toString());
            }
        } catch (Exception e) {
            System.err.println("[MC-Control] Failed to send result: " + e.getMessage());
        }
    }

    // ======================== 寻路到方块（主线程同步搜索） ========================

    static void goToBlock(ClientPlayerEntity player, String blockType, double range) {
        MinecraftClient client = MinecraftClient.getInstance();
        World world = player.getWorld();
        BlockPos playerPos = player.getBlockPos();
        int r = (int) range;
        String targetLower = blockType.toLowerCase();

        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        int checked = 0;
        int maxCheck = 10000;

        for (int radius = 1; radius <= r && checked < maxCheck; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    for (int dy = -8; dy <= 8; dy++) {
                        if (checked >= maxCheck) break;
                        checked++;
                        BlockPos pos = playerPos.add(dx, dy, dz);
                        BlockState state = world.getBlockState(pos);
                        if (state.isAir()) continue;
                        Identifier id = Registries.BLOCK.getId(state.getBlock());
                        String idStr = id != null ? id.toString() : "";
                        String name = state.getBlock().getName().getString();
                        if (idStr.toLowerCase().contains(targetLower) || name.toLowerCase().contains(targetLower)) {
                            double hx = pos.getX() - playerPos.getX();
                            double hy = pos.getY() - playerPos.getY();
                            double hz = pos.getZ() - playerPos.getZ();
                            double wd = hx * hx + hz * hz + (hy * hy) * 9.0;
                            if (wd < nearestDist && wd > 0.25) {
                                nearestDist = wd;
                                nearest = pos;
                                if (wd < 4.0) break;
                            }
                        }
                    }
                }
            }
        }

        if (nearest != null) {
            // 链式挖掘标注：目标是原木/矿物时，自动标注相连的相同方块（树/矿脉），
            // 之后 mc_digBlock 会连续挖完所有标注方块
            markConnectedIfNeeded(player, nearest);
            // 不立即发送结果——让 NavTask 完成后发送到达/超时结果
            // emitResult=true 使 NavTask 在到达或超时时调用 sendResult
            startTask(client, new NavTask(player, nearest.getX() + 0.5, nearest.getY(),
                    nearest.getZ() + 0.5, true, "go_to_block"), "go_to_block");
        } else {
            System.out.println("[MC-Control] Block not found: " + blockType + " (checked " + checked + " blocks)");
            sendResult("go_to_block", false, "未找到方块: " + blockType);
        }
    }

    // ======================== 攻击实体 ========================

    static void attackEntity(ClientPlayerEntity player, String type, double range) {
        MinecraftClient client = MinecraftClient.getInstance();
        World world = player.getWorld();
        Box box = player.getBoundingBox().expand(range);
        List<Entity> entities = world.getOtherEntities(player, box,
                e -> e instanceof LivingEntity && !(e instanceof ClientPlayerEntity));

        Entity target = null;
        double nearest = Double.MAX_VALUE;
        for (Entity e : entities) {
            String name = e.getName().getString();
            if (!type.isEmpty() && !name.toLowerCase().contains(type.toLowerCase())) continue;
            double dist = player.squaredDistanceTo(e);
            if (dist < nearest) {
                nearest = dist;
                target = e;
            }
        }

        if (target == null) {
            sendResult("attack_entity", false, "未找到实体" + (type.isEmpty() ? "" : ": " + type));
            return;
        }

        double dx = target.getX() - player.getX();
        double dy = (target.getY() + target.getHeight() / 2) - (player.getY() + player.getEyeHeight(player.getPose()));
        double dz = target.getZ() - player.getZ();
        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setPitch((float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));

        client.options.attackKey.setPressed(true);
        scheduler.schedule(() ->
                client.execute(() -> client.options.attackKey.setPressed(false)),
                1500, TimeUnit.MILLISECONDS);
        sendResult("attack_entity", true, "攻击 " + target.getName().getString());
    }

    // ======================== 装备物品 ========================

    static void equipItem(ClientPlayerEntity player, String itemName) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.getItem().getName().getString()
                    .toLowerCase().contains(itemName.toLowerCase())) {
                if (i < 9) {
                    inv.selectedSlot = i;
                } else {
                    MinecraftClient.getInstance().interactionManager.pickFromInventory(i);
                }
                MinecraftClient.getInstance().interactionManager
                        .interactItem(player, Hand.MAIN_HAND);
                sendResult("equip", true, "已装备 " + itemName);
                return;
            }
        }
        sendResult("equip", false, "未找到物品: " + itemName);
    }

    // ======================== 吃/喝 ========================

    private static void consumeItem(ClientPlayerEntity player, String itemName) {
        PlayerInventory inv = player.getInventory();
        int slot = -1;
        String foundName = "";
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String name = stack.getItem().getName().getString();
            if (itemName.isEmpty() && stack.getItem().isFood()) {
                slot = i;
                foundName = name;
                break;
            }
            if (name.toLowerCase().contains(itemName.toLowerCase())) {
                slot = i;
                foundName = name;
                break;
            }
        }
        if (slot == -1) {
            sendResult("consume", false, "未找到: " + itemName);
            return;
        }

        if (slot < 9) {
            inv.selectedSlot = slot;
        } else {
            MinecraftClient.getInstance().interactionManager.pickFromInventory(slot);
        }

        MinecraftClient client = MinecraftClient.getInstance();
        client.options.useKey.setPressed(true);
        scheduler.schedule(() ->
                client.execute(() -> client.options.useKey.setPressed(false)),
                2000, TimeUnit.MILLISECONDS);
        sendResult("consume", true, "已消耗: " + foundName);
    }

    // ======================== 合成（服务端同步） ========================

    /**
     * 尝试用熔炼配方自动烧制：原料足够时找附近熔炉并启动 FurnaceSmeltTask。
     * 返回 true 表示已处理（启动了任务或返回了明确提示）。
     */
    private static boolean trySmeltRecipe(MinecraftClient client, ClientPlayerEntity player,
                                          List<RecipeLookup.RecipeInfo> allRecipes, int count) {
        RecipeLookup.RecipeInfo cook = null;
        for (RecipeLookup.RecipeInfo r : allRecipes) {
            if ("smelting".equals(r.type) || "blasting".equals(r.type) || "smoking".equals(r.type)) {
                List<RecipeLookup.RequiredIngredient> req = RecipeLookup.getRequiredIngredients(r);
                boolean hasAll = true;
                for (RecipeLookup.RequiredIngredient ing : req) {
                    if (countItemByIngredient(player.getInventory(), ing.matchIds) < ing.count) {
                        hasAll = false;
                        break;
                    }
                }
                if (hasAll) {
                    cook = r;
                    break;
                }
            }
        }
        if (cook == null) return false;

        String furnaceType = "smelting".equals(cook.type) ? "furnace"
                : ("blasting".equals(cook.type) ? "blast_furnace" : "smoker");
        BlockPos furnacePos = findNearestBlock(player, furnaceType, 8);
        if (furnacePos == null) {
            sendResult("craft", false,
                    "该物品需要通过" + ("smelting".equals(cook.type) ? "熔炉"
                            : ("blasting".equals(cook.type) ? "高炉" : "烟熏炉"))
                            + "烧炼（烧炼原料已就绪），但附近没有找到。"
                            + "请先放置一个（mc_place " + furnaceType + "），然后再次调用 mc_craft。");
            return true;
        }
        StateCollector.addBehaviorLog("检测到熔炼配方，使用" + furnaceType + "自动烧制");
        startTask(client, new FurnaceSmeltTask(cook, count, furnacePos), "craft");
        return true;
    }

    /**
     * 自动合成物品。使用 RecipeLookup 动态查询所有配方，
     * 逐个尝试直到找到材料足够的配方，然后通过 ScreenHandler 进行服务端同步合成。
     * 不再直接修改客户端背包，而是通过 clickRecipe + clickSlot 发送合成数据包给服务端。
     */
    private static void craftItem(MinecraftClient client, ClientPlayerEntity player,
                                    String recipe, int count) {
        if (recipe.isEmpty()) {
            sendResult("craft", false, "合成配方为空");
            return;
        }

        // 动态查询所有配方
        List<RecipeLookup.RecipeInfo> allRecipes = RecipeLookup.findRecipes(recipe);
        if (allRecipes.isEmpty()) {
            sendResult("craft", false,
                "未找到 " + recipe + " 的合成配方。该物品可能需要通过其他方式获得"
                + "（挖矿、打怪、交易等），或物品 ID 不正确。"
                + "可用 mc_queryRecipe 工具查询。");
            return;
        }

        // 筛选可在合成台/背包完成的配方（排除熔炼、切石机等）
        List<RecipeLookup.RecipeInfo> craftableRecipes = new ArrayList<>();
        boolean hasCookingRecipe = false;
        for (RecipeLookup.RecipeInfo r : allRecipes) {
            if ("crafting_shaped".equals(r.type) || "crafting_shapeless".equals(r.type)
                    || "crafting_special".equals(r.type)) {
                craftableRecipes.add(r);
            } else if ("smelting".equals(r.type) || "blasting".equals(r.type)
                    || "smoking".equals(r.type)) {
                hasCookingRecipe = true;
            }
        }

        if (craftableRecipes.isEmpty()) {
            if (hasCookingRecipe) {
                // 材料足够时自动用熔炉/高炉/烟熏炉烧制
                if (trySmeltRecipe(client, player, allRecipes, count)) return;
                sendResult("craft", false,
                    "该物品只能通过熔炼获得，不能用工作台合成，"
                    + "且背包中没有对应的烧炼原料。"
                    + "需要使用熔炉/高炉/烟熏炉，并放入燃料。");
            } else {
                sendResult("craft", false, "未找到可用的合成台配方。可用 mc_queryRecipe 查看所有配方。");
            }
            return;
        }

        PlayerInventory inv = player.getInventory();

        // 逐个尝试每个配方，找到第一个材料足够的
        RecipeLookup.RecipeInfo selected = null;
        List<String> missingInfo = new ArrayList<>();

        for (RecipeLookup.RecipeInfo r : craftableRecipes) {
            List<RecipeLookup.RequiredIngredient> req =
                    RecipeLookup.getRequiredIngredients(r);
            boolean hasAll = true;
            for (RecipeLookup.RequiredIngredient ingredient : req) {
                int need = ingredient.count * count;
                int have = countItemByIngredient(inv, ingredient.matchIds);
                if (have < need) {
                    hasAll = false;
                    missingInfo.add(ingredient.displayName + " ×" + need + "(仅有" + have + ")");
                    break;
                }
            }
            if (hasAll) {
                selected = r;
                break;
            }
        }

        if (selected == null) {
            // 所有合成台配方材料都不足：如果有熔炼配方且原料足够，自动改用熔炉烧制
            if (hasCookingRecipe && trySmeltRecipe(client, player, allRecipes, count)) {
                return;
            }
            // 所有配方材料都不足
            StringBuilder sb = new StringBuilder("材料不足，已尝试全部 ");
            sb.append(craftableRecipes.size()).append(" 个合成台配方均无法合成。");
            sb.append("缺少的材料: ");
            sb.append(String.join("; ", missingInfo));
            if (hasCookingRecipe) {
                sb.append("。该物品也可通过熔炼获得（需要熔炉+燃料+对应原料），可用 mc_queryRecipe 查看。");
            }
            sb.append("。可用 mc_queryRecipe 查看所有配方和所需材料。");
            sendResult("craft", false, sb.toString());
            return;
        }

        // 记录合成前的物品数量（用于验证合成结果）
        String outputId = selected.outputItemId.contains(":")
                ? selected.outputItemId.substring(selected.outputItemId.indexOf(':') + 1)
                : selected.outputItemId;
        int beforeCount = countItemByIngredient(inv, Set.of(outputId));

        // 判断是否需要工作台
        boolean needsTable = "crafting_table".equals(selected.station);
        BlockPos tablePos = null;

        if (needsTable) {
            // 搜索附近的工作台（5 格范围）
            tablePos = findNearestBlock(player, "crafting_table", 5);
            if (tablePos == null) {
                sendResult("craft", false,
                    "需要工作台来合成此物品（3×3 配方），但附近没有工作台。"
                    + "请先放置一个工作台或靠近工作台后重试。"
                    + "如果你的背包有工作台，先用 mc_place 放置到地上。");
                return;
            }
        }

        // 启动合成任务（tick 状态机，确保服务端同步）
        startTask(client, new CraftTask(selected, count, tablePos, beforeCount, outputId), "craft");
    }

    /** 查询物品的合成配方，返回配方详情供 AI 参考 */
    private static void queryRecipe(String itemId) {
        if (itemId.isEmpty()) {
            sendResult("query_recipe", false, "物品 ID 为空");
            return;
        }

        List<RecipeLookup.RecipeInfo> recipes = RecipeLookup.findRecipes(itemId);
        String result = RecipeLookup.formatRecipeResults(itemId, recipes);
        // 查询的是炉子本身时，附加烧炼用法提示（避免 AI 把"制作熔炉"误当"烧炼方法"）
        String lower = itemId.toLowerCase();
        if (lower.equals("furnace") || lower.equals("blast_furnace") || lower.equals("smoker")) {
            String display = lower.equals("blast_furnace") ? "高炉"
                    : (lower.equals("smoker") ? "烟熏炉" : "熔炉");
            result += "\n\n【提示】以上是制作" + display + "的方法。"
                    + "若你想烧炼物品（如 raw_iron → iron_ingot、铁矿石 → 铁锭），"
                    + "请查询目标产物（如 mc_queryRecipe iron_ingot），"
                    + "然后在附近放置炉子后直接调用 mc_craft。";
        } else if (lower.equals("iron_ingot") || lower.equals("gold_ingot")) {
            result += "\n\n【提示】铁锭/金锭通常有两种途径："
                    + "① 熔炉烧炼（矿石/粗金属 + 燃料，需要熔炉）；"
                    + "② 工作台合成（铁粒/铁块分解）。"
                    + "如果你有粗铁/铁矿石，请先放置熔炉（mc_place furnace）再调用 mc_craft。";
        }
        boolean success = !recipes.isEmpty();
        sendResult("query_recipe", success, result);
    }

    /** 统计背包中匹配指定材料集合的物品数量（支持标签，如任意木板均可） */
    static int countItemByIngredient(PlayerInventory inv, Set<String> matchIds) {
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            Identifier id = Registries.ITEM.getId(stack.getItem());
            if (id != null && matchIds.contains(id.getPath())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 搜索玩家附近最近的指定方块（用于查找工作台等） */
    private static BlockPos findNearestBlock(ClientPlayerEntity player, String blockType, int range) {
        World world = player.getWorld();
        BlockPos playerPos = player.getBlockPos();
        String targetLower = blockType.toLowerCase();

        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos pos = playerPos.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) continue;
                    Identifier id = Registries.BLOCK.getId(state.getBlock());
                    String idStr = id != null ? id.toString() : "";
                    if (idStr.toLowerCase().contains(targetLower)) {
                        double dist = player.squaredDistanceTo(
                                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                        if (dist < nearestDist) {
                            nearestDist = dist;
                            nearest = pos;
                        }
                    }
                }
            }
        }

        return nearest;
    }

    // ======================== 工具方法 ========================

    static void releaseAllKeys(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.attackKey.setPressed(false);
        client.options.useKey.setPressed(false);
    }

    private static void move(ClientPlayerEntity player, String direction, double duration) {
        MinecraftClient client = MinecraftClient.getInstance();
        KeyBinding key = switch (direction) {
            case "forward" -> client.options.forwardKey;
            case "back" -> client.options.backKey;
            case "left" -> client.options.leftKey;
            case "right" -> client.options.rightKey;
            default -> client.options.forwardKey;
        };
        key.setPressed(true);
        scheduler.schedule(() ->
                client.execute(() -> key.setPressed(false)),
                (long) (duration * 1000), TimeUnit.MILLISECONDS);
    }
}
