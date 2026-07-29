package com.xt.mccontrol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

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
                cancelCurrentTask(client);
                sendResult("stop_nav", true, "已停止寻路");
                return;
            }

            // --- 其它动作：先取消旧的长任务（防按键冲突），再执行 ---
            actionVersion++;
            navCancelled = false;
            if (currentTask != null) {
                releaseAllKeys(client);
                currentTask = null;
                actionInProgress = false;
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
                    HitResult hit = player.raycast(5.0, 0, false);
                    if (hit.getType() == HitResult.Type.BLOCK) {
                        ActionResult res = client.interactionManager.interactBlock(
                                player, Hand.MAIN_HAND, (BlockHitResult) hit);
                        sendResult("place", res.isAccepted(), res.isAccepted() ? "已放置" : "放置失败");
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
                    player.setYaw(yaw);
                    player.setPitch(pitch);
                    sendResult("look_at", true, "视角已调整");
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
                    startTask(client, new NavTask(player, tx, ty, tz, true));
                }

                // === 持续挖掘直到破坏 ===
                case "dig_block" -> {
                    double timeout = cmd.has("timeout")
                            ? cmd.get("timeout").getAsDouble() : 10.0;
                    startTask(client, new DigBlockTask(timeout));
                }

                // === 向下挖掘（安全） ===
                case "dig_down" -> {
                    int distance = cmd.has("distance")
                            ? cmd.get("distance").getAsInt() : 1;
                    startTask(client, new DigDownTask(distance));
                }

                // === 回到地面（向上挖） ===
                case "go_to_surface" -> startTask(client, new GoToSurfaceTask());

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
    private static void startTask(MinecraftClient client, ActionTask task) {
        releaseAllKeys(client);
        actionInProgress = true;
        currentTask = task;
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
        private double lastX, lastZ;
        private int stuckTicks = 0;
        private int totalTicks = 0;
        private final int maxTicks = 600; // 约 30 秒（20 TPS）
        private int strafeDir = 0;        // BYPASS 方向
        private int state = NAV;
        private int stateTicks = 0;

        NavTask(ClientPlayerEntity player, double tx, double ty, double tz, boolean emitResult) {
            this.tx = tx; this.ty = ty; this.tz = tz;
            this.emitResult = emitResult;
            this.myVersion = actionVersion;
            this.lastX = player.getX();
            this.lastZ = player.getZ();
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
                if (emitResult) sendResult("go_to_pos", true, "已到达目标位置");
                return true;
            }
            if (totalTicks >= maxTicks) {
                if (emitResult) sendResult("go_to_pos", false, "导航超时");
                return true;
            }

            double yaw = Math.toDegrees(Math.atan2(-dx, dz));
            double pitch = Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));

            // 卡住检测
            double moved = Math.abs(player.getX() - lastX) + Math.abs(player.getZ() - lastZ);
            if (moved < 0.05) {
                stuckTicks++;
                if (stuckTicks == 5) {
                    // 跳一下
                    applyMove(client, player, yaw, pitch, true, 0);
                } else if (stuckTicks == 15) {
                    strafeDir = (strafeDir == 0) ? 1 : (strafeDir == 1 ? -1 : 1);
                    applyMove(client, player, yaw, pitch, true, strafeDir);
                } else if (stuckTicks >= 30) {
                    // 尝试挖障碍
                    BlockPos obstacle = findObstacleInFront(player, tx, ty, tz);
                    if (obstacle != null && isBreakable(player, obstacle)) {
                        state = BREAKING;
                        stateTicks = 0;
                        client.options.forwardKey.setPressed(false);
                        client.options.attackKey.setPressed(true);
                        return false;
                    }
                    // 不可破坏，绕行
                    strafeDir = (strafeDir == 0) ? 1 : (strafeDir == 1 ? -1 : 1);
                    state = BYPASS;
                    stateTicks = 0;
                    return false;
                } else {
                    applyMove(client, player, yaw, pitch, false, 0);
                }
            } else {
                stuckTicks = 0;
                strafeDir = 0;
                applyMove(client, player, yaw, pitch, false, 0);
            }
            lastX = player.getX();
            lastZ = player.getZ();
            return false;
        }

        private boolean tickBreaking(MinecraftClient client, ClientPlayerEntity player) {
            stateTicks++;
            // 检查前方障碍是否已破坏
            HitResult hit = player.raycast(4.0, 0, false);
            boolean cleared = true;
            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockPos ob = ((BlockHitResult) hit).getBlockPos();
                if (!player.getWorld().getBlockState(ob).isAir()) {
                    cleared = false;
                }
            }
            if (cleared || stateTicks > 40) { // 最多 2 秒
                client.options.attackKey.setPressed(false);
                stuckTicks = 0;
                state = NAV;
            }
            return false;
        }

        private boolean tickBypass(MinecraftClient client, ClientPlayerEntity player) {
            stateTicks++;
            // 朝目标方向但侧移绕行
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
            if (stateTicks > 30) { // 1.5 秒后恢复导航
                client.options.leftKey.setPressed(false);
                client.options.rightKey.setPressed(false);
                stuckTicks = 20;
                state = NAV;
            }
            return false;
        }

        private void applyMove(MinecraftClient client, ClientPlayerEntity player,
                               double yaw, double pitch, boolean jump, int sDir) {
            player.setYaw((float) yaw);
            player.setPitch((float) pitch);
            client.options.forwardKey.setPressed(true);
            client.options.jumpKey.setPressed(jump);
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
        private BlockPos targetPos;
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
                    sendResult("dig_block", false, "视线内没有方块");
                    return true;
                }
                targetPos = ((BlockHitResult) hit).getBlockPos();
                BlockState s = player.getWorld().getBlockState(targetPos);
                if (s.isAir()) {
                    sendResult("dig_block", false, "目标方块是空气");
                    return true;
                }
                System.out.println("[MC-Control] Digging " +
                        s.getBlock().getName().getString() + " at " + targetPos.toShortString());
                initialized = true;
                client.options.attackKey.setPressed(true);
                return false;
            }

            // 检查是否已破坏
            if (player.getWorld().getBlockState(targetPos).isAir()) {
                client.options.attackKey.setPressed(false);
                // 延迟向前走捡掉落物（scheduler + client.execute，线程安全）
                scheduler.schedule(() ->
                        client.execute(() -> {
                            client.options.forwardKey.setPressed(true);
                            scheduler.schedule(() ->
                                    client.execute(() -> client.options.forwardKey.setPressed(false)),
                                    500, TimeUnit.MILLISECONDS);
                        }), 300, TimeUnit.MILLISECONDS);
                sendResult("dig_block", true, "方块已破坏");
                return true;
            }

            if (System.currentTimeMillis() - startTime > timeoutMs) {
                client.options.attackKey.setPressed(false);
                sendResult("dig_block", false, "挖掘超时");
                return true;
            }
            return false;
        }
    }

    // ======================== DigDownTask：安全向下挖 ========================

    private static class DigDownTask implements ActionTask {
        private final int distance;
        private final long myVersion;
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
                sendResult("dig_down", true, "已向下挖掘 " + distance + " 格");
                return true;
            }

            switch (phase) {
                case 0: { // 检查安全 + 开始挖
                    BlockPos pos = player.getBlockPos();
                    digPos = pos.down(current + 1);
                    BlockState state = player.getWorld().getBlockState(digPos);
                    String name = state.getBlock().getName().getString().toLowerCase();
                    if (name.contains("lava") || name.contains("water")) {
                        sendResult("dig_down", false, "遇到危险: " + name);
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
                        sendResult("dig_down", false, "挖掘超时");
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
                sendResult("go_to_surface", true, "已回到地面");
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

    // ======================== 动作结果回传 ========================

    private static void sendResult(String action, boolean success, String message) {
        try {
            ControlServer server = MCControlMod.getServer();
            if (server != null) {
                JsonObject result = new JsonObject();
                result.addProperty("type", "action_result");
                result.addProperty("action", action);
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
                    nearest.getZ() + 0.5, true));
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

    // ======================== 合成（动态配方查询） ========================

    /**
     * 自动合成物品。使用 RecipeLookup 动态查询配方，支持所有已注册的配方。
     * 自动检查材料（支持标签匹配，如任意木板均可），扣除材料并给成品。
     */
    private static void craftItem(MinecraftClient client, ClientPlayerEntity player,
                                    String recipe, int count) {
        if (recipe.isEmpty()) {
            sendResult("craft", false, "合成配方为空");
            return;
        }

        // 动态查询配方
        List<RecipeLookup.RecipeInfo> recipes = RecipeLookup.findRecipes(recipe);
        if (recipes.isEmpty()) {
            sendResult("craft", false,
                "未找到 " + recipe + " 的合成配方。该物品可能需要通过其他方式获得"
                + "（挖矿、打怪、交易等），或物品 ID 不正确。"
                + "可用 mc_queryRecipe 工具查询。");
            return;
        }

        // 选取最佳配方
        RecipeLookup.RecipeInfo best = RecipeLookup.pickBestRecipe(recipes);

        // 获取所需材料（含标签匹配信息）
        List<RecipeLookup.RequiredIngredient> required =
                RecipeLookup.getRequiredIngredients(best);

        // 检查材料是否足够
        PlayerInventory inv = player.getInventory();
        for (RecipeLookup.RequiredIngredient req : required) {
            int need = req.count * count;
            int have = countItemByIngredient(inv, req.matchIds);
            if (have < need) {
                sendResult("craft", false,
                    "材料不足: 需要 " + req.displayName + " ×" + need
                    + "，仅有 " + have);
                return;
            }
        }

        // 扣除材料
        for (RecipeLookup.RequiredIngredient req : required) {
            int need = req.count * count;
            removeItemByIngredient(inv, req.matchIds, need);
        }

        // 给成品
        Identifier outId = best.outputItemId.contains(":")
                ? new Identifier(best.outputItemId)
                : new Identifier("minecraft", best.outputItemId);
        ItemStack result = new ItemStack(Registries.ITEM.get(outId));
        result.setCount(best.outputCount * count);
        if (!inv.insertStack(result)) {
            player.dropItem(result, false);
        }

        sendResult("craft", true,
            "合成成功: " + best.outputName + " ×" + (best.outputCount * count)
            + " (配方: " + best.type + ", 站: " + best.station + ")");
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

    /** 从背包移除匹配指定材料集合的物品（支持标签） */
    private static void removeItemByIngredient(PlayerInventory inv,
                                                Set<String> matchIds, int amount) {
        int remaining = amount;
        for (int i = 0; i < 36 && remaining > 0; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            Identifier id = Registries.ITEM.getId(stack.getItem());
            if (id != null && matchIds.contains(id.getPath())) {
                int take = Math.min(stack.getCount(), remaining);
                stack.decrement(take);
                remaining -= take;
                if (stack.getCount() == 0) {
                    inv.setStack(i, ItemStack.EMPTY);
                }
            }
        }
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
