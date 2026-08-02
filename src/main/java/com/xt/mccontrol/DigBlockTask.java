package com.xt.mccontrol;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.concurrent.TimeUnit;

import static com.xt.mccontrol.ActionExecutor.*;

/**
 * Dig-block task (extracted from ActionExecutor + v1.3 enhancement):
 * - locks aim at the target block center every tick (no misses while sliding);
 * - if the target is occluded (ray hits another block first), digs the occluder
 *   first (containers/furniture are protected), then resumes the original target;
 * - short post-break cooldown so the same spot is not re-mined instantly;
 * - periodic tool re-check (auto-swap to a better tool picked up mid-dig).
 */
final class DigBlockTask implements ActionTask {
    private final long myVersion;
    private final long timeoutMs;
    private final long startTime;
    private final long callId = currentCallId;
    private BlockPos targetPos;         // block currently being dug (may be occluder)
    private BlockState targetBlockState;
    private String targetBlockName;
    private BlockPos originalTarget;    // original target, kept while in occluder mode
    private String originalName;
    private boolean initialized = false;
    private boolean occluderMode = false;
    private int hitDelay = 0;           // post-break cooldown ticks
    private int recheckTicks = 0;       // periodic tool/occluder re-check

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

        // post-break cooldown: avoid instantly re-mining the same spot
        if (hitDelay > 0) {
            hitDelay--;
            client.options.attackKey.setPressed(false);
            return false;
        }

        if (!initialized) {
            HitResult hit = player.raycast(5.0, 0, false);
            if (hit.getType() != HitResult.Type.BLOCK) {
                sendResult("dig_block", callId, false, "no block in sight");
                return true;
            }
            targetPos = ((BlockHitResult) hit).getBlockPos();
            BlockState s = player.getWorld().getBlockState(targetPos);
            if (s.isAir()) {
                sendResult("dig_block", callId, false, "target block is air");
                return true;
            }
            targetBlockState = s;
            targetBlockName = s.getBlock().getName().getString();
            originalTarget = targetPos;
            originalName = targetBlockName;
            System.out.println("[MC-Control] Digging " +
                    targetBlockName + " at " + targetPos.toShortString());
            initialized = true;
            ensureBestTool(player, s);
            client.options.attackKey.setPressed(true);
            return false;
        }

        BlockState currentState = player.getWorld().getBlockState(targetPos);
        if (currentState.isAir()) {
            client.options.attackKey.setPressed(false);
            if (occluderMode) {
                // occluder cleared: resume the original target
                occluderMode = false;
                hitDelay = 3;
                BlockState origState = player.getWorld().getBlockState(originalTarget);
                if (origState.isAir()) {
                    // original broke too on the way
                    scheduler.schedule(() ->
                            client.execute(() -> {
                                client.options.forwardKey.setPressed(true);
                                scheduler.schedule(() ->
                                        client.execute(() -> client.options.forwardKey.setPressed(false)),
                                        500, TimeUnit.MILLISECONDS);
                            }), 300, TimeUnit.MILLISECONDS);
                    sendResult("dig_block", callId, true, "broken " + originalName);
                    return true;
                }
                targetPos = originalTarget;
                targetBlockState = origState;
                targetBlockName = originalName;
                StateCollector.addBehaviorLog("dig: occluder cleared, back to original target");
                ensureBestTool(player, origState);
                client.options.attackKey.setPressed(true);
                return false;
            }
            // original target broken: brief forward walk to grab drops
            scheduler.schedule(() ->
                    client.execute(() -> {
                        client.options.forwardKey.setPressed(true);
                        scheduler.schedule(() ->
                                client.execute(() -> client.options.forwardKey.setPressed(false)),
                                500, TimeUnit.MILLISECONDS);
                    }), 300, TimeUnit.MILLISECONDS);
            sendResult("dig_block", callId, true, "broken " + targetBlockName);
            return true;
        }

        // type verification (original-target mode only): type changed => dug wrong block
        if (!occluderMode && currentState.getBlock() != targetBlockState.getBlock()) {
            client.options.attackKey.setPressed(false);
            String actualName = currentState.getBlock().getName().getString();
            sendResult("dig_block", callId, false,
                "target block type changed: was " + targetBlockName + ", now " + actualName
                + ". Maybe dug the wrong block, re-aim and retry.");
            return true;
        }

        // periodic re-check (every 3s): tool swap + occluder detection
        recheckTicks++;
        if (recheckTicks >= 60) {
            recheckTicks = 0;
            ensureBestTool(player, player.getWorld().getBlockState(targetPos));
            if (!occluderMode) {
                HitResult ray = player.raycast(5.0, 0, false);
                if (ray.getType() == HitResult.Type.BLOCK) {
                    BlockPos hitPos = ((BlockHitResult) ray).getBlockPos();
                    if (!hitPos.equals(targetPos) && !hitPos.equals(originalTarget)) {
                        BlockState ocState = player.getWorld().getBlockState(hitPos);
                        if (!ocState.isAir() && isContainerBlock(ocState)) {
                            // container in the way: do not break it, ask for a new angle
                            client.options.attackKey.setPressed(false);
                            sendResult("dig_block", callId, false,
                                    "blocked by " + ocState.getBlock().getName().getString()
                                    + " (container-like, not auto-broken). Move or change angle.");
                            return true;
                        }
                        if (!ocState.isAir()) {
                            // dig the occluder first
                            occluderMode = true;
                            targetPos = hitPos;
                            targetBlockState = ocState;
                            targetBlockName = ocState.getBlock().getName().getString();
                            StateCollector.addBehaviorLog("dig: target occluded by "
                                    + targetBlockName + ", digging occluder first");
                            ensureBestTool(player, ocState);
                        }
                    }
                }
            }
        }

        // lock aim at target center every tick
        double dx = targetPos.getX() + 0.5 - player.getX();
        double dy = targetPos.getY() + 0.5
                - (player.getY() + player.getEyeHeight(player.getPose()));
        double dz = targetPos.getZ() + 0.5 - player.getZ();
        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setPitch((float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        client.options.attackKey.setPressed(true);

        if (System.currentTimeMillis() - startTime > timeoutMs) {
            client.options.attackKey.setPressed(false);
            sendResult("dig_block", callId, false, "dig timeout (maybe wrong tool or unbreakable)");
            return true;
        }
        return false;
    }

    /** Container/furniture blocks are protected when they occlude the target. */
    private static boolean isContainerBlock(BlockState state) {
        if (state.isAir()) return false;
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        String idStr = id != null ? id.getPath() : "";
        for (String k : new String[]{"chest", "barrel", "shulker", "furnace",
                "crafting_table", "anvil", "enchanting", "bed", "door", "brewing"}) {
            if (idStr.contains(k)) return true;
        }
        return false;
    }
}
