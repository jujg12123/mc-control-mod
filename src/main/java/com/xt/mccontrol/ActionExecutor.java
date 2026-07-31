package com.xt.mccontrol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.registry.Registries;
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
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
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
                    HitResult hit = player.raycast(5.0, 0, false);
                    if (hit.getType() == HitResult.Type.BLOCK) {
                        ActionResult res = client.interactionManager.interactBlock(
                                player, Hand.MAIN_HAND, (BlockHitResult) hit);
                        String heldName = player.getMainHandStack().isEmpty()
                                ? "空手" : player.getMainHandStack().getItem().getName().getString();
                        sendResult("place", res.isAccepted(),
                                res.isAccepted() ? "已放置 " + heldName : "放置失败");
                    } else {
                        sendResult("place", false, "未瞄准方块");
                    }
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
                    startTask(client, new NavTask(player, tx, ty, tz, true, "go_to_pos"));
                }

                // === 持续挖掘直到破坏 ===
                case "dig_block" -> {
                    double timeout = cmd.has("timeout")
                            ? cmd.get("timeout").getAsDouble() : 10.0;
                    startTask(client, new DigBlockTask(timeout), "dig_block");
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
        private static final int NAV = 0, BREAKING = 1, BYPASS = 2;
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
        private int totalTicks = 0;
        private final int maxTicks = 600;        // 约 30 秒（20 TPS）
        private int strafeDir = 0;
        private int state = NAV;
        private int stateTicks = 0;
        private BlockPos targetObstacle = null;  // BREAKING 正在挖掘的方块
        private double bypassStartX = 0, bypassStartZ = 0;

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

            // ---- 正常导航 ----
            totalTicks++;
            double px = player.getX();
            double py = player.getY() + 0.5;
            double pz = player.getZ();
            double dx = tx - px;
            double dy = ty - py;
            double dz = tz - pz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (dist < 1.5) {
                if (emitResult) sendResult(resultAction, callId, true, "已到达目标位置");
                return true;
            }
            if (totalTicks >= maxTicks || System.currentTimeMillis() - startTime > 32000) {
                if (emitResult) sendResult(resultAction, callId, false, "导航超时");
                return true;
            }

            double yaw = Math.toDegrees(Math.atan2(-dx, dz));
            double pitch = Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));

            // 跳跃按键计时（保持 4 tick 让跳跃真正生效，避免“抽搐”式单 tick 点按）
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
                    // 卡住 ≥1 秒：优先尝试挖掘正前方的阻挡方块
                    BlockPos obstacle = findBlockInFront(player);
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
                BlockPos obstacle = findBlockInFront(player);
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

        /** 查找玩家正前方（行进方向）的阻挡方块：先查相邻脚部/身体高度，其次视线射线 */
        private BlockPos findBlockInFront(ClientPlayerEntity player) {
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
                    // 向下看并按住挖掘键
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

    private static class GoToSurfaceTask implements ActionTask {
        private final long myVersion;
        private final long callId = currentCallId;
        private int phaseTicks = 0;

        GoToSurfaceTask() {
            this.myVersion = actionVersion;
        }

        @Override
        public boolean tick(MinecraftClient client, ClientPlayerEntity player) {
            if (actionVersion != myVersion || navCancelled) return true;

            BlockPos pos = player.getBlockPos();
            // 已露天（头顶连续 air）则完成
            if (isOpenSky(player, pos)) {
                sendResult("go_to_surface", callId, true, "已回到地面");
                return true;
            }

            BlockPos above = pos.up();
            BlockState aboveState = player.getWorld().getBlockState(above);
            player.setPitch(-90f);

            if (aboveState.isAir()) {
                // 头顶是空气，跳跃上去
                client.options.jumpKey.setPressed(true);
                client.options.forwardKey.setPressed(true);
                phaseTicks++;
                if (phaseTicks > 10) {
                    client.options.jumpKey.setPressed(false);
                    client.options.forwardKey.setPressed(false);
                    phaseTicks = 0;
                }
            } else {
                // 头顶有方块，挖掉
                client.options.attackKey.setPressed(true);
                if (player.getWorld().getBlockState(above).isAir()) {
                    client.options.attackKey.setPressed(false);
                    client.options.jumpKey.setPressed(true);
                    phaseTicks++;
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
                    sendResult("craft", callId, false, "合成材料不足: " + ing.toJson());
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
                List<String> pattern = shaped.getPattern();
                for (int row = 0; row < pattern.size(); row++) {
                    String line = pattern.get(row);
                    for (int col = 0; col < line.length(); col++) {
                        if (line.charAt(col) == ' ') continue;
                        int idx = row * shaped.getWidth() + col;
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
            // 不立即发送结果——让 NavTask 完成后发送到达/超时结果
            // emitResult=true 使 NavTask 在到达或超时时调用 sendResult
            startTask(client, new NavTask(player, nearest.getX() + 0.5, nearest.getY(),
                    nearest.getZ() + 0.5, true, "go_to_block"));
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
                sendResult("craft", false,
                    "该物品只能通过熔炼获得，不能用工作台合成。"
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
            // 所有配方材料都不足
            StringBuilder sb = new StringBuilder("材料不足，已尝试全部 ");
            sb.append(craftableRecipes.size()).append(" 个配方均无法合成。");
            sb.append("缺少的材料: ");
            sb.append(String.join("; ", missingInfo));
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
