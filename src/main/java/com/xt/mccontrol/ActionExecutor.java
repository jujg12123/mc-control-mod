package com.xt.mccontrol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
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
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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
    private static final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2);

    // === 任务状态机 ===
    private interface ActionTask {
        /**
         * 每个客户端 tick 在主线程调用一次。
         *
         * @return true 表示任务完成（会自动清理 currentTask 与 actionInProgress）
         */
        boolean tick(MinecraftClient client, ClientPlayerEntity player);
    }

    private static ActionTask currentTask = null;
    /** 统一动作锁：长任务进行中为 true，自动行为应让位（保命除外） */
    private static volatile boolean actionInProgress = false;
    private static volatile boolean navCancelled = false;
    private static volatile long actionVersion = 0;
    /** 当前动作的 call_id（由插件生成，回传结果时原样带回） */
    private static long currentCallId = 0;
    /** 当前长任务的来源动作名与 call_id（用于任务被打断时回传结果） */
    private static String currentTaskActionName = "unknown";
    private static long currentTaskCallId = 0;

    // 白名单：只有这些方块在导航时会被尝试破坏
    private static final Set<String> BREAKABLE_BLOCKS = Set.of(
        "dirt", "grass_block", "sand", "gravel", "tall_grass", "leaves",
        "cobblestone", "stone", "oak_planks", "oak_log", "birch_log", "spruce_log",
        "netherrack", "end_stone"
    );

    // === 连续挖掘标注（树/矿脉）===
    // go_to_block 命中原木/矿物时，BFS 收集相连同 ID 方块存到这里，
    // 之后 mc_digBlock 会连续挖完这些方块（自动清理遮挡、自动垫脚）
    private static final LinkedHashSet<BlockPos> markedBlocks = new LinkedHashSet<>();
    private static String markTag = "";
    private static final Set<String> MARK_KEYWORDS = Set.of("log", "ore");
    private static final int MAX_MARKED = 64;
    private static final int[] MARK_DIR_X = {1, -1, 0, 0, 0, 0};
    private static final int[] MARK_DIR_Y = {0, 0, 1, -1, 0, 0};
    private static final int[] MARK_DIR_Z = {0, 0, 0, 0, 1, -1};
    // 不能拿来垫脚的 BlockItem（门/火把/红石等无法踩踏的方块）
    private static final Set<String> NON_PILLAR_KEYWORDS = Set.of(
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
    private static void startTask(MinecraftClient client, ActionTask task, String actionName) {
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

    // ======================== SmoothLookTask：平滑视角转动 ========================

    /**
     * 模拟鼠标移动的平滑视角转动（借鉴 Baritone LookBehavior 的核心思路）。
     * <p>
     * 每个 tick 将角度差量化为"鼠标像素"位移，再按 Minecraft 灵敏度公式换算回角度，
     * 使视角以自然的步长渐进逼近目标，而非瞬间跳变。
     * <ul>
     *   <li>鼠标灵敏度公式复刻自原版 {@code Mouse.updateMouse}</li>
     *   <li>每 tick 最大转角限制 25°，避免过快旋转</li>
     *   <li>角度差 < 1° 时直接到位并完成</li>
     * </ul>
     */
    private static class SmoothLookTask implements ActionTask {
        private final float targetYaw;
        private final float targetPitch;
        private final long myVersion;
        private final long callId = currentCallId;
        private int totalTicks = 0;
        private static final int MAX_TICKS = 100; // 5 秒超时

        SmoothLookTask(float yaw, float pitch) {
            this.targetYaw = yaw;
            this.targetPitch = pitch;
            this.myVersion = actionVersion;
        }

        @Override
        public boolean tick(MinecraftClient client, ClientPlayerEntity player) {
            if (actionVersion != myVersion || navCancelled) {
                return true;
            }

            totalTicks++;
            if (totalTicks > MAX_TICKS) {
                // 超时，直接到位
                player.setYaw(targetYaw);
                player.setPitch(targetPitch);
                sendResult("look_at", callId, true, "视角已调整 (超时强制到位)");
                return true;
            }

            float currentYaw = player.getYaw();
            float currentPitch = player.getPitch();

            // 计算最短角度差（处理 360° 环绕）
            float yawDelta = MathHelper.wrapDegrees(targetYaw - currentYaw);
            float pitchDelta = targetPitch - currentPitch;

            // 角度差足够小，直接到位
            if (Math.abs(yawDelta) < 1.0f && Math.abs(pitchDelta) < 1.0f) {
                player.setYaw(targetYaw);
                player.setPitch(targetPitch);
                sendResult("look_at", callId, true,
                    String.format("视角已调整 (yaw=%.1f, pitch=%.1f, %d ticks)",
                        targetYaw, targetPitch, totalTicks));
                return true;
            }

            // 获取鼠标灵敏度（0.0 ~ 1.0，默认 0.5）
            // 1.20.1 中 mouseSensitivity 是私有字段，用反射读取
            double sensitivity = 0.5;
            try {
                java.lang.reflect.Field f = client.options.getClass()
                        .getDeclaredField("mouseSensitivity");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                net.minecraft.client.option.SimpleOption<Double> opt =
                        (net.minecraft.client.option.SimpleOption<Double>) f.get(client.options);
                sensitivity = opt.getValue();
            } catch (Exception e) {
                // 反射失败时使用默认灵敏度 0.5
            }
            // Minecraft 原版鼠标灵敏度公式
            double f = sensitivity * 0.6 + 0.2;
            double anglePerPixel = f * f * f * 8.0 * 0.15;

            // 量化为鼠标像素并换算回角度（模拟真实鼠标输入）
            float yawStep = stepToward(yawDelta, anglePerPixel);
            float pitchStep = stepToward(pitchDelta, anglePerPixel);

            // 限制每 tick 最大转角（25°/tick ≈ 500°/秒，自然但不过慢）
            yawStep = MathHelper.clamp(yawStep, -25.0f, 25.0f);
            pitchStep = MathHelper.clamp(pitchStep, -25.0f, 25.0f);

            player.setYaw(currentYaw + yawStep);
            player.setPitch(MathHelper.clamp(currentPitch + pitchStep, -90.0f, 90.0f));

            return false;
        }

        /**
         * 将角度差量化为鼠标像素，再换算回角度步长。
         * 这模拟了真实鼠标的离散输入特性。
         */
        private float stepToward(float delta, double anglePerPixel) {
            if (anglePerPixel <= 0) return delta; // 防除零
            int pixels = Math.round(delta / (float) anglePerPixel);
            if (pixels == 0) {
                // 小于一个像素的角度差，直接返回完整 delta（下个 tick 到位）
                return delta;
            }
            return pixels * (float) anglePerPixel;
        }
    }

    // ======================== NavTask：寻路状态机 ========================

    /**
     * 主线程 tick 驱动的寻路。所有 player/world 读写都在主线程，无后台线程、无 sleep。
     * 卡住时进入 BREAKING（挖障碍）或 BYPASS（绕行）子状态。
     */
    private static class NavTask implements ActionTask {
        private static final int NAV = 0, BREAKING = 1, BYPASS = 2, PILLAR = 3, DRILL_DOWN = 4;
        private final double tx, ty, tz;
        private final boolean emitResult;
        private final long myVersion;
        private final long callId;
        private final String resultAction;
        private final long startTime;
        private double lastCheckX, lastCheckZ;   // 卡住检测窗口起点（每 0.5s 结算）
        private int checkCounter = 0;
        private int stuckCount = 0;              // 连续无进展的窗口数
        private int jumpTicks = 0;               // 跳跃按键保持 tick 数
        private int jumpCooldown = 0;            // 跳跃间隔（避免连续蹦跳）
        private int totalTicks = 0;
        private final int maxTicks = 600;        // 约 30 秒（20 TPS）
        private int strafeDir = 0;
        private int state = NAV;
        private int stateTicks = 0;
        private BlockPos targetObstacle = null;  // BREAKING 正在挖掘的方块
        private double bypassStartX = 0, bypassStartZ = 0;
        // A* 路径点（玩家当前层可行走路径），沿路径点走避免直线撞墙/掉坑
        private List<BlockPos> path = null;
        private int pathIdx = 0;
        private int replanTicks = 0;
        private BlockPos pillarPos = null;   // PILLAR 已放置的垫脚方块
        private boolean pillarJumped = false;

        NavTask(ClientPlayerEntity player, double tx, double ty, double tz, boolean emitResult, String resultAction) {
            this.tx = tx; this.ty = ty; this.tz = tz;
            this.emitResult = emitResult;
            this.resultAction = resultAction;
            this.callId = currentCallId;
            this.myVersion = actionVersion;
            this.startTime = System.currentTimeMillis();
            this.lastCheckX = player.getX();
            this.lastCheckZ = player.getZ();
        }

        @Override
        public boolean tick(MinecraftClient client, ClientPlayerEntity player) {
            // 被新动作取消
            if (actionVersion != myVersion || navCancelled) {
                return true;
            }
            // ---- 子状态：挖掘障碍 ----
            if (state == BREAKING) {
                return tickBreaking(client, player);
            }
            // ---- 子状态：绕行 ----
            if (state == BYPASS) {
                return tickBypass(client, player);
            }
            // ---- 子状态：向下挖穿（目标在脚下）----
            if (state == DRILL_DOWN) {
                return tickDrillDown(client, player);
            }
            // ---- 子状态：垫脚（目标太高够不着）----
            if (state == PILLAR) {
                return tickPillar(client, player);
            }

            // ---- 正常导航 ----
            totalTicks++;
            double px = player.getX();
            double py = player.getY() + 0.5;
            double pz = player.getZ();
            double dx = tx - px;
            double dy = ty - py;
            double dz = tz - pz;

            // 到达判定：水平贴住目标即可，垂直方向允许站在目标正上方或正下方
            // （目标方块四周被围住时，只能从上方/下方接近，旧版只认侧面导致误报失败）
            double hDist = Math.sqrt(dx * dx + dz * dz);
            double vDist = Math.abs(dy);
            if (hDist < 1.2 && vDist < 2.2) {
                // 到达后把过度朝下的视角抬平，避免后续放置/观察视角异常
                if (player.getPitch() < -45) player.setPitch(0);
                if (emitResult) sendResult(resultAction, callId, true, "已到达目标位置");
                return true;
            }
            if (totalTicks >= maxTicks || System.currentTimeMillis() - startTime > 32000) {
                if (emitResult) sendResult(resultAction, callId, false, "导航超时");
                return true;
            }

            // 目标在脚下（水平已贴住但垂直差较大）：直接向下挖穿，不再对着地面抽动视角
            if (hDist < 1.5 && ty < player.getY() - 1.2) {
                startDrillDown(client, player);
                return false;
            }
            // 目标太高且水平已贴近（够不着）：尝试垫脚
            double eyeDy = ty - (player.getY() + player.getEyeHeight(player.getPose()));
            if (eyeDy > 3.4 && hDist < 2.2) {
                startPillar(client, player);
                return false;
            }

            // ---- 路径规划：沿可行走路径移动，自动绕开障碍/台阶/坑 ----
            replanTicks--;
            if (path == null || pathIdx >= path.size() || replanTicks <= 0) {
                path = findPath(player, tx, ty, tz);
                pathIdx = 0;
                replanTicks = 15;
            }

            // 目标点：当前路径点（有路径时），否则直线兜底
            double gx = tx, gz = tz;
            if (path != null && pathIdx < path.size()) {
                BlockPos wp = path.get(pathIdx);
                gx = wp.getX() + 0.5;
                gz = wp.getZ() + 0.5;
                double wdx = gx - px;
                double wdz = gz - pz;
                if (Math.sqrt(wdx * wdx + wdz * wdz) < 0.8) {
                    pathIdx++;
                    if (pathIdx >= path.size()) {
                        path = null;   // 路径走完：重新规划或直线到目标
                    }
                }
            }
            double gdx = gx - px;
            double gdz = gz - pz;
            double yaw = Math.toDegrees(Math.atan2(-gdx, gdz));
            // 移动时保持水平视角（不再朝目标上下甩头，避免视角抽搐；挖掘在子状态内进行）
            double pitch = 0;
            // 跳跃需求：路径点比玩家高 1 格以上时需要跳上去
            double needJump = 0;
            if (path != null && pathIdx < path.size()) {
                needJump = path.get(pathIdx).getY() + 0.5 - player.getY();
            } else if (ty > player.getY() + 3.0) {
                pitch = Math.min(25, Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
            }

            // 跳跃按键计时（跳 4 tick、停 12 tick，避免连续蹦跳）
            if (needJump > 1.1) {
                if (jumpCooldown <= 0) {
                    jumpTicks = 4;
                    jumpCooldown = 12;
                }
            } else {
                jumpCooldown = 0;
            }
            if (jumpCooldown > 0) jumpCooldown--;
            if (jumpTicks > 0) {
                jumpTicks--;
                client.options.jumpKey.setPressed(true);
            } else {
                client.options.jumpKey.setPressed(false);
            }

            // ---- 卡住检测：每 10 tick（0.5s）结算一次位移，避免逐 tick 抖动误判 ----
            checkCounter++;
            if (checkCounter >= 10) {
                double moved = Math.abs(player.getX() - lastCheckX)
                        + Math.abs(player.getZ() - lastCheckZ);
                boolean tryingToMove = client.options.forwardKey.isPressed()
                        || client.options.backKey.isPressed()
                        || client.options.leftKey.isPressed()
                        || client.options.rightKey.isPressed();
                if (tryingToMove && moved < 0.15) {
                    stuckCount++;
                } else {
                    stuckCount = 0;
                }
                lastCheckX = player.getX();
                lastCheckZ = player.getZ();
                checkCounter = 0;

                if (stuckCount >= 2) {
                    // 卡住 ≥1 秒：路径失效，强制重新规划
                    path = null;
                    // 目标在脚下（被地面挡住走不动）：直接挖脚下
                    if (hDist < 1.5 && ty < player.getY() - 0.5) {
                        startDrillDown(client, player);
                        return false;
                    }
                    // 优先尝试挖掘正前方的阻挡方块
                    BlockPos obstacle = findBlockInFront(player, tx, ty, tz);
                    if (obstacle != null) {
                        if (isBreakable(player, obstacle)) {
                            startDigging(client, player, obstacle);
                            return false;
                        }
                        // 不可破坏（基岩等）：绕行
                        startBypass(client, player);
                        return false;
                    }
                    // 前方没有方块但头顶被挡住：挖头顶
                    BlockPos headBlock = player.getBlockPos().up();
                    if (!player.getWorld().getBlockState(headBlock).isAir()) {
                        startDigging(client, player, headBlock);
                        return false;
                    }
                    // 都不是：跳一下试试脱困
                    applyMove(client, player, yaw, pitch, true, 0);
                } else if (stuckCount == 1) {
                    // 卡住 0.5s：先跳一下（可能卡在台阶/栅栏上）
                    applyMove(client, player, yaw, pitch, true, 0);
                } else {
                    applyMove(client, player, yaw, pitch, false, 0);
                }
            } else {
                applyMove(client, player, yaw, pitch, false, 0);
            }
            return false;
        }
        /** 向下挖穿子状态：目标在脚下时挖穿脚下地面逐层下降（不再对着目标猛低头导致视角抽搐） */
        private boolean tickDrillDown(MinecraftClient client, ClientPlayerEntity player) {
            stateTicks++;
            double dx = tx - player.getX();
            double dz = tz - player.getZ();
            double hDist = Math.sqrt(dx * dx + dz * dz);
            // 已接近目标层：结束下挖
            if (hDist < 1.2 && Math.abs(ty - player.getY()) < 2.2) {
                endDrillDown(client, player);
                return false;
            }
            BlockPos foot = player.getBlockPos().down();
            BlockState footState = player.getWorld().getBlockState(foot);
            String name = footState.getBlock().getName().getString().toLowerCase();
            if (footState.isAir()) {
                // 脚下已挖空：等待自然下落（一次只掉 1 格，无摔落伤害）
                client.options.attackKey.setPressed(false);
                client.options.sneakKey.setPressed(false);
                client.options.forwardKey.setPressed(false);
                return false;
            }
            // 危险/不可挖方块：放弃并提示
            if (name.contains("lava") || name.contains("water") || name.contains("bedrock")
                    || name.contains("obsidian")) {
                client.options.attackKey.setPressed(false);
                sendResult(resultAction, callId, false,
                        "目标下方是" + name + "，无法继续下挖，请换位置或换目标");
                return true;
            }
            // 向下看并挖掘脚下方块（水平方向仍朝目标）
            player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            player.setPitch(90f);
            client.options.forwardKey.setPressed(false);
            client.options.sneakKey.setPressed(false);
            client.options.attackKey.setPressed(true);
            // 挖了 8 秒仍未挖穿（基岩/黑曜石等）：放弃
            if (stateTicks > 160) {
                client.options.attackKey.setPressed(false);
                sendResult(resultAction, callId, false, "脚下方块无法挖掘，已停止下挖（请换位置）");
                return true;
            }
            return false;
        }

        private void startDrillDown(MinecraftClient client, ClientPlayerEntity player) {
            state = DRILL_DOWN;
            stateTicks = 0;
            client.options.attackKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
            StateCollector.addBehaviorLog("目标在脚下，开始向下挖掘");
        }

        private void endDrillDown(MinecraftClient client, ClientPlayerEntity player) {
            client.options.attackKey.setPressed(false);
            client.options.sneakKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
            state = NAV;
            stuckCount = 0;
            lastCheckX = player.getX();
            lastCheckZ = player.getZ();
            checkCounter = 0;
            path = null;
        }

        /** 垫脚子状态：目标太高够不着时，从背包找方块垫到身边并跳上去 */
        private boolean tickPillar(MinecraftClient client, ClientPlayerEntity player) {
            stateTicks++;
            if (pillarPos != null) {
                // 已放置垫脚方块：朝目标前进+跳跃，直到站上垫脚方块
                double dx = tx - player.getX();
                double dz = tz - player.getZ();
                player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                player.setPitch(0);
                client.options.forwardKey.setPressed(true);
                client.options.jumpKey.setPressed(true);
                if (player.getY() > pillarPos.getY() + 0.9) {
                    endPillar(client, player);
                    return false;
                }
                if (stateTicks > 40) {
                    // 2 秒没站上去：放弃垫脚
                    endPillar(client, player);
                    StateCollector.addBehaviorLog("寻路垫脚失败，继续尝试直接前进");
                    return false;
                }
                return false;
            }
            // 找垫脚方块并放置到前方一格的地面上
            int slot = findPillarSlot(client, player);
            if (slot < 0) {
                StateCollector.addBehaviorLog("背包没有可垫脚的方块，放弃垫脚");
                state = NAV;
                return false;
            }
            player.getInventory().selectedSlot = slot;
            double dx = tx - player.getX();
            double dz = tz - player.getZ();
            player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            player.setPitch(0);
            BlockPos front = player.getBlockPos().offset(facingDir(player));
            BlockPos ground = front.down();
            World world = player.getWorld();
            if (!world.getBlockState(front).isAir()) {
                // 前方已有方块：直接跳上去
                pillarPos = front;
                stateTicks = 0;
                return false;
            }
            if (!world.getBlockState(ground).isAir()) {
                Vec3d hitPos = new Vec3d(ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5);
                BlockHitResult bhr = new BlockHitResult(hitPos, Direction.UP, ground, false);
                ActionResult res = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, bhr);
                if (res.isAccepted()) {
                    pillarPos = front;
                    stateTicks = 0;
                    StateCollector.addBehaviorLog("寻路垫脚: 放置 " + front.toShortString());
                    return false;
                }
            }
            // 放置失败（悬崖/低洼等）：放弃垫脚
            StateCollector.addBehaviorLog("寻路垫脚: 放置失败");
            state = NAV;
            return false;
        }

        private void startPillar(MinecraftClient client, ClientPlayerEntity player) {
            state = PILLAR;
            stateTicks = 0;
            pillarPos = null;
            client.options.attackKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
        }

        private void endPillar(MinecraftClient client, ClientPlayerEntity player) {
            client.options.forwardKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            pillarPos = null;
            pillarJumped = false;
            state = NAV;
            path = null;   // 玩家位置已变，强制重新规划
            stuckCount = 0;
            lastCheckX = player.getX();
            lastCheckZ = player.getZ();
            checkCounter = 0;
        }

        /** 挖掘障碍子状态：锁定瞄准目标方块并持续挖掘，直到方块被破坏 */
        private boolean tickBreaking(MinecraftClient client, ClientPlayerEntity player) {
            stateTicks++;
            if (targetObstacle == null) {
                // 兜底：用视线射线寻找目标
                BlockPos fallback = findObstacleInFront(player, tx, ty, tz);
                if (fallback != null) {
                    targetObstacle = fallback;
                } else {
                    endBreaking(client, player);
                    return false;
                }
            }

            // 持续瞄准方块中心（玩家位置可能滑动），保证挖掘命中、视角稳定
            double dx = targetObstacle.getX() + 0.5 - player.getX();
            double dy = targetObstacle.getY() + 0.5
                    - (player.getY() + player.getEyeHeight(player.getPose()));
            double dz = targetObstacle.getZ() + 0.5 - player.getZ();
            player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            player.setPitch((float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
            client.options.attackKey.setPressed(true);

            // 目标已被破坏
            if (player.getWorld().getBlockState(targetObstacle).isAir()) {
                endBreaking(client, player);
                StateCollector.addBehaviorLog("寻路障碍已清除");
                return false;
            }
            // 超过 4 秒仍未破坏（基岩/黑曜石等不可挖掘方块）：放弃挖掘改绕行
            if (stateTicks > 80) {
                endBreaking(client, player);
                strafeDir = (strafeDir == 0) ? 1 : (strafeDir == 1 ? -1 : 1);
                state = BYPASS;
                stateTicks = 0;
                bypassStartX = player.getX();
                bypassStartZ = player.getZ();
                StateCollector.addBehaviorLog("障碍无法挖掘，尝试绕行");
            }
            return false;
        }

        /** 结束挖掘：释放按键，重置卡住计数，回到正常导航 */
        private void endBreaking(MinecraftClient client, ClientPlayerEntity player) {
            client.options.attackKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            targetObstacle = null;
            stuckCount = 0;
            lastCheckX = player.getX();
            lastCheckZ = player.getZ();
            checkCounter = 0;
            state = NAV;
        }

        /** 开始挖掘指定方块：锁定视角、按住攻击键；方块较远时同时前进 */
        private void startDigging(MinecraftClient client, ClientPlayerEntity player, BlockPos obstacle) {
            state = BREAKING;
            stateTicks = 0;
            targetObstacle = obstacle;
            client.options.jumpKey.setPressed(false);
            // 自动切换更合适的工具
            ensureBestTool(player, player.getWorld().getBlockState(obstacle));
            client.options.attackKey.setPressed(true);
            double dx = obstacle.getX() + 0.5 - player.getX();
            double dy = obstacle.getY() + 0.5
                    - (player.getY() + player.getEyeHeight(player.getPose()));
            double dz = obstacle.getZ() + 0.5 - player.getZ();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            player.setPitch((float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
            // 方块在 1.6 格外时边挖边前进（如视线前方 2-4 格的墙）
            client.options.forwardKey.setPressed(dist > 1.6);
            StateCollector.addBehaviorLog("寻路中挖掘障碍 " + obstacle.toShortString());
        }

        /** 绕行子状态：朝目标方向前进并侧移，有进展后回到正常导航 */
        private boolean tickBypass(MinecraftClient client, ClientPlayerEntity player) {
            stateTicks++;
            double dx = tx - player.getX();
            double dz = tz - player.getZ();
            double yaw = Math.toDegrees(Math.atan2(-dx, dz));
            player.setYaw((float) yaw);
            client.options.forwardKey.setPressed(true);
            if (strafeDir > 0) {
                client.options.leftKey.setPressed(true);
                client.options.rightKey.setPressed(false);
            } else {
                client.options.rightKey.setPressed(true);
                client.options.leftKey.setPressed(false);
            }

            // 每 1.5 秒评估：若已开始朝目标靠近，则结束绕行
            if (stateTicks % 30 == 0) {
                double moved = Math.abs(player.getX() - bypassStartX)
                        + Math.abs(player.getZ() - bypassStartZ);
                if (moved > 0.5) {
                    endBypass(client);
                    stuckCount = 0;
                    lastCheckX = player.getX();
                    lastCheckZ = player.getZ();
                    checkCounter = 0;
                    state = NAV;
                    return false;
                }
            }
            // 绕行 4.5 秒无进展：若前方可挖则硬挖，否则回到导航强制进入挖掘评估
            if (stateTicks > 90) {
                endBypass(client);
                BlockPos obstacle = findBlockInFront(player, tx, ty, tz);
                if (obstacle != null && isBreakable(player, obstacle)) {
                    startDigging(client, player, obstacle);
                } else {
                    stuckCount = 3; // 下一窗口立即进入挖掘评估
                    lastCheckX = player.getX();
                    lastCheckZ = player.getZ();
                    checkCounter = 0;
                    state = NAV;
                }
            }
            return false;
        }

        private void endBypass(MinecraftClient client) {
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
        }

        private void startBypass(MinecraftClient client, ClientPlayerEntity player) {
            strafeDir = (strafeDir == 0) ? 1 : (strafeDir == 1 ? -1 : 1);
            bypassStartX = player.getX();
            bypassStartZ = player.getZ();
            state = BYPASS;
            stateTicks = 0;
            client.options.attackKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            StateCollector.addBehaviorLog("寻路中尝试绕行");
        }

        private void applyMove(MinecraftClient client, ClientPlayerEntity player,
                               double yaw, double pitch, boolean jump, int sDir) {
            player.setYaw((float) yaw);
            player.setPitch((float) pitch);
            client.options.forwardKey.setPressed(true);
            if (jump) jumpTicks = 4;
            if (sDir > 0) {
                client.options.leftKey.setPressed(true);
                client.options.rightKey.setPressed(false);
            } else if (sDir < 0) {
                client.options.rightKey.setPressed(true);
                client.options.leftKey.setPressed(false);
            } else {
                client.options.leftKey.setPressed(false);
                client.options.rightKey.setPressed(false);
            }
        }
    }
    /**
     * 分层 BFS 路径规划：在玩家周围搜索一条可站立行走的路径（自动绕开障碍/台阶/坑）。
     * 节点 = 玩家脚部站立位置，允许跳上 1 格台阶；找不到时返回 null（调用方直线兜底）。
     */
    private static List<BlockPos> findPath(ClientPlayerEntity player, double tx, double ty, double tz) {
        World world = player.getWorld();
        BlockPos start = player.getBlockPos();
        BlockPos goal = new BlockPos((int) Math.floor(tx), (int) Math.floor(ty), (int) Math.floor(tz));
        int range = 48;
        if (Math.abs(goal.getX() - start.getX()) > range || Math.abs(goal.getZ() - start.getZ()) > range) {
            return null;
        }
        if (!isStandable(world, start)) return null;

        Deque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);
        int maxNodes = 24000;

        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};

        BlockPos found = null;
        while (!queue.isEmpty() && cameFrom.size() < maxNodes) {
            BlockPos cur = queue.poll();
            // 到达判定：水平 ≤1 格、垂直差 ≤2 格
            if (Math.abs(cur.getX() - goal.getX()) <= 1
                    && Math.abs(cur.getZ() - goal.getZ()) <= 1
                    && Math.abs(cur.getY() - goal.getY()) <= 2) {
                found = cur;
                break;
            }
            for (int d = 0; d < 4; d++) {
                int nx = cur.getX() + dx[d];
                int nz = cur.getZ() + dz[d];
                // 从同高度到 +1 层找站立点（走下不主动规划，玩家会自然下落）
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos cand = new BlockPos(nx, cur.getY() + dy, nz);
                    if (cand.getY() < world.getBottomY() + 1 || cand.getY() > world.getTopY()) continue;
                    if (isStandable(world, cand) && visited.add(cand)) {
                        queue.add(cand);
                        cameFrom.put(cand, cur);
                        break;
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
    private static boolean isStandable(World world, BlockPos pos) {
        if (!isSolid(world, pos) && !isSolid(world, pos.up())) {
            return isSolid(world, pos.down());
        }
        return false;
    }

    /** 是否实心方块（可站立/阻挡的完整固体，如石头/泥土/原木） */
    private static boolean isSolid(World world, BlockPos pos) {
        BlockState s = world.getBlockState(pos);
        if (s.isAir()) return false;
        return s.isSolidBlock(world, pos);
    }

    /** 从背包找一个能当垫脚的方块槽位（优先手持/快捷栏，其次背包并交换到快捷栏） */
    private static int findPillarSlot(MinecraftClient client, ClientPlayerEntity player) {
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

    private static boolean isPillarBlock(ItemStack stack) {
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
    private static boolean ensureBestTool(ClientPlayerEntity player, BlockState targetState) {
        PlayerInventory inv = player.getInventory();
        ItemStack held = player.getMainHandStack();
        float heldSpeed = held.getMiningSpeedMultiplier(targetState);
        int bestSlot = inv.selectedSlot;
        float bestSpeed = heldSpeed;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            float sp = s.getMiningSpeedMultiplier(targetState);
            if (sp > bestSpeed) {
                bestSpeed = sp;
                bestSlot = i;
            }
        }
        if (bestSlot != inv.selectedSlot) {
            inv.selectedSlot = bestSlot;
            StateCollector.addBehaviorLog("挖掘前自动切换到更合适的工具（槽位 " + bestSlot + "）");
            return true;
        }
        // 快捷栏没有明显更快的：从背包找
        if (bestSpeed <= 1.05f) {
            for (int i = 9; i < 36; i++) {
                ItemStack s = inv.getStack(i);
                if (s.isEmpty()) continue;
                float sp = s.getMiningSpeedMultiplier(targetState);
                if (sp > 1.05f) {
                    MinecraftClient.getInstance().interactionManager.pickFromInventory(i);
                    StateCollector.addBehaviorLog("挖掘前从背包切换更合适的工具");
                    return true;
                }
            }
        }
        return false;
    }

    /** 水平搜索最近的露天出口（同层可站立且头顶 20 格连续空气），找不到返回 null */
    private static BlockPos findOpenSkyExit(ClientPlayerEntity player, int range) {
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
    private static BlockPos findBlockInFront(ClientPlayerEntity player,
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
    private static BlockPos findObstacleInFront(ClientPlayerEntity player,
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

    private static boolean isBreakable(ClientPlayerEntity player, BlockPos pos) {
        BlockState state = player.getWorld().getBlockState(pos);
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        String idStr = id != null ? id.getPath() : "";
        for (String key : BREAKABLE_BLOCKS) {
            if (idStr.contains(key)) return true;
        }
        return false;
    }

    /** 玩家当前面朝的水平方向（yaw 约定：0=南/+Z，顺时针） */
    private static Direction facingDir(ClientPlayerEntity player) {
        int rot = Math.floorMod((int) Math.round(player.getYaw() / 90.0) + 2, 4);
        return Direction.fromHorizontal(rot);
    }

    /**
     * 链式挖掘标注：go_to_block 命中原木/矿物时，BFS 收集与之相连的相同 ID 方块，
     * 存入 {@link #markedBlocks}，之后 mc_digBlock 会连续挖完（树/整条矿脉）。
     * 排除树叶等不连续方块：只沿同 ID 传播。
     */
    private static void markConnectedIfNeeded(ClientPlayerEntity player, BlockPos origin) {
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

    // ======================== PlaceTask：放置（自动调整距离、视角、放置后校验） ========================

    /**
     * 放置方块。自动修正三个常见失败原因：
     * 1) 与瞄准方块水平距离 < 1.6 格时自动后退拉开距离（1.8 格以上）；
     * 2) 视角朝下超过 30° 时自动抬平；
     * 3) 后退不动（狭小空间/身后有墙）时改为跳跃放置（跳起时让出脚下空间再放）；
     * 放置后校验目标位置是否真的出现该方块，避免“假成功”让 AI 隔空操作。
     */
    private static class PlaceTask implements ActionTask {
        private static final int PREP = 0, BACKUP = 1, JUMP_ATTEMPT = 2, PLACE = 3, VERIFY = 4;
        private final long myVersion;
        private final long callId = currentCallId;
        private BlockPos anchor = null;      // 初始瞄准的方块（放置参照）
        private int state = PREP;
        private int stateTicks = 0;
        private int backupTicks = 0;
        private double backupStartX = 0, backupStartZ = 0;
        private BlockPos expectedPos = null; // 预期放置位置（用于校验）
        private Block expectedBlock = null;  // 预期放置的方块（手持 BlockItem 时）
        private String heldName = "";        // 手持物品名（结果报告用）

        PlaceTask() {
            this.myVersion = actionVersion;
        }

        @Override
        public boolean tick(MinecraftClient client, ClientPlayerEntity player) {
            if (actionVersion != myVersion || navCancelled) {
                releaseAllKeys(client);
                return true;
            }

            switch (state) {
                case PREP: {
                    HitResult hit = player.raycast(5.0, 0, false);
                    if (hit.getType() != HitResult.Type.BLOCK) {
                        sendResult("place", callId, false,
                                "未瞄准方块，请先看向要放置的位置（或用 mc_look/mc_turn 调整视角）");
                        return true;
                    }
                    anchor = ((BlockHitResult) hit).getBlockPos();
                    double dx = anchor.getX() + 0.5 - player.getX();
                    double dz = anchor.getZ() + 0.5 - player.getZ();
                    double anchorDist = Math.sqrt(dx * dx + dz * dz);
                    if (anchorDist < 1.6 || player.getPitch() < -30) {
                        state = BACKUP;
                        stateTicks = 0;
                        backupTicks = 0;
                        backupStartX = player.getX();
                        backupStartZ = player.getZ();
                        return false;
                    }
                    state = PLACE;
                    stateTicks = 0;
                    return false;
                }
                case BACKUP: {
                    stateTicks++;
                    double dx = anchor.getX() + 0.5 - player.getX();
                    double dz = anchor.getZ() + 0.5 - player.getZ();
                    double anchorDist = Math.sqrt(dx * dx + dz * dz);
                    // 朝向 anchor 后退，同时把视角抬平
                    player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                    player.setPitch(0);
                    if (anchorDist < 1.8) {
                        client.options.backKey.setPressed(true);
                        backupTicks++;
                    } else {
                        client.options.backKey.setPressed(false);
                        state = PLACE;
                        stateTicks = 0;
                        return false;
                    }
                    // 后退 1.5 秒：检查是否真的拉开了距离
                    if (backupTicks > 30) {
                        client.options.backKey.setPressed(false);
                        double moved = Math.abs(player.getX() - backupStartX)
                                + Math.abs(player.getZ() - backupStartZ);
                        if (moved < 0.3) {
                            // 后退不动（狭小空间/身后有墙）：尝试跳跃放置
                            StateCollector.addBehaviorLog("放置: 后退空间不足，尝试跳跃放置");
                            state = JUMP_ATTEMPT;
                        } else {
                            state = PLACE;
                        }
                        stateTicks = 0;
                        return false;
                    }
                    return false;
                }
                case JUMP_ATTEMPT: {
                    stateTicks++;
                    // 跳跃让出脚下空间，同时朝下 60° 瞄准，把方块放到脚前/脚下
                    client.options.jumpKey.setPressed(true);
                    double dx = anchor.getX() + 0.5 - player.getX();
                    double dz = anchor.getZ() + 0.5 - player.getZ();
                    player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                    player.setPitch(60f);
                    if (stateTicks >= 4 && stateTicks <= 12 && stateTicks % 3 == 0) {
                        HitResult hit = player.raycast(3.0, 0, false);
                        if (hit.getType() == HitResult.Type.BLOCK) {
                            ActionResult res = client.interactionManager.interactBlock(
                                    player, Hand.MAIN_HAND, (BlockHitResult) hit);
                            if (res.isAccepted()) {
                                recordExpected(player, (BlockHitResult) hit);
                            }
                        }
                    }
                    if (stateTicks > 14) {
                        client.options.jumpKey.setPressed(false);
                        if (expectedPos == null) {
                            // 跳跃放置也没成功（周围空间不足）
                            sendResult("place", callId, false,
                                    "放置失败：周围空间不足（后退不动、跳跃放置也未生效）。"
                                            + "建议先挖掉周围方块腾出空间，或换个位置再试。");
                            return true;
                        }
                        state = VERIFY;
                        stateTicks = 0;
                        return false;
                    }
                    return false;
                }
                case PLACE: {
                    releaseAllKeys(client);
                    HitResult hit = player.raycast(5.0, 0, false);
                    if (hit.getType() != HitResult.Type.BLOCK) {
                        sendResult("place", callId, false,
                                "未瞄准方块（调整距离后仍无目标），请换个位置再试");
                        return true;
                    }
                    ActionResult res = client.interactionManager.interactBlock(
                            player, Hand.MAIN_HAND, (BlockHitResult) hit);
                    heldName = player.getMainHandStack().isEmpty()
                            ? "空手" : player.getMainHandStack().getItem().getName().getString();
                    if (res.isAccepted()) {
                        recordExpected(player, (BlockHitResult) hit);
                        state = VERIFY;
                        stateTicks = 0;
                    } else {
                        sendResult("place", callId, false,
                                "放置失败，建议后退到 2-3 格外并保持水平视角再试（也可用 mc_look 先看向目标位置）");
                        return true;
                    }
                    return false;
                }
                default: { // VERIFY：放置后校验目标位置是否真的出现该方块
                    stateTicks++;
                    if (stateTicks < 6) return false;   // 等 0.3 秒让方块出现
                    boolean ok;
                    if (expectedBlock != null && expectedPos != null) {
                        ok = player.getWorld().getBlockState(expectedPos).getBlock() == expectedBlock;
                    } else {
                        ok = true;  // 非方块物品（如桶/火把等），以 accepted 为准
                    }
                    if (ok) {
                        sendResult("place", callId, true, "已放置 " + heldName);
                    } else {
                        sendResult("place", callId, false,
                                "放置未生效：目标位置 " + expectedPos.toShortString() + " 没有出现 "
                                        + heldName + "（可能是距离太近/空间不足/朝向不对）。"
                                        + "建议：后退 2-3 格保持水平视角，或先挖掉周围方块腾出空间，再重试。");
                    }
                    return true;
                }
            }
        }

        /** 记录预期放置位置与方块（用于 VERIFY 校验） */
        private void recordExpected(ClientPlayerEntity player, BlockHitResult hit) {
            expectedPos = hit.getBlockPos().offset(hit.getSide());
            ItemStack held = player.getMainHandStack();
            heldName = held.isEmpty() ? "空手" : held.getItem().getName().getString();
            if (held.getItem() instanceof BlockItem bi) {
                expectedBlock = bi.getBlock();
            } else {
                expectedBlock = null;
            }
        }
    }
    // ======================== DigBlockTask：持续挖掘 ========================

    private static class DigBlockTask implements ActionTask {
        private final long myVersion;
        private final long timeoutMs;
        private final long startTime;
        private final long callId = currentCallId;
        private BlockPos targetPos;
        private BlockState targetBlockState;  // 记录初始方块状态，用于校验是否挖对了
        private String targetBlockName;       // 目标方块名称（用于结果报告）
        private boolean initialized = false;

        DigBlockTask(double timeout) {
            this.timeoutMs = (long) (timeout * 1000);
            this.startTime = System.currentTimeMillis();
            this.myVersion = actionVersion;
        }

        @Override
        public boolean tick(MinecraftClient client, ClientPlayerEntity player) {
            if (actionVersion != myVersion || navCancelled) {
                client.options.attackKey.setPressed(false);
                return true;
            }

            if (!initialized) {
                HitResult hit = player.raycast(5.0, 0, false);
                if (hit.getType() != HitResult.Type.BLOCK) {
                    sendResult("dig_block", callId, false, "视线内没有方块");
                    return true;
                }
                targetPos = ((BlockHitResult) hit).getBlockPos();
                BlockState s = player.getWorld().getBlockState(targetPos);
                if (s.isAir()) {
                    sendResult("dig_block", callId, false, "目标方块是空气");
                    return true;
                }
                targetBlockState = s;
                targetBlockName = s.getBlock().getName().getString();
                System.out.println("[MC-Control] Digging " +
                        targetBlockName + " at " + targetPos.toShortString());
                initialized = true;
                // 自动切换更合适的工具（镐/斧/锹）
                ensureBestTool(player, s);
                client.options.attackKey.setPressed(true);
                return false;
            }

            // 检查目标位置是否已变成空气（已被破坏）
            BlockState currentState = player.getWorld().getBlockState(targetPos);
            if (currentState.isAir()) {
                client.options.attackKey.setPressed(false);
                // 延迟向前走捡掉落物（scheduler + client.execute，线程安全）
                scheduler.schedule(() ->
                        client.execute(() -> {
                            client.options.forwardKey.setPressed(true);
                            scheduler.schedule(() ->
                                    client.execute(() -> client.options.forwardKey.setPressed(false)),
                                    500, TimeUnit.MILLISECONDS);
                        }), 300, TimeUnit.MILLISECONDS);
                sendResult("dig_block", callId, true, "已破坏 " + targetBlockName);
                return true;
            }

            // 校验：目标位置的方块类型是否与初始一致
            // 如果方块类型变了（比如被其他因素替换），说明可能挖错了
            if (currentState.getBlock() != targetBlockState.getBlock()) {
                client.options.attackKey.setPressed(false);
                String actualName = currentState.getBlock().getName().getString();
                sendResult("dig_block", callId, false,
                    "目标方块类型已改变: 原目标=" + targetBlockName + ", 当前=" + actualName
                    + "。可能挖错了方块，请重新瞄准。");
                return true;
            }

            if (System.currentTimeMillis() - startTime > timeoutMs) {
                client.options.attackKey.setPressed(false);
                sendResult("dig_block", callId, false, "挖掘超时");
                return true;
            }
            return false;
        }
    }

    // ======================== ChainDigTask：连续挖掘标注方块（树/矿脉） ========================

    /**
     * 连续挖掘（树/矿脉）：把 go_to_block 标注的相连方块全部挖完。
     * - 目标不可达时自动走过去；路径被挡自动挖掉遮挡（准星指向目标，MC 天然先挖路径方块）；
     * - 目标太高（超出挖掘范围）时自动从背包找方块垫脚并跳上去；
     * - 挖完一个自动挖下一个，直到标注清空；超时/中断返回进度。
     */
    private static class ChainDigTask implements ActionTask {
        private static final int FIND = 0, WALK = 1, MINE = 2, PILLAR = 3;
        private final long myVersion;
        private final long callId = currentCallId;
        private final long startTime;
        private final long timeoutMs = 90000; // 90 秒
        private int state = FIND;
        private int stateTicks = 0;
        private BlockPos target = null;
        private BlockPos pillarPos = null;   // 已放置的垫脚方块
        private double lastCheckX = 0, lastCheckZ = 0;
        private int checkCounter = 0;
        private int stuckCount = 0;
        private int mined = 0;
        private int skipped = 0;
        private BlockPos lastUnbreakable = null; // 上次挖不动的方块（避免死循环）

        ChainDigTask() {
            this.myVersion = actionVersion;
            this.startTime = System.currentTimeMillis();
        }

        @Override
        public boolean tick(MinecraftClient client, ClientPlayerEntity player) {
            if (actionVersion != myVersion || navCancelled) {
                releaseAllKeys(client);
                return true;
            }
            if (markedBlocks.isEmpty()) {
                releaseAllKeys(client);
                sendResult("dig_block", callId, true,
                        "已连续挖完 " + mined + " 个标注方块"
                                + (skipped > 0 ? "（跳过 " + skipped + " 个无法挖掘的）" : ""));
                return true;
            }
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                releaseAllKeys(client);
                sendResult("dig_block", callId, false,
                        "连续挖掘超时：已挖 " + mined + " 个，剩余 " + markedBlocks.size()
                                + " 个标注方块（再次调用 mc_digBlock 可继续）");
                return true;
            }

            switch (state) {
                case FIND: {
                    target = pickReachable(player);
                    if (target != null) {
                        state = MINE;
                    } else {
                        target = pickNearest(player);
                        if (target == null) {
                            // 所有标注方块都挖不动（缺工具/全被基岩围住）
                            releaseAllKeys(client);
                            int remain = markedBlocks.size();
                            markedBlocks.clear();
                            sendResult("dig_block", callId, false,
                                    "剩余 " + remain + " 个标注方块无法挖掘（可能缺少合适工具或被不可挖掘方块围住），已停止");
                            return true;
                        }
                        state = WALK;
                    }
                    stateTicks = 0;
                    stuckCount = 0;
                    return false;
                }
                case MINE: {
                    stateTicks++;
                    // 自动切换更合适的工具（每 tick 检测开销极小，可应对捡到新工具）
                    ensureBestTool(player, player.getWorld().getBlockState(target));
                    if (player.getWorld().getBlockState(target).isAir()) {
                        client.options.attackKey.setPressed(false);
                        if (markedBlocks.remove(target)) {
                            mined++;
                            StateCollector.addBehaviorLog("连续挖掘: 挖完 " + target.toShortString()
                                    + "（" + mined + "/" + (mined + markedBlocks.size()) + "）");
                        } else {
                            StateCollector.addBehaviorLog("连续挖掘: 清除路径遮挡 " + target.toShortString());
                        }
                        state = FIND;
                        stateTicks = 0;
                        return false;
                    }
                    // 锁定视角 + 按住攻击（准星指向目标，若路径中间有方块会先挖掉它）
                    double dx = target.getX() + 0.5 - player.getX();
                    double dy = target.getY() + 0.5
                            - (player.getY() + player.getEyeHeight(player.getPose()));
                    double dz = target.getZ() + 0.5 - player.getZ();
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                    player.setPitch((float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
                    client.options.attackKey.setPressed(true);
                    if (dist > 4.6) {
                        // 走远了：停止挖掘，先靠近
                        client.options.attackKey.setPressed(false);
                        state = WALK;
                        stateTicks = 0;
                        return false;
                    }
                    // 挖了 12 秒仍未破坏（基岩/黑曜石/需要正确工具等）：跳过并记住
                    if (stateTicks > 240) {
                        client.options.attackKey.setPressed(false);
                        lastUnbreakable = target;
                        markedBlocks.remove(target);
                        skipped++;
                        StateCollector.addBehaviorLog("连续挖掘: 无法挖掘 " + target.toShortString() + "，跳过");
                        state = FIND;
                        stateTicks = 0;
                        return false;
                    }
                    return false;
                }
                case WALK: {
                    stateTicks++;
                    double dx = target.getX() + 0.5 - player.getX();
                    double dy = target.getY() + 0.5 - player.getY();
                    double dz = target.getZ() + 0.5 - player.getZ();
                    double hDist = Math.sqrt(dx * dx + dz * dz);
                    double eyeDy = target.getY() + 0.5
                            - (player.getY() + player.getEyeHeight(player.getPose()));
                    player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));

                    // 目标太高且已靠近：垫脚
                    if (eyeDy > 4.5 && hDist < 3.0) {
                        releaseAllKeys(client);
                        state = PILLAR;
                        stateTicks = 0;
                        pillarPos = null;
                        return false;
                    }
                    // 足够近且手够得着（含目标在脚下深处：朝下挖穿即可）
                    if (hDist <= 4.5 && Math.abs(eyeDy) <= 4.5) {
                        releaseAllKeys(client);
                        state = MINE;
                        stateTicks = 0;
                        return false;
                    }
                    // 朝目标走，周期性小跳（跨台阶/栅栏）
                    client.options.forwardKey.setPressed(true);
                    if (stateTicks % 20 < 4) {
                        client.options.jumpKey.setPressed(true);
                    } else {
                        client.options.jumpKey.setPressed(false);
                    }

                    // 卡住检测（每 10 tick 结算）
                    checkCounter++;
                    if (checkCounter >= 10) {
                        double moved = Math.abs(player.getX() - lastCheckX)
                                + Math.abs(player.getZ() - lastCheckZ);
                        if (moved < 0.15) {
                            stuckCount++;
                        } else {
                            stuckCount = 0;
                        }
                        lastCheckX = player.getX();
                        lastCheckZ = player.getZ();
                        checkCounter = 0;
                    }
                    if (stuckCount >= 2) {
                        releaseAllKeys(client);
                        // 优先挖掉路径遮挡
                        BlockPos ob = findBlockInFront(player,
                                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
                        if (ob != null && isBreakable(player, ob)) {
                            if (ob.equals(lastUnbreakable)) {
                                // 这个遮挡上次挖不动：跳过当前目标换下一个
                                markedBlocks.remove(target);
                                skipped++;
                                StateCollector.addBehaviorLog("连续挖掘: 路径遮挡无法挖掘，跳过目标");
                                state = FIND;
                                stateTicks = 0;
                                stuckCount = 0;
                                return false;
                            }
                            target = ob;
                            state = MINE;
                            stateTicks = 0;
                            stuckCount = 0;
                            return false;
                        }
                        // 不可挖：跳过该目标换下一个
                        markedBlocks.remove(target);
                        skipped++;
                        StateCollector.addBehaviorLog("连续挖掘: 目标被不可挖掘方块阻挡，跳过");
                        state = FIND;
                        stateTicks = 0;
                        stuckCount = 0;
                        return false;
                    }
                    // 走 30 秒仍未接近：换下一个目标
                    if (stateTicks > 600) {
                        releaseAllKeys(client);
                        markedBlocks.remove(target);
                        skipped++;
                        StateCollector.addBehaviorLog("连续挖掘: 目标无法到达，跳过");
                        state = FIND;
                        stateTicks = 0;
                        stuckCount = 0;
                        return false;
                    }
                    return false;
                }
                default: { // PILLAR：垫脚
                    stateTicks++;
                    if (pillarPos != null) {
                        // 已放置：跳上去（持续前进+跳跃直到站到垫脚方块上方）
                        double dx = target.getX() + 0.5 - player.getX();
                        double dz = target.getZ() + 0.5 - player.getZ();
                        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                        client.options.forwardKey.setPressed(true);
                        client.options.jumpKey.setPressed(true);
                        if (player.getY() > pillarPos.getY() + 0.9) {
                            releaseAllKeys(client);
                            pillarPos = null;
                            state = WALK;
                            stateTicks = 0;
                            stuckCount = 0;
                            return false;
                        }
                        if (stateTicks > 40) { // 2 秒没站上去
                            releaseAllKeys(client);
                            pillarPos = null;
                            markedBlocks.remove(target);
                            skipped++;
                            StateCollector.addBehaviorLog("连续挖掘: 垫脚失败，跳过高处目标");
                            state = FIND;
                            stateTicks = 0;
                            return false;
                        }
                        return false;
                    }
                    // 找垫脚方块
                    int slot = findPillarSlot(client, player);
                    if (slot < 0) {
                        markedBlocks.remove(target);
                        skipped++;
                        StateCollector.addBehaviorLog("连续挖掘: 背包没有可垫脚的方块，跳过高处目标");
                        state = FIND;
                        stateTicks = 0;
                        return false;
                    }
                    player.getInventory().selectedSlot = slot;
                    // 放置到玩家前方一格（地面上的空气位置）
                    double dx = target.getX() + 0.5 - player.getX();
                    double dz = target.getZ() + 0.5 - player.getZ();
                    player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                    BlockPos front = player.getBlockPos().offset(facingDir(player));
                    BlockPos ground = front.down();
                    World world = player.getWorld();
                    if (!world.getBlockState(front).isAir()) {
                        // 前方已有方块：直接跳上去
                        pillarPos = front;
                        stateTicks = 0;
                        return false;
                    }
                    if (!world.getBlockState(ground).isAir()) {
                        // 点击地面顶面，新方块会出现在 front
                        Vec3d hitPos = new Vec3d(ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5);
                        BlockHitResult bhr = new BlockHitResult(hitPos, Direction.UP, ground, false);
                        ActionResult res = client.interactionManager
                                .interactBlock(player, Hand.MAIN_HAND, bhr);
                        if (res.isAccepted()) {
                            pillarPos = front;
                            stateTicks = 0;
                            StateCollector.addBehaviorLog("连续挖掘: 放置垫脚方块 " + front.toShortString());
                            return false;
                        }
                    }
                    // 放置失败（悬崖/低洼等）：跳过该目标
                    markedBlocks.remove(target);
                    skipped++;
                    StateCollector.addBehaviorLog("连续挖掘: 垫脚位置不合适，跳过高处目标");
                    state = FIND;
                    stateTicks = 0;
                    return false;
                }
            }
        }

        /** 从标注集合中选一个当前伸手够得着的方块（排除上次挖不动的） */
        private BlockPos pickReachable(ClientPlayerEntity player) {
            for (BlockPos p : markedBlocks) {
                if (p.equals(lastUnbreakable)) continue;
                double hDist = Math.sqrt(
                        (p.getX() + 0.5 - player.getX()) * (p.getX() + 0.5 - player.getX())
                                + (p.getZ() + 0.5 - player.getZ()) * (p.getZ() + 0.5 - player.getZ()));
                double eyeDy = p.getY() + 0.5 - (player.getY() + player.getEyeHeight(player.getPose()));
                if (hDist <= 4.5 && Math.abs(eyeDy) <= 4.5) {
                    return p;
                }
            }
            return null;
        }

        /** 从标注集合中选最近的一个（排除上次挖不动的） */
        private BlockPos pickNearest(ClientPlayerEntity player) {
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (BlockPos p : markedBlocks) {
                if (p.equals(lastUnbreakable)) continue;
                double d = player.squaredDistanceTo(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            return best;
        }

        /** 从背包找一个能当垫脚的方块槽位（优先手持/快捷栏，其次背包并交换到快捷栏） */
    }

    // ======================== DigDownTask：安全向下挖 ========================

    private static class DigDownTask implements ActionTask {
        private final int distance;
        private final long myVersion;
        private final long callId = currentCallId;
        private int current = 0;     // 已完成的格数
        private int phase = 0;       // 0=检查并开始挖, 1=等待挖完, 2=潜行下移
        private int phaseTicks = 0;
        private BlockPos digPos;

        DigDownTask(int distance) {
            this.distance = distance;
            this.myVersion = actionVersion;
        }

        @Override
        public boolean tick(MinecraftClient client, ClientPlayerEntity player) {
            if (actionVersion != myVersion || navCancelled) {
                client.options.attackKey.setPressed(false);
                client.options.sneakKey.setPressed(false);
                client.options.forwardKey.setPressed(false);
                return true;
            }
            if (current >= distance) {
                sendResult("dig_down", callId, true, "已向下挖掘 " + distance + " 格");
                return true;
            }

            switch (phase) {
                case 0: { // 检查安全 + 开始挖
                    BlockPos pos = player.getBlockPos();
                    digPos = pos.down(current + 1);
                    BlockState state = player.getWorld().getBlockState(digPos);
                    String name = state.getBlock().getName().getString().toLowerCase();
                    if (name.contains("lava") || name.contains("water")) {
                        sendResult("dig_down", callId, false, "遇到危险: " + name);
                        return true;
                    }
                    // 向下看并按住挖掘键（自动切工具）
                    ensureBestTool(player, state);
                    player.setPitch(90f);
                    client.options.attackKey.setPressed(true);
                    phaseTicks = 0;
                    phase = 1;
                    return false;
                }
                case 1: { // 等待方块破坏
                    phaseTicks++;
                    if (player.getWorld().getBlockState(digPos).isAir()) {
                        client.options.attackKey.setPressed(false);
                        // 潜行下移，防止直接坠落
                        client.options.sneakKey.setPressed(true);
                        client.options.forwardKey.setPressed(true);
                        phaseTicks = 0;
                        phase = 2;
                    } else if (phaseTicks > 100) { // 5 秒超时
                        client.options.attackKey.setPressed(false);
                        sendResult("dig_down", callId, false, "挖掘超时");
                        return true;
                    }
                    return false;
                }
                case 2: { // 下移
                    phaseTicks++;
                    if (phaseTicks > 8) { // ~0.4 秒
                        client.options.forwardKey.setPressed(false);
                        client.options.sneakKey.setPressed(false);
                        current++;
                        phase = 0;
                    }
                    return false;
                }
            }
            return false;
        }
    }

    // ======================== GoToSurfaceTask：向上挖回地面 ========================

    /**
     * 回到地面：不再死磕直线向上挖。
     * 1) 优先水平寻找最近的露天出口（半径 20 格内头顶通天的位置），走过去再向上挖；
     * 2) 没有出口时向上挖，但挖到不可挖掘方块（基岩/黑曜石）3 秒挖不动时自动水平换位；
     * 3) 水平移动被墙挡住时自动挖掉阻挡方块。
     */
    private static class GoToSurfaceTask implements ActionTask {
        private final long myVersion;
        private final long callId = currentCallId;
        private int phaseTicks = 0;
        private int recheckTicks = 0;
        private BlockPos exitPos = null;      // 找到的露天出口（水平移动目标）
        private boolean digging = false;      // 是否正在挖路径障碍
        private BlockPos digTarget = null;
        private int digStuckTicks = 0;
        private double lastCheckX = 0, lastCheckZ = 0;
        private int checkCounter = 0;
        private int stuckCount = 0;
        private int moveTicks = 0;            // 挖不动时水平换位的剩余 tick
        private float moveYaw = 0;            // 水平换位的方向
        private BlockPos lastDigPos = null;   // 最近一次尝试挖的头顶方块（检测挖不动）

        GoToSurfaceTask() {
            this.myVersion = actionVersion;
        }

        @Override
        public boolean tick(MinecraftClient client, ClientPlayerEntity player) {
            if (actionVersion != myVersion || navCancelled) {
                releaseAllKeys(client);
                return true;
            }

            BlockPos pos = player.getBlockPos();
            // 已露天（头顶连续 air）则完成
            if (isOpenSky(player, pos)) {
                releaseAllKeys(client);
                sendResult("go_to_surface", callId, true, "已回到地面");
                return true;
            }

            // 挖不动时的水平换位（基岩等）: 朝随机方向走 3 秒
            if (moveTicks > 0) {
                moveTicks--;
                player.setYaw(moveYaw);
                player.setPitch(0);
                client.options.forwardKey.setPressed(true);
                client.options.attackKey.setPressed(false);
                phaseTicks++;
                return false;
            }

            // 定期重新搜索露天出口（每 2 秒）
            recheckTicks--;
            if (exitPos == null || recheckTicks <= 0) {
                exitPos = findOpenSkyExit(player, 20);
                recheckTicks = 40;
                if (exitPos != null) {
                    StateCollector.addBehaviorLog("找到露天出口 " + exitPos.toShortString() + "，先走过去");
                }
            }

            // ---- 有出口：水平移动过去（走通道/挖穿薄墙）----
            if (exitPos != null) {
                double dx = exitPos.getX() + 0.5 - player.getX();
                double dz = exitPos.getZ() + 0.5 - player.getZ();
                double hDist = Math.sqrt(dx * dx + dz * dz);
                if (hDist < 2.5) {
                    // 到出口下方：转向上方挖
                    exitPos = null;
                    phaseTicks = 0;
                } else {
                    // 挖掘路径障碍中：锁定视角到障碍直到挖穿
                    if (digging && digTarget != null) {
                        double ox = digTarget.getX() + 0.5 - player.getX();
                        double oy = digTarget.getY() + 0.5
                                - (player.getY() + player.getEyeHeight(player.getPose()));
                        double oz = digTarget.getZ() + 0.5 - player.getZ();
                        player.setYaw((float) Math.toDegrees(Math.atan2(-ox, oz)));
                        player.setPitch((float) Math.toDegrees(-Math.atan2(oy, Math.sqrt(ox * ox + oz * oz))));
                        client.options.forwardKey.setPressed(true);
                        client.options.attackKey.setPressed(true);
                        digStuckTicks++;
                        if (player.getWorld().getBlockState(digTarget).isAir() || digStuckTicks > 100) {
                            digging = false;
                            digTarget = null;
                            client.options.attackKey.setPressed(false);
                            digStuckTicks = 0;
                        }
                        phaseTicks++;
                        return false;
                    }
                    // 正常朝出口走
                    player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                    player.setPitch(0);
                    client.options.forwardKey.setPressed(true);
                    client.options.attackKey.setPressed(false);
                    if (phaseTicks % 20 < 4) {
                        client.options.jumpKey.setPressed(true);
                    } else {
                        client.options.jumpKey.setPressed(false);
                    }
                    // 卡住检测 → 挖前方障碍
                    checkCounter++;
                    if (checkCounter >= 10) {
                        double moved = Math.abs(player.getX() - lastCheckX)
                                + Math.abs(player.getZ() - lastCheckZ);
                        if (moved < 0.15) {
                            stuckCount++;
                        } else {
                            stuckCount = 0;
                        }
                        lastCheckX = player.getX();
                        lastCheckZ = player.getZ();
                        checkCounter = 0;
                    }
                    if (stuckCount >= 2) {
                        stuckCount = 0;
                        BlockPos ob = findBlockInFront(player,
                                exitPos.getX() + 0.5, exitPos.getY() + 0.5, exitPos.getZ() + 0.5);
                        if (ob != null && isBreakable(player, ob)) {
                            digging = true;
                            digTarget = ob;
                            digStuckTicks = 0;
                        } else {
                            // 前方不可挖：放弃这个出口
                            exitPos = null;
                            client.options.jumpKey.setPressed(false);
                        }
                    }
                    phaseTicks++;
                    return false;
                }
            }

            // ---- 向上挖 ----
            phaseTicks++;
            BlockPos above = pos.up();
            BlockState aboveState = player.getWorld().getBlockState(above);
            player.setPitch(-90f);

            if (aboveState.isAir()) {
                // 头顶是空气，跳跃上去
                client.options.attackKey.setPressed(false);
                client.options.jumpKey.setPressed(true);
                client.options.forwardKey.setPressed(true);
                if (phaseTicks > 10) {
                    client.options.jumpKey.setPressed(false);
                    client.options.forwardKey.setPressed(false);
                    phaseTicks = 0;
                }
            } else {
                // 头顶有方块，挖掉（自动切工具）
                ensureBestTool(player, aboveState);
                client.options.attackKey.setPressed(true);
                // 检测是否挖不动（基岩/黑曜石等）
                if (above.equals(lastDigPos)) {
                    digStuckTicks++;
                } else {
                    lastDigPos = above;
                    digStuckTicks = 0;
                }
                if (digStuckTicks > 60) {
                    // 3 秒挖不动：水平随机移动换位置
                    client.options.attackKey.setPressed(false);
                    digStuckTicks = 0;
                    lastDigPos = null;
                    moveTicks = 60;
                    moveYaw = player.getYaw() + (float) (Math.random() * 240 - 120);
                    StateCollector.addBehaviorLog("头顶方块挖不动，水平移动换位置");
                }
                if (player.getWorld().getBlockState(above).isAir()) {
                    client.options.attackKey.setPressed(false);
                    client.options.jumpKey.setPressed(true);
                    if (phaseTicks > 5) {
                        client.options.jumpKey.setPressed(false);
                        phaseTicks = 0;
                    }
                }
            }
            return false;
        }

        /** 头顶向上 20 格连续 air 视为露天 */
        private boolean isOpenSky(ClientPlayerEntity player, BlockPos pos) {
            for (int y = pos.getY() + 1; y < pos.getY() + 20 && y < 320; y++) {
                if (!player.getWorld().getBlockState(new BlockPos(pos.getX(), y, pos.getZ())).isAir()) {
                    return false;
                }
            }
            return true;
        }
    }
    // ======================== FurnaceSmeltTask：熔炉/高炉/烟熏炉自动烧炼 ========================

    /**
     * 自动烧炼：解决 AI 无法使用熔炉的问题。
     * 1) 靠近附近的熔炉并右键打开界面；
     * 2) 用 shift-click 把背包中的原料批量放入输入槽(0)；
     * 3) 放入燃料到燃料槽(1)（自动检测背包燃料，耗尽自动补充）；
     * 4) 等待烧制完成，shift-click 取出产物，关闭界面并报告结果。
     */
    private static class FurnaceSmeltTask implements ActionTask {
        private static final int FIND = 0, OPEN = 1, PUT_INPUT = 2, PUT_FUEL = 3,
                SMELT = 4, TAKE = 5, DONE = 6;
        private final long myVersion;
        private final long callId = currentCallId;
        private final RecipeLookup.RecipeInfo recipe;
        private final BlockPos furnacePos;
        private final String outputId;
        private final int needInput;      // 需要的原料总数
        private final String inputDisplay;
        private final String furnaceDisplay;
        private int phase = FIND;
        private int phaseTicks = 0;
        private int slotCooldown = 0;
        private int putCount = 0;         // 连续点击计数（防止卡死）
        private int inputInvSlot = -1;    // 原料在背包的槽位
        private int fuelInvSlot = -1;     // 燃料在背包的槽位

        FurnaceSmeltTask(RecipeLookup.RecipeInfo recipe, int count, BlockPos furnacePos) {
            this.myVersion = actionVersion;
            this.recipe = recipe;
            this.furnacePos = furnacePos;
            this.outputId = recipe.outputItemId.contains(":")
                    ? recipe.outputItemId.substring(recipe.outputItemId.indexOf(':') + 1)
                    : recipe.outputItemId;
            List<RecipeLookup.RequiredIngredient> req = RecipeLookup.getRequiredIngredients(recipe);
            int per = 1;
            for (RecipeLookup.RequiredIngredient r : req) per = Math.max(per, r.count);
            this.needInput = Math.max(1, per * count);
            this.inputDisplay = req.isEmpty() ? "原料" : req.get(0).displayName;
            if ("blasting".equals(recipe.type)) this.furnaceDisplay = "高炉";
            else if ("smoking".equals(recipe.type)) this.furnaceDisplay = "烟熏炉";
            else this.furnaceDisplay = "熔炉";
        }

        @Override
        public boolean tick(MinecraftClient client, ClientPlayerEntity player) {
            if (actionVersion != myVersion || navCancelled) {
                releaseAllKeys(client);
                player.closeHandledScreen();
                return true;
            }
            if (slotCooldown > 0) slotCooldown--;

            switch (phase) {
                case FIND: {   // 靠近熔炉并打开界面
                    phaseTicks++;
                    double dx = furnacePos.getX() + 0.5 - player.getX();
                    double dz = furnacePos.getZ() + 0.5 - player.getZ();
                    double hDist = Math.sqrt(dx * dx + dz * dz);
                    player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                    player.setPitch(0);
                    if (hDist > 3.2) {
                        client.options.forwardKey.setPressed(true);
                        if (phaseTicks > 200) {   // 10 秒走不到：放弃
                            client.options.forwardKey.setPressed(false);
                            sendResult("craft", callId, false,
                                    "无法靠近" + furnaceDisplay + "（" + furnacePos.toShortString()
                                            + "），请先走到炉子旁边再调用 mc_craft");
                            return true;
                        }
                        return false;
                    }
                    client.options.forwardKey.setPressed(false);
                    if (player.currentScreenHandler instanceof AbstractFurnaceScreenHandler) {
                        phase = PUT_INPUT;
                        phaseTicks = 0;
                        return false;
                    }
                    Vec3d hitPos = new Vec3d(furnacePos.getX() + 0.5,
                            furnacePos.getY() + 0.5, furnacePos.getZ() + 0.5);
                    BlockHitResult bhr = new BlockHitResult(hitPos, Direction.UP, furnacePos, false);
                    client.interactionManager.interactBlock(player, Hand.MAIN_HAND, bhr);
                    phase = OPEN;
                    phaseTicks = 0;
                    return false;
                }
                case OPEN: {   // 等待界面打开
                    phaseTicks++;
                    if (player.currentScreenHandler instanceof AbstractFurnaceScreenHandler) {
                        phase = PUT_INPUT;
                        phaseTicks = 0;
                        return false;
                    }
                    if (phaseTicks > 40) {
                        sendResult("craft", callId, false, "打开" + furnaceDisplay + "界面超时，请检查炉子位置");
                        return true;
                    }
                    return false;
                }
                case PUT_INPUT: {
                    phaseTicks++;
                    if (slotCooldown > 0) return false;
                    if (!(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler)) {
                        phase = FIND;   // 界面丢了：重新打开
                        phaseTicks = 0;
                        return false;
                    }
                    int cur = handler.getSlot(0).getStack().getCount();
                    if (cur >= needInput) {
                        phase = PUT_FUEL;
                        phaseTicks = 0;
                        putCount = 0;
                        return false;
                    }
                    if (inputInvSlot < 0) {
                        inputInvSlot = findSmeltInputSlot(player);
                        if (inputInvSlot < 0) {
                            sendResult("craft", callId, false,
                                    "背包中没有烧炼原料: " + inputDisplay + "（需要 " + needInput + " 个）");
                            player.closeHandledScreen();
                            return true;
                        }
                    }
                    // shift-click 把原料批量放入输入槽
                    client.interactionManager.clickSlot(handler.syncId,
                            screenSlotOf(inputInvSlot), 0, SlotActionType.QUICK_MOVE, player);
                    slotCooldown = 3;
                    if (++putCount >= 12) {
                        sendResult("craft", callId, false,
                                "原料无法放入" + furnaceDisplay + "，请检查原料是否正确（" + inputDisplay + "）");
                        player.closeHandledScreen();
                        return true;
                    }
                    return false;
                }
                case PUT_FUEL: {
                    phaseTicks++;
                    if (slotCooldown > 0) return false;
                    if (!(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler)) {
                        phase = FIND;
                        phaseTicks = 0;
                        return false;
                    }
                    // 燃料槽已有燃料或正在燃烧：开始等待
                    if (!handler.getSlot(1).getStack().isEmpty() || handler.isBurning()) {
                        phase = SMELT;
                        phaseTicks = 0;
                        putCount = 0;
                        return false;
                    }
                    if (fuelInvSlot < 0) {
                        fuelInvSlot = findFuelSlot(player);
                        if (fuelInvSlot < 0) {
                            sendResult("craft", callId, false,
                                    "背包中没有燃料（煤炭/木炭/木板/原木等）。请先收集燃料再烧炼。");
                            player.closeHandledScreen();
                            return true;
                        }
                    }
                    client.interactionManager.clickSlot(handler.syncId,
                            screenSlotOf(fuelInvSlot), 0, SlotActionType.QUICK_MOVE, player);
                    slotCooldown = 3;
                    if (++putCount >= 12) {
                        sendResult("craft", callId, false, "燃料无法放入" + furnaceDisplay);
                        player.closeHandledScreen();
                        return true;
                    }
                    return false;
                }
                case SMELT: {   // 等待烧制完成
                    phaseTicks++;
                    if (!(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler)) {
                        sendResult("craft", callId, false, furnaceDisplay + "界面意外关闭");
                        return true;
                    }
                    // 输出槽有产物：取出
                    if (!handler.getSlot(2).getStack().isEmpty()) {
                        phase = TAKE;
                        phaseTicks = 0;
                        return false;
                    }
                    // 燃料耗尽且还没有产物：自动补燃料
                    if (handler.getSlot(1).getStack().isEmpty()
                            && !handler.getSlot(0).getStack().isEmpty()
                            && phaseTicks % 60 == 0) {
                        fuelInvSlot = findFuelSlot(player);
                        if (fuelInvSlot >= 0) {
                            client.interactionManager.clickSlot(handler.syncId,
                                    screenSlotOf(fuelInvSlot), 0, SlotActionType.QUICK_MOVE, player);
                            slotCooldown = 3;
                            StateCollector.addBehaviorLog("熔炉燃料耗尽，自动补充");
                        } else {
                            sendResult("craft", callId, false,
                                    "燃料烧完了且背包没有燃料，已停止烧炼");
                            player.closeHandledScreen();
                            return true;
                        }
                    }
                    // 超时 120 秒
                    if (phaseTicks > 2400) {
                        sendResult("craft", callId, false, "烧炼超时，请检查" + furnaceDisplay + "是否有燃料");
                        player.closeHandledScreen();
                        return true;
                    }
                    return false;
                }
                case TAKE: {
                    phaseTicks++;
                    if (slotCooldown > 0) return false;
                    if (!(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler)) {
                        phase = FIND;
                        phaseTicks = 0;
                        return false;
                    }
                    client.interactionManager.clickSlot(handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, player);
                    slotCooldown = 2;
                    phase = DONE;
                    phaseTicks = 0;
                    return false;
                }
                default: { // DONE
                    phaseTicks++;
                    if (phaseTicks > 6) {
                        player.closeHandledScreen();
                        int have = countItemByIngredient(player.getInventory(), Set.of(outputId));
                        sendResult("craft", callId, true,
                                "烧炼成功: " + outputId + "（背包现有 " + have + " 个）");
                        return true;
                    }
                    return false;
                }
            }
        }

        /** 从背包找烧炼原料槽位（匹配配方输入） */
        private int findSmeltInputSlot(ClientPlayerEntity player) {
            List<RecipeLookup.RequiredIngredient> req = RecipeLookup.getRequiredIngredients(recipe);
            if (req.isEmpty()) return -1;
            Set<String> matchIds = req.get(0).matchIds;
            PlayerInventory inv = player.getInventory();
            for (int i = 0; i < 36; i++) {
                ItemStack s = inv.getStack(i);
                if (s.isEmpty()) continue;
                Identifier id = Registries.ITEM.getId(s.getItem());
                if (id != null && matchIds.contains(id.getPath())) return i;
            }
            return -1;
        }

        /** 从背包找燃料槽位（煤炭/木炭/木板/原木等，按原版燃料时间判断） */
        private int findFuelSlot(ClientPlayerEntity player) {
            PlayerInventory inv = player.getInventory();
            for (int i = 0; i < 36; i++) {
                ItemStack s = inv.getStack(i);
                if (s.isEmpty()) continue;
                // 1.20.1: 检查常见燃料
                String name = Registries.ITEM.getId(s.getItem()).getPath();
                if (name.contains("coal") || name.contains("charcoal") || 
                    name.contains("plank") || name.contains("log") || 
                    name.contains("wood") || name.contains("stick") ||
                    name.contains("lava_bucket") || name.contains("blaze_rod")) return i;
            }
            return -1;
        }

        /** 背包槽位 → 熔炉界面屏幕槽位 */
        private int screenSlotOf(int invSlot) {
            if (invSlot < 9) return invSlot + 30;   // 快捷栏 0-8 → 30-38
            return invSlot - 9 + 3;                 // 背包 9-35 → 3-29
        }
    }

    // ======================== CraftTask：服务端同步合成 ========================

    /**
     * 通过 ScreenHandler 进行服务端同步合成（借鉴 Altoclef 的 SlotHandler + CraftTask 设计）。
     * <p>
     * 改进点：
     * <ul>
     *   <li>光标管理：取出产物前确保光标空闲或物品可叠加</li>
     *   <li>点击节流：每次槽位操作间隔 ≥2 tick，防止服务器丢弃过快点击</li>
     *   <li>智能取出：需要全部时用 shift-click(QUICK_MOVE)，部分时用普通点击(PICKUP)</li>
     *   <li>多次合成：count>1 时循环"填充→取出"直到完成或材料耗尽</li>
     * </ul>
     */
    private static class CraftTask implements ActionTask {
        private final RecipeLookup.RecipeInfo recipe;
        private final int count;
        private final long myVersion;
        private final BlockPos tablePos;     // null = 背包合成, 非null = 工作台合成
        private final int beforeCount;       // 合成前背包中该物品的数量
        private final String outputId;       // 产物物品 ID（短名）
        private final long callId = currentCallId;

        // 状态机阶段
        // 0=打开界面, 1=等待界面打开, 2=填充网格, 21=手动摆放材料,
        // 3=等待填充完成, 4=确保光标空闲, 5=取出产物, 6=等待取出完成,
        // 7=检查是否需要继续合成(循环), 8=关闭并报告
        private int phase = 0;
        private int phaseTicks = 0;
        private int craftsDone = 0;           // 已完成的合成次数
        private int slotCooldown = 0;         // 槽位操作冷却（tick）

        // 手动填充模式：clickRecipe 依赖配方书解锁，服务端对未解锁配方静默忽略。
        // 检测到无产物时回退为逐格 clickSlot 手动摆放，任何配方都可靠。
        private boolean manualMode = false;
        private final List<Integer> manualCells = new ArrayList<>();          // 网格槽位（屏幕坐标）
        private final List<Ingredient> manualIngredients = new ArrayList<>(); // 对应材料
        private int fillStep = 0;          // 手动填充进度
        private int pendingGridSlot = -1;  // 光标已拿起材料，等待放入的网格槽位

        CraftTask(RecipeLookup.RecipeInfo recipe, int count, BlockPos tablePos,
                  int beforeCount, String outputId) {
            this.recipe = recipe;
            this.count = count;
            this.tablePos = tablePos;
            this.beforeCount = beforeCount;
            this.outputId = outputId;
            this.myVersion = actionVersion;
        }

        @Override
        public boolean tick(MinecraftClient client, ClientPlayerEntity player) {
            if (actionVersion != myVersion || navCancelled) {
                player.closeHandledScreen();
                return true;
            }

            // 槽位操作冷却
            if (slotCooldown > 0) {
                slotCooldown--;
                return false;
            }

            switch (phase) {
                case 0: { // 打开界面
                    if (tablePos != null) {
                        // 转向工作台并右键交互
                        double dx = tablePos.getX() + 0.5 - player.getX();
                        double dy = tablePos.getY() + 0.5
                                - (player.getY() + player.getEyeHeight(player.getPose()));
                        double dz = tablePos.getZ() + 0.5 - player.getZ();
                        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                        player.setPitch((float) Math.toDegrees(
                                -Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));

                        HitResult hit = player.raycast(5.0, 0, false);
                        if (hit.getType() == HitResult.Type.BLOCK) {
                            client.interactionManager.interactBlock(
                                    player, Hand.MAIN_HAND, (BlockHitResult) hit);
                        }
                        phase = 1;
                        phaseTicks = 0;
                    } else {
                        // 背包合成：直接打开背包界面
                        client.setScreen(new InventoryScreen(player));
                        phase = 2;
                        phaseTicks = 0;
                    }
                    slotCooldown = 2;
                    return false;
                }
                case 1: { // 等待工作台界面打开
                    phaseTicks++;
                    if (client.currentScreen != null) {
                        phase = 2;
                        phaseTicks = 0;
                    } else if (phaseTicks > 20) { // 1 秒超时
                        sendResult("craft", callId, false, "无法打开工作台界面，可能距离太远");
                        return true;
                    }
                    return false;
                }
                case 2: { // 填充合成网格（clickRecipe 或手动摆放）
                    if (manualMode) {
                        phase = 21;
                        phaseTicks = 0;
                        fillStep = 0;
                        pendingGridSlot = -1;
                        slotCooldown = 1;
                        return false;
                    }
                    try {
                        int syncId = player.currentScreenHandler.syncId;
                        // craftAll=false: 只合成一次，不把所有材料都消耗掉
                        client.interactionManager.clickRecipe(syncId, recipe.recipe, false);
                    } catch (Exception e) {
                        player.closeHandledScreen();
                        sendResult("craft", callId, false, "填充合成网格失败: " + e.getMessage());
                        return true;
                    }
                    phase = 3;
                    phaseTicks = 0;
                    slotCooldown = 3; // 等待服务端处理
                    return false;
                }
                case 21: { // 手动摆放材料（每个 tick 最多一次点击，避免服务端丢包）
                    if (manualCells.isEmpty()) {
                        if (!initManualPlacements()) {
                            player.closeHandledScreen();
                            sendResult("craft", callId, false, "该配方无法自动摆放，请手动合成");
                            return true;
                        }
                    }
                    // 第二步：把光标中的材料放入网格
                    if (pendingGridSlot >= 0) {
                        client.interactionManager.clickSlot(
                                player.currentScreenHandler.syncId, pendingGridSlot, 0,
                                SlotActionType.PICKUP, player);
                        pendingGridSlot = -1;
                        fillStep++;
                        slotCooldown = 1;
                        return false;
                    }
                    // 全部摆放完成，等待服务端生成产物
                    if (fillStep >= manualCells.size()) {
                        phase = 3;
                        phaseTicks = 0;
                        slotCooldown = 3;
                        return false;
                    }
                    int gridSlot = manualCells.get(fillStep);
                    Ingredient ing = manualIngredients.get(fillStep);
                    // 该格已有匹配材料（上一轮残留），跳过
                    ItemStack gridStack = player.currentScreenHandler.getSlot(gridSlot).getStack();
                    if (!gridStack.isEmpty() && ing.test(gridStack)) {
                        fillStep++;
                        return false;
                    }
                    int invStart = (tablePos != null) ? 10 : 9;
                    int invEnd = (tablePos != null) ? 46 : 45;
                    for (int i = invStart; i < invEnd; i++) {
                        ItemStack s = player.currentScreenHandler.getSlot(i).getStack();
                        if (!s.isEmpty() && ing.test(s)) {
                            // 第一步：从背包拿起材料
                            client.interactionManager.clickSlot(
                                    player.currentScreenHandler.syncId, i, 0,
                                    SlotActionType.PICKUP, player);
                            pendingGridSlot = gridSlot;
                            slotCooldown = 1;
                            return false;
                        }
                    }
                    // 材料不足（可能已被前几轮消耗）
                    player.closeHandledScreen();
                    String ingDesc = "未知材料";
                    ItemStack[] matchStacks = ing.getMatchingStacks();
                    if (matchStacks.length > 0) ingDesc = matchStacks[0].getName().getString();
                    sendResult("craft", callId, false, "合成材料不足: " + ingDesc);
                    return true;
                }
                case 3: { // 等待网格填充完成
                    phaseTicks++;
                    int waitTicks = manualMode ? 6 : 3; // 手动摆放时给服务端更多同步时间
                    if (phaseTicks > waitTicks) {
                        // 检查产物槽是否有物品
                        try {
                            ItemStack output = player.currentScreenHandler.getSlot(0).getStack();
                            if (output.isEmpty()) {
                                // clickRecipe 无产物：常见原因是配方书未解锁（服务端静默忽略），
                                // 回退为逐格手动摆放
                                if (!manualMode && isManuallyPlaceable()) {
                                    manualMode = true;
                                    phase = 2;
                                    phaseTicks = 0;
                                    slotCooldown = 2;
                                    return false;
                                }
                                player.closeHandledScreen();
                                if (craftsDone > 0) {
                                    reportCraftResult(player, true);
                                    return true;
                                }
                                sendResult("craft", callId, false,
                                    "合成失败: 配方与当前合成台不匹配，或材料已被消耗");
                                return true;
                            }
                        } catch (Exception e) {
                            // 忽略检查错误，继续尝试取出
                        }
                        phase = 4;
                        phaseTicks = 0;
                    }
                    return false;
                }
                case 4: { // 确保光标空闲（借鉴 Altoclef EnsureFreeCursorSlotTask）
                    ItemStack cursor = player.currentScreenHandler.getCursorStack();
                    if (cursor.isEmpty()) {
                        phase = 5;
                        return false;
                    }
                    // 检查光标物品是否与产物相同（可叠加）
                    ItemStack output = player.currentScreenHandler.getSlot(0).getStack();
                    if (!output.isEmpty() && ItemStack.areItemsEqual(cursor, output)
                            && cursor.getCount() < cursor.getMaxCount()) {
                        // 光标物品与产物相同且可叠加，可直接取出
                        phase = 5;
                        return false;
                    }
                    // 光标有不同物品，尝试放入背包空槽
                    // 背包槽位：工作台 10-36，背包 5-35（跳过护甲）
                    int invStart = (tablePos != null) ? 10 : 9;
                    int invEnd = (tablePos != null) ? 46 : 45;
                    for (int i = invStart; i < invEnd; i++) {
                        try {
                            ItemStack slot = player.currentScreenHandler.getSlot(i).getStack();
                            if (slot.isEmpty()) {
                                // 点击空槽放入光标物品
                                client.interactionManager.clickSlot(
                                        player.currentScreenHandler.syncId, i, 0,
                                        SlotActionType.PICKUP, player);
                                slotCooldown = 2;
                                phase = 5;
                                return false;
                            }
                        } catch (Exception e) {
                            break;
                        }
                    }
                    // 背包满了，无法清空光标，用 THROW 丢弃
                    client.interactionManager.clickSlot(
                            player.currentScreenHandler.syncId, -999, 0,
                            SlotActionType.PICKUP, player);
                    slotCooldown = 2;
                    phase = 5;
                    return false;
                }
                case 5: { // 取出产物（shift-click 转移到背包，每次只取一次合成结果）
                    ItemStack output = player.currentScreenHandler.getSlot(0).getStack();
                    if (output.isEmpty()) {
                        // 产物槽空了，可能已取出
                        phase = 7;
                        return false;
                    }

                    int syncId = player.currentScreenHandler.syncId;
                    // shift-click 将产物槽的物品转移到背包
                    client.interactionManager.clickSlot(
                            syncId, 0, 0, SlotActionType.QUICK_MOVE, player);
                    phase = 6;
                    phaseTicks = 0;
                    slotCooldown = 3;
                    return false;
                }
                case 6: { // 等待产物取出完成
                    phaseTicks++;
                    if (phaseTicks > 3) {
                        craftsDone++;
                        phase = 7;
                        phaseTicks = 0;
                    }
                    return false;
                }
                case 7: { // 检查是否需要继续合成
                    if (craftsDone >= count) {
                        // 已合成足够数量
                        phase = 8;
                        return false;
                    }
                    // 检查产物槽是否还有物品（说明材料还够再合成一次）
                    try {
                        ItemStack output = player.currentScreenHandler.getSlot(0).getStack();
                        if (!output.isEmpty()) {
                            // 还有产物，继续取出
                            phase = 4;
                            return false;
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                    // 产物槽空了，需要重新填充网格
                    phase = 2;
                    return false;
                }
                case 8: { // 关闭界面并报告结果
                    player.closeHandledScreen();
                    reportCraftResult(player, craftsDone > 0);
                    return true;
                }
            }
            return false;
        }

        /** 该配方是否支持手动摆放（有序/无序合成） */
        private boolean isManuallyPlaceable() {
            return recipe.recipe instanceof ShapedRecipe || recipe.recipe instanceof ShapelessRecipe;
        }

        /**
         * 计算手动摆放方案：网格槽位（屏幕坐标，工作台 1-9 / 背包 1-4）+ 对应材料。
         * 有序合成按 pattern 逐格映射，无序合成按顺序填入空格。
         */
        private boolean initManualPlacements() {
            manualCells.clear();
            manualIngredients.clear();
            DefaultedList<Ingredient> ings = recipe.recipe.getIngredients();
            int gridW = (tablePos != null) ? 3 : 2;
            if (recipe.recipe instanceof ShapedRecipe shaped) {
                // Yarn 1.20.1 的 ShapedRecipe 没有无参 getPattern()，
                // 直接用 width/height 按行主序遍历 ingredients（含空位），等价于 pattern 摆放
                int width = shaped.getWidth();
                int height = shaped.getHeight();
                for (int row = 0; row < height; row++) {
                    for (int col = 0; col < width; col++) {
                        int idx = row * width + col;
                        if (idx >= ings.size() || ings.get(idx).isEmpty()) continue;
                        manualCells.add(row * gridW + col + 1);
                        manualIngredients.add(ings.get(idx));
                    }
                }
            } else if (recipe.recipe instanceof ShapelessRecipe) {
                int cell = 1;
                int maxCells = gridW * gridW;
                for (Ingredient ing : ings) {
                    if (ing.isEmpty()) continue;
                    if (cell > maxCells) break;
                    manualCells.add(cell);
                    manualIngredients.add(ing);
                    cell++;
                }
            } else {
                return false;
            }
            return !manualCells.isEmpty();
        }

        private void reportCraftResult(ClientPlayerEntity player, boolean success) {
            PlayerInventory inv = player.getInventory();
            int afterCount = countItemByIngredient(inv, Set.of(outputId));
            int crafted = afterCount - beforeCount;

            if (crafted > 0) {
                String msg = "合成成功: " + recipe.outputName + " ×" + crafted
                        + " (配方: " + recipe.type + ", 站: " + recipe.station + ")";
                if (crafted < count * recipe.outputCount) {
                    msg += " (请求 " + count + " 次, 实际合成 "
                            + (crafted / Math.max(1, recipe.outputCount)) + " 次, 材料不足)";
                }
                sendResult("craft", callId, true, msg);
            } else if (success) {
                sendResult("craft", callId, true,
                    "合成完成但未检测到新增物品 (可能背包已有该物品)");
            } else {
                sendResult("craft", callId, false,
                    "合成可能失败: 未检测到新增物品。请检查材料是否足够或配方是否正确。");
            }
        }
    }

    // ======================== 动作结果回传 ========================

    private static void sendResult(String action, boolean success, String message) {
        sendResult(action, currentCallId, success, message);
    }

    private static void sendResult(String action, long callId, boolean success, String message) {
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

    private static void goToBlock(ClientPlayerEntity player, String blockType, double range) {
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

    private static void attackEntity(ClientPlayerEntity player, String type, double range) {
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

    private static void equipItem(ClientPlayerEntity player, String itemName) {
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
    private static int countItemByIngredient(PlayerInventory inv, Set<String> matchIds) {
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

    private static void releaseAllKeys(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
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
