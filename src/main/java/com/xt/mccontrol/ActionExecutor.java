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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ActionExecutor {
    private static final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(4);

    private static volatile boolean navCancelled = false;
    private static volatile long actionVersion = 0;

    public static void execute(String commandJson) {
        actionVersion++; // 新动作递增版本号，取消旧的后台任务
        navCancelled = false; // 重置取消标志，供新动作使用
        String actionName = "unknown";
        try {
            JsonObject cmd = JsonParser.parseString(commandJson).getAsJsonObject();
            actionName = cmd.get("action").getAsString();
            String action = actionName;
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            if (player == null) return;

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
                        // 持续使用模式（如吃食物、拉弓）— 参考 consume 方法
                        client.options.useKey.setPressed(true);
                        scheduler.schedule(() ->
                                client.execute(() ->
                                        client.options.useKey.setPressed(false)),
                                (long) (duration * 1000), TimeUnit.MILLISECONDS);
                        sendResult("use", true, "持续使用中，持续 " + duration + "秒");
                    } else {
                        // 单次使用模式
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

                // === 寻路 ===
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
                    goToPos(player, tx, ty, tz, true);
                }
                case "stop_nav" -> {
                    navCancelled = true;
                    releaseAllKeys(client);
                    AutoBehaviorManager.setNavigating(false);
                    sendResult("stop_nav", true, "已停止寻路");
                }

                // === 新增：持续挖掘直到方块破坏 ===
                case "dig_block" -> {
                    double timeout = cmd.has("timeout")
                            ? cmd.get("timeout").getAsDouble() : 10.0;
                    digBlock(player, timeout);
                }

                // === 新增：向下挖掘（安全） ===
                case "dig_down" -> {
                    int distance = cmd.has("distance")
                            ? cmd.get("distance").getAsInt() : 1;
                    digDown(player, distance);
                }

                // === 新增：回到地面 ===
                case "go_to_surface" -> goToSurface(player);

                // === 新增：攻击实体 ===
                case "attack_entity" -> {
                    String type = cmd.has("type")
                            ? cmd.get("type").getAsString() : "";
                    double range = cmd.has("range")
                            ? cmd.get("range").getAsDouble() : 16;
                    attackEntity(player, type, range);
                }

                // === 新增：装备物品 ===
                case "equip" -> {
                    String itemName = cmd.get("item_name").getAsString();
                    equipItem(player, itemName);
                }

                // === 新增：吃/喝 ===
                case "consume" -> {
                    String itemName = cmd.has("item_name")
                            ? cmd.get("item_name").getAsString() : "";
                    consumeItem(player, itemName);
                }

                // === 自动行为管理 ===
                case "enable_auto" -> {
                    AutoBehaviorManager.setEnabled(true);
                    sendResult("enable_auto", true, "自动行为已启用");
                }
                case "disable_auto" -> {
                    AutoBehaviorManager.setEnabled(false);
                    sendResult("disable_auto", true, "自动行为已禁用");
                }

                default -> {
                    System.out.println(
                            "[MC-Control] Unknown action: " + action);
                    sendResult(action, false, "未知动作: " + action);
                }
            }
        } catch (Exception e) {
            System.err.println("[MC-Control] Action failed: " + e.getMessage());
            sendResult(actionName, false, "执行失败: " + e.getMessage());
        }
    }

    // === 动作结果回传 ===
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

    // === 寻路 ===
    private static void goToBlock(ClientPlayerEntity player, String blockType, double range) {
        MinecraftClient client = MinecraftClient.getInstance();
        World world = player.getWorld();
        BlockPos playerPos = player.getBlockPos();
        int r = (int) range;
        String targetLower = blockType.toLowerCase();

        // 在主线程执行搜索，避免后台线程访问 World API
        client.execute(() -> {
            BlockPos nearest = null;
            double nearestDist = Double.MAX_VALUE;
            int checked = 0;
            int maxCheck = 10000;

            // 螺旋搜索：从近到远
            for (int radius = 1; radius <= r && checked < maxCheck; radius++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        // 只处理当前环的边缘
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
                                double weightedDist = hx * hx + hz * hz + (hy * hy) * 9.0;
                                if (weightedDist < nearestDist && weightedDist > 0.25) {
                                    nearestDist = weightedDist;
                                    nearest = pos;
                                    if (weightedDist < 4.0) break;
                                }
                            }
                        }
                    }
                }
            }

            if (nearest != null) {
                sendResult("go_to_block", true, "找到方块，开始导航");
                goToPos(player, nearest.getX() + 0.5, nearest.getY(), nearest.getZ() + 0.5, false);
            } else {
                System.out.println("[MC-Control] Block not found: " + blockType + " (checked " + checked + " blocks)");
                sendResult("go_to_block", false, "未找到方块: " + blockType);
            }
        });
    }

    private static void goToPos(ClientPlayerEntity player, double tx, double ty, double tz, boolean emitResult) {
        MinecraftClient client = MinecraftClient.getInstance();

        AutoBehaviorManager.setNavigating(true);
        // 在后台线程中循环追踪目标
        scheduler.execute(() -> {
            long myVersion = actionVersion;
            double lastX = player.getX();
            double lastZ = player.getZ();
            int stuckTicks = 0;
            int totalTicks = 0;
            int maxTicks = 600; // 30 秒超时
            boolean jumping = false;
            int strafeDir = 0; // 0=直走, 1=左偏, -1=右偏

            while (totalTicks < maxTicks && !navCancelled && actionVersion == myVersion) {
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                totalTicks++;

                double px = player.getX();
                double py = player.getY() + 0.5;
                double pz = player.getZ();
                double dx = tx - px;
                double dy = ty - py;
                double dz = tz - pz;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

                if (dist < 1.5) {
                    client.execute(() -> releaseAllKeys(client));
                    System.out.println("[MC-Control] Reached target");
                    AutoBehaviorManager.setNavigating(false);
                    if (emitResult) sendResult("go_to_pos", true, "已到达目标位置");
                    return;
                }

                // 计算朝向
                double yaw = Math.toDegrees(Math.atan2(-dx, dz));
                double pitch = Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));

                // 卡住检测
                double moved = Math.abs(player.getX() - lastX) + Math.abs(player.getZ() - lastZ);
                if (moved < 0.05) {
                    stuckTicks++;
                    if (stuckTicks == 5) {
                        // 第一次卡住：跳
                        jumping = true;
                        System.out.println("[MC-Control] Stuck, jumping");
                    } else if (stuckTicks == 15) {
                        // 还卡：左右试探
                        strafeDir = (strafeDir == 0) ? 1 : (strafeDir == 1 ? -1 : 1);
                        jumping = true;
                        System.out.println("[MC-Control] Stuck, strafing " + (strafeDir > 0 ? "left" : "right"));
                    } else if (stuckTicks >= 30) {
                        // 还卡：尝试挖掉前方方块
                        System.out.println("[MC-Control] Stuck, trying to break obstacle");
                        attemptBreakObstacle(player, tx, ty, tz);
                        stuckTicks = 0;
                        strafeDir = 0;
                    }
                } else {
                    stuckTicks = 0;
                    jumping = false;
                    strafeDir = 0;
                }
                lastX = player.getX();
                lastZ = player.getZ();

                // 应用移动
                final float fy = (float) yaw;
                final float fp = (float) pitch;
                final boolean doJump = jumping;
                final int sDir = strafeDir;

                client.execute(() -> {
                    player.setYaw(fy);
                    player.setPitch(fp);
                    client.options.forwardKey.setPressed(true);
                    if (doJump) client.options.jumpKey.setPressed(true);
                    else client.options.jumpKey.setPressed(false);
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
                });
            }

            client.execute(() -> releaseAllKeys(client));
            if (actionVersion != myVersion) {
                AutoBehaviorManager.setNavigating(false);
                return; // 被新动作取消，不发结果
            }
            System.out.println("[MC-Control] Navigation timeout");
            AutoBehaviorManager.setNavigating(false);
            if (emitResult) sendResult("go_to_pos", false, "导航超时");
        });
    }

    // 尝试破坏前方障碍物（从后台线程调用，所有 MC API 通过 client.execute 调度）
    private static void attemptBreakObstacle(ClientPlayerEntity player, double tx, double ty, double tz) {
        MinecraftClient client = MinecraftClient.getInstance();
        final boolean[] shouldBreak = {false};
        final String[] blockName = {""};
        final BlockPos[] obstaclePos = {null};
    
        // 在主线程执行 raycast 和方块状态查询
        client.execute(() -> {
            if (client.player == null) return;
            HitResult hit = client.player.raycast(4.0, 0, false);
            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockHitResult bHit = (BlockHitResult) hit;
                BlockPos obstacle = bHit.getBlockPos();
                // 避开目标方块本身
                if (Math.abs(obstacle.getX() - tx) < 1 &&
                    Math.abs(obstacle.getY() - ty) < 1 &&
                    Math.abs(obstacle.getZ() - tz) < 1) {
                    return; // 这就是目标，不挖
                }
                // 判断是否是“软”障碍（树叶、泥土等），可以挖掉
                String name = client.player.getWorld().getBlockState(obstacle).getBlock()
                        .getName().getString().toLowerCase();
                boolean soft = name.contains("leaf") || name.contains("dirt") ||
                              name.contains("grass") || name.contains("sand") ||
                              name.contains("gravel") || name.contains("snow") ||
                              name.contains("tall_grass") || name.contains("fern") ||
                              name.contains("flower") || name.contains("sapling");
                if (soft) {
                    blockName[0] = name;
                    obstaclePos[0] = obstacle;
                    shouldBreak[0] = true;
                    client.player.setPitch(0);
                }
            }
        });
    
        // 等待主线程执行完成
        try { Thread.sleep(50); } catch (InterruptedException e) { /* ignore */ }
    
        if (shouldBreak[0]) {
            System.out.println("[MC-Control] Breaking soft obstacle: " + blockName[0]);
            digBlock(player, 3.0);
        }
    }

    // === 持续挖掘直到方块破坏，然后捡掉落物 ===
    private static void digBlock(ClientPlayerEntity player, double timeout) {
        MinecraftClient client = MinecraftClient.getInstance();

        // 在主线程执行 raycast 和初始方块状态查询（可能从后台线程调用）
        client.execute(() -> {
            if (client.player == null) {
                sendResult("dig_block", false, "玩家已离线");
                return;
            }
            World world = client.player.getWorld();

            HitResult hit = client.player.raycast(5.0, 0, false);
            if (hit.getType() != HitResult.Type.BLOCK) {
                System.out.println("[MC-Control] No block in sight to dig");
                sendResult("dig_block", false, "视线内没有方块");
                return;
            }
            BlockHitResult blockHit = (BlockHitResult) hit;
            BlockPos targetPos = blockHit.getBlockPos();
            BlockState targetState = world.getBlockState(targetPos);
            if (targetState.isAir()) {
                System.out.println("[MC-Control] Target block is air");
                sendResult("dig_block", false, "目标方块是空气");
                return;
            }
            String blockName = targetState.getBlock().getName().getString();
            System.out.println("[MC-Control] Digging " + blockName + " at " + targetPos.toShortString());

            // 按住挖掘键
            client.options.attackKey.setPressed(true);

            // 用调度器定时检查方块状态并释放
            long timeoutMs = (long) (timeout * 1000);
            long checkInterval = 100; // 每 100ms 检查一次

            scheduler.execute(() -> {
                long myVersion = actionVersion;
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < timeoutMs && !navCancelled && actionVersion == myVersion) {
                    try { Thread.sleep(checkInterval); } catch (InterruptedException e) { break; }

                    // 在主线程检查方块状态
                    final boolean[] broken = {false};
                    client.execute(() -> {
                        if (client.player == null) return;
                        if (client.player.getWorld().getBlockState(targetPos).isAir()) {
                            broken[0] = true;
                        }
                    });
                    try { Thread.sleep(20); } catch (InterruptedException e) { break; } // 等待主线程执行

                    if (broken[0]) {
                        // 方块已破坏
                        client.execute(() -> {
                            client.options.attackKey.setPressed(false);
                            // 延迟后向前走捡掉落物
                            scheduler.schedule(() ->
                                    client.execute(() -> {
                                        client.options.forwardKey.setPressed(true);
                                        scheduler.schedule(() ->
                                                client.execute(() -> client.options.forwardKey.setPressed(false)),
                                                500, TimeUnit.MILLISECONDS);
                                    }),
                                    300, TimeUnit.MILLISECONDS);
                        });
                        System.out.println("[MC-Control] Block broken!");
                        sendResult("dig_block", true, "方块已破坏");
                        return;
                    }
                }
                // 超时或取消
                client.execute(() -> client.options.attackKey.setPressed(false));
                if (actionVersion != myVersion) return; // 被新动作取消，不发结果
                System.out.println("[MC-Control] Dig timed out");
                sendResult("dig_block", false, "挖掘超时");
            });
        });
    }

    // === 安全向下挖（异步） ===
    private static void digDown(ClientPlayerEntity player, int distance) {
        MinecraftClient client = MinecraftClient.getInstance();

        scheduler.execute(() -> {
            long myVersion = actionVersion;
            for (int i = 0; i < distance; i++) {
                if (navCancelled || actionVersion != myVersion) {
                    sendResult("dig_down", false, "已取消");
                    return;
                }

                // 在主线程获取位置、检查危险、设置视角和按键
                final int iter = i;
                final boolean[] safe = {true};
                final String[] hazard = {null};

                client.execute(() -> {
                    if (client.player == null) { safe[0] = false; return; }
                    BlockPos pos = client.player.getBlockPos();
                    BlockPos below = pos.down(iter + 1);
                    BlockState state = client.player.getWorld().getBlockState(below);
                    String name = state.getBlock().getName().getString();

                    if (name.toLowerCase().contains("lava") || name.toLowerCase().contains("water")) {
                        hazard[0] = name; safe[0] = false; return;
                    }
                    if (state.isAir() && iter > 0) {
                        hazard[0] = "void"; safe[0] = false; return;
                    }

                    client.player.setPitch(90f);
                    client.options.attackKey.setPressed(true);
                });

                // 等待主线程执行 + 挖掘
                try { Thread.sleep(50); } catch (InterruptedException e) { break; }
                if (!safe[0]) {
                    sendResult("dig_down", false, hazard[0] != null ? "遇到危险: " + hazard[0] : "玩家已离线");
                    return;
                }
                try { Thread.sleep(2500); } catch (InterruptedException e) { break; }

                // 停止攻击 + 潜行移动
                client.execute(() -> {
                    if (client.player == null) return;
                    client.options.attackKey.setPressed(false);
                    client.options.sneakKey.setPressed(true);
                    client.options.forwardKey.setPressed(true);
                });
                try { Thread.sleep(300); } catch (InterruptedException e) { break; }
                client.execute(() -> {
                    client.options.forwardKey.setPressed(false);
                    client.options.sneakKey.setPressed(false);
                });
            }
            sendResult("dig_down", true, "已向下挖掘 " + distance + " 格");
        });
    }

    // === 回到地面（异步） ===
    private static void goToSurface(ClientPlayerEntity player) {
        MinecraftClient client = MinecraftClient.getInstance();
        World world = player.getWorld();
        BlockPos pos = player.getBlockPos();

        // 在主线程查找头顶方块
        client.execute(() -> {
            int surfaceY = pos.getY();
            for (int y = pos.getY(); y < 320; y++) {
                BlockPos check = new BlockPos(pos.getX(), y, pos.getZ());
                if (world.getBlockState(check).isAir()) {
                    surfaceY = y;
                    break;
                }
            }
            System.out.println("[MC-Control] Surface at y=" + surfaceY + ", current y=" + pos.getY());

                        final int targetY = surfaceY; // 捕获为 final 变量
                        // 在后台线程执行跳跃循环（需要 Thread.sleep）
                        scheduler.execute(() -> {
                            long myVersion = actionVersion;
                            // 抬头向上
                            client.execute(() -> player.setPitch(-90f));
                            for (int y = pos.getY(); y < targetY && !navCancelled && actionVersion == myVersion; y++) {
                    client.execute(() -> client.options.jumpKey.setPressed(true));
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                    client.execute(() -> client.options.jumpKey.setPressed(false));
                    try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                }
                System.out.println("[MC-Control] Reached surface");
                sendResult("go_to_surface", true, "已回到地面");
            });
        });
    }

    // === 攻击实体 ===
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
            System.out.println("[MC-Control] No entity found" + (type.isEmpty() ? "" : " of type " + type));
            sendResult("attack_entity", false, "未找到实体" + (type.isEmpty() ? "" : ": " + type));
            return;
        }

        // 看向目标并攻击
        double dx = target.getX() - player.getX();
        double dy = (target.getY() + target.getHeight() / 2) - (player.getY() + player.getEyeHeight(player.getPose()));
        double dz = target.getZ() - player.getZ();
        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setPitch((float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));

        client.options.attackKey.setPressed(true);
        scheduler.schedule(() ->
                client.execute(() -> client.options.attackKey.setPressed(false)),
                1500, TimeUnit.MILLISECONDS);
        System.out.println("[MC-Control] Attacking " + target.getName().getString());
        sendResult("attack_entity", true, "攻击 " + target.getName().getString());
    }

    // === 装备物品 ===
    private static void equipItem(ClientPlayerEntity player, String itemName) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.getItem().getName().getString()
                    .toLowerCase().contains(itemName.toLowerCase())) {
                // 切换到该物品并右键装备
                if (i < 9) {
                    inv.selectedSlot = i;
                } else {
                    // 从背包移到快捷栏
                    MinecraftClient.getInstance().interactionManager
                            .pickFromInventory(i);
                }
                // 右键装备
                MinecraftClient.getInstance().interactionManager
                        .interactItem(player, Hand.MAIN_HAND);
                System.out.println("[MC-Control] Equipped " + itemName);
                sendResult("equip", true, "已装备 " + itemName);
                return;
            }
        }
        System.out.println("[MC-Control] Item '" + itemName + "' not found in inventory");
        sendResult("equip", false, "未找到物品: " + itemName);
    }

    // === 吃/喝 ===
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
            System.out.println("[MC-Control] No food/item found");
            sendResult("consume", false, "未找到: " + itemName);
            return;
        }

        if (slot < 9) {
            inv.selectedSlot = slot;
        } else {
            MinecraftClient.getInstance().interactionManager.pickFromInventory(slot);
        }

        // 按住右键吃
        MinecraftClient client = MinecraftClient.getInstance();
        client.options.useKey.setPressed(true);
        scheduler.schedule(() ->
                client.execute(() -> client.options.useKey.setPressed(false)),
                2000, TimeUnit.MILLISECONDS);
        System.out.println("[MC-Control] Consuming item in slot " + slot);
        sendResult("consume", true, "已消耗: " + foundName);
    }

    private static void releaseAllKeys(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
        client.options.attackKey.setPressed(false);
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
