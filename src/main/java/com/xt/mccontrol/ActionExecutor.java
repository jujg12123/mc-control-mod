package com.xt.mccontrol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
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

    public static void execute(String commandJson) {
        try {
            JsonObject cmd = JsonParser.parseString(commandJson).getAsJsonObject();
            String action = cmd.get("action").getAsString();
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
                }
                case "attack" -> {
                    double duration = cmd.has("duration")
                            ? cmd.get("duration").getAsDouble() : 2.0;
                    client.options.attackKey.setPressed(true);
                    scheduler.schedule(() ->
                            client.execute(() ->
                                    client.options.attackKey.setPressed(false)),
                            (long) (duration * 1000), TimeUnit.MILLISECONDS);
                }
                case "place" -> {
                    HitResult hit = player.raycast(5.0, 0, false);
                    if (hit.getType() == HitResult.Type.BLOCK) {
                        client.interactionManager.interactBlock(
                                player, Hand.MAIN_HAND, (BlockHitResult) hit);
                    }
                }
                case "switch_slot" -> {
                    int slot = cmd.get("slot").getAsInt();
                    if (slot >= 0 && slot <= 8) {
                        player.getInventory().selectedSlot = slot;
                    }
                }
                case "jump" -> {
                    client.options.jumpKey.setPressed(true);
                    scheduler.schedule(() ->
                            client.execute(() ->
                                    client.options.jumpKey.setPressed(false)),
                            100, TimeUnit.MILLISECONDS);
                }
                case "look_at" -> {
                    float yaw = cmd.get("yaw").getAsFloat();
                    float pitch = cmd.get("pitch").getAsFloat();
                    player.setYaw(yaw);
                    player.setPitch(pitch);
                }
                case "sneak" -> client.options.sneakKey.setPressed(true);
                case "unsneak" -> client.options.sneakKey.setPressed(false);
                case "use" -> client.interactionManager.interactItem(
                        player, Hand.MAIN_HAND);
                case "drop" -> player.dropSelectedItem(false);

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
                    goToPos(player, tx, ty, tz);
                }
                case "stop_nav" -> releaseAllKeys(client);

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

                default -> System.out.println(
                        "[MC-Control] Unknown action: " + action);
            }
        } catch (Exception e) {
            System.err.println("[MC-Control] Action failed: " + e.getMessage());
        }
    }

    // === 寻路 ===
    private static void goToBlock(ClientPlayerEntity player, String blockType, double range) {
        MinecraftClient client = MinecraftClient.getInstance();
        World world = player.getWorld();
        BlockPos playerPos = player.getBlockPos();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        int r = (int) range;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dy = -8; dy <= 8; dy++) {
                        for (int dz = -r; dz <= r; dz++) {
                            BlockPos pos = playerPos.add(dx, dy, dz);
                            BlockState state = world.getBlockState(pos);
                            if (state.isAir()) continue;
                            // 用注册 ID 匹配，兼容中文名和英文 ID
                            Identifier id = Registries.BLOCK.getId(state.getBlock());
                            String idStr = id != null ? id.toString() : "";
                            String name = state.getBlock().getName().getString();
                            if (idStr.toLowerCase().contains(blockType.toLowerCase()) ||
                                                            name.toLowerCase().contains(blockType.toLowerCase())) {
                                                            // 加权距离：垂直差 3 倍权重，优先找同高度或下方的方块
                                                            double dx = pos.getX() - playerPos.getX();
                                                            double dy = pos.getY() - playerPos.getY();
                                                            double dz = pos.getZ() - playerPos.getZ();
                                                            double weightedDist = dx*dx + dz*dz + (dy*dy) * 9.0;
                                                            if (weightedDist < nearestDist && weightedDist > 0.25) {
                                                                nearestDist = weightedDist;
                                                                nearest = pos;
                                                            }
                                                        }
                        }
                    }
                }

        if (nearest == null) {
            System.out.println("[MC-Control] No block '" + blockType + "' found");
            return;
        }
        System.out.println("[MC-Control] Found " + blockType + " at " + nearest.toShortString());
        goToPos(player, nearest.getX() + 0.5, nearest.getY(), nearest.getZ() + 0.5);
    }

    private static void goToPos(ClientPlayerEntity player, double tx, double ty, double tz) {
            MinecraftClient client = MinecraftClient.getInstance();
            World world = player.getWorld();

            // 在后台线程中循环追踪目标
            scheduler.execute(() -> {
                double lastX = player.getX();
                double lastZ = player.getZ();
                int stuckTicks = 0;
                int totalTicks = 0;
                int maxTicks = 600; // 30 秒超时
                boolean jumping = false;
                int strafeDir = 0; // 0=直走, 1=左偏, -1=右偏

                while (totalTicks < maxTicks) {
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
                System.out.println("[MC-Control] Navigation timeout");
            });
        }

        // 尝试破坏前方障碍物
        private static void attemptBreakObstacle(ClientPlayerEntity player, double tx, double ty, double tz) {
            MinecraftClient client = MinecraftClient.getInstance();
            // 看向前方
            HitResult hit = player.raycast(4.0, 0, false);
            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockHitResult bHit = (BlockHitResult) hit;
                BlockPos obstacle = bHit.getBlockPos();
                // 避开目标方块本身
                if (Math.abs(obstacle.getX() - tx) < 1 && 
                    Math.abs(obstacle.getY() - ty) < 1 && 
                    Math.abs(obstacle.getZ() - tz) < 1) {
                    return; // 这就是目标，不挖
                }
                // 判断是否是"软"障碍（树叶、泥土等），可以挖掉
                String name = player.getWorld().getBlockState(obstacle).getBlock()
                        .getName().getString().toLowerCase();
                boolean soft = name.contains("leaf") || name.contains("dirt") || 
                              name.contains("grass") || name.contains("sand") ||
                              name.contains("gravel") || name.contains("snow") ||
                              name.contains("tall_grass") || name.contains("fern") ||
                              name.contains("flower") || name.contains("sapling");
                if (soft) {
                    System.out.println("[MC-Control] Breaking soft obstacle: " + name);
                    player.setPitch(0);
                    digBlock(player, 3.0);
                }
            }
        }

    // === 持续挖掘直到方块破坏，然后捡掉落物 ===
        private static void digBlock(ClientPlayerEntity player, double timeout) {
            MinecraftClient client = MinecraftClient.getInstance();
            World world = player.getWorld();

            HitResult hit = player.raycast(5.0, 0, false);
            if (hit.getType() != HitResult.Type.BLOCK) {
                System.out.println("[MC-Control] No block in sight to dig");
                return;
            }
            BlockHitResult blockHit = (BlockHitResult) hit;
            BlockPos targetPos = blockHit.getBlockPos();
            BlockState targetState = world.getBlockState(targetPos);
            if (targetState.isAir()) {
                System.out.println("[MC-Control] Target block is air");
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
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < timeoutMs) {
                    try { Thread.sleep(checkInterval); } catch (InterruptedException e) { break; }
                    if (world.getBlockState(targetPos).isAir()) {
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
                        return;
                    }
                }
                // 超时
                client.execute(() -> client.options.attackKey.setPressed(false));
                System.out.println("[MC-Control] Dig timed out");
            });
        }

    // === 安全向下挖 ===
    private static void digDown(ClientPlayerEntity player, int distance) {
        MinecraftClient client = MinecraftClient.getInstance();
        World world = player.getWorld();
        BlockPos pos = player.getBlockPos();

        for (int i = 0; i < distance; i++) {
            BlockPos below = pos.down(i + 1);
            BlockState state = world.getBlockState(below);
            String name = state.getBlock().getName().getString();

            // 危险检查
            if (name.toLowerCase().contains("lava") || name.toLowerCase().contains("water")) {
                System.out.println("[MC-Control] DigDown stopped: hazard '" + name + "' at " + below.toShortString());
                return;
            }
            if (state.isAir() && i > 0) {
                System.out.println("[MC-Control] DigDown stopped: void below at " + below.toShortString());
                return;
            }

            // 向下看并挖
            player.setPitch(90f);
            client.options.attackKey.setPressed(true);
            try { Thread.sleep(2500); } catch (InterruptedException e) { break; }
            client.options.attackKey.setPressed(false);

            // 潜行走到下一格
            client.options.sneakKey.setPressed(true);
            client.options.forwardKey.setPressed(true);
            try { Thread.sleep(300); } catch (InterruptedException e) { break; }
            client.options.forwardKey.setPressed(false);
            client.options.sneakKey.setPressed(false);
        }
        System.out.println("[MC-Control] DigDown done, " + distance + " blocks");
    }

    // === 回到地面 ===
    private static void goToSurface(ClientPlayerEntity player) {
        MinecraftClient client = MinecraftClient.getInstance();
        World world = player.getWorld();
        BlockPos pos = player.getBlockPos();

        // 找到头顶最高方块
        int surfaceY = pos.getY();
        for (int y = pos.getY(); y < 320; y++) {
            BlockPos check = new BlockPos(pos.getX(), y, pos.getZ());
            if (world.getBlockState(check).isAir()) {
                surfaceY = y;
                break;
            }
        }
        System.out.println("[MC-Control] Surface at y=" + surfaceY + ", current y=" + pos.getY());

        // 抬头向上，跳跃+放置方块垫脚
        player.setPitch(-90f);
        for (int y = pos.getY(); y < surfaceY; y++) {
            client.options.jumpKey.setPressed(true);
            try { Thread.sleep(200); } catch (InterruptedException e) { break; }
            client.options.jumpKey.setPressed(false);
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        System.out.println("[MC-Control] Reached surface");
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
                return;
            }
        }
        System.out.println("[MC-Control] Item '" + itemName + "' not found in inventory");
    }

    // === 吃/喝 ===
    private static void consumeItem(ClientPlayerEntity player, String itemName) {
        PlayerInventory inv = player.getInventory();
        int slot = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String name = stack.getItem().getName().getString();
            if (itemName.isEmpty() && stack.getItem().isFood()) {
                slot = i;
                break;
            }
            if (name.toLowerCase().contains(itemName.toLowerCase())) {
                slot = i;
                break;
            }
        }
        if (slot == -1) {
            System.out.println("[MC-Control] No food/item found");
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