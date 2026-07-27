package com.xt.mccontrol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

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

                // === 寻路：找到最近的目标方块，转向并走过去 ===
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
                case "stop_nav" -> {
                    releaseAllKeys(client);
                }

                default -> System.out.println(
                        "[MC-Control] Unknown action: " + action);
            }
        } catch (Exception e) {
            System.err.println("[MC-Control] Action failed: " + e.getMessage());
        }
    }

    // === 寻路：找到最近方块并转向走过去 ===
    private static void goToBlock(ClientPlayerEntity player, String blockType, double range) {
        MinecraftClient client = MinecraftClient.getInstance();
        World world = player.getWorld();
        BlockPos playerPos = player.getBlockPos();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        int r = (int) range;
        // 只搜索水平范围，y 轴 ±8
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -8; dy <= 8; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = playerPos.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) continue;
                    String name = state.getBlock().getName().getString();
                    if (name.toLowerCase().contains(blockType.toLowerCase())) {
                        double dist = playerPos.getSquaredDistance(pos);
                        if (dist < nearestDist && dist > 0.5) {
                            nearestDist = dist;
                            nearest = pos;
                        }
                    }
                }
            }
        }

        if (nearest == null) {
            System.out.println("[MC-Control] No block '" + blockType + "' found within " + range + " blocks");
            return;
        }

        System.out.println("[MC-Control] Found " + blockType + " at " + nearest.toShortString()
                + " (dist=" + String.format("%.1f", Math.sqrt(nearestDist)) + ")");
        goToPos(player, nearest.getX() + 0.5, nearest.getY(), nearest.getZ() + 0.5);
    }

    // === 转向目标并向前走一段 ===
    private static void goToPos(ClientPlayerEntity player, double tx, double ty, double tz) {
        MinecraftClient client = MinecraftClient.getInstance();

        double px = player.getX();
        double py = player.getY() + 0.5;
        double pz = player.getZ();

        double dx = tx - px;
        double dy = ty - py;
        double dz = tz - pz;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // 计算朝向
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));

        player.setYaw((float) yaw);
        player.setPitch((float) pitch);

        // 向前走，距离越远走越久，最多 3 秒
        double duration = Math.min(dist * 0.15, 3.0);
        if (duration < 0.3) duration = 0.3;

        client.options.forwardKey.setPressed(true);
        scheduler.schedule(() ->
                client.execute(() -> client.options.forwardKey.setPressed(false)),
                (long) (duration * 1000), TimeUnit.MILLISECONDS);

        System.out.println("[MC-Control] Moving toward target, dist="
                + String.format("%.1f", dist) + " duration=" + String.format("%.1f", duration) + "s");
    }

    private static void releaseAllKeys(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
    }

    private static void move(ClientPlayerEntity player,
                             String direction, double duration) {
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