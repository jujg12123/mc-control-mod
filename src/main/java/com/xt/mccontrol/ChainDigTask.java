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

import static com.xt.mccontrol.ActionExecutor.*;

/**
 * 从 ActionExecutor 拆出的独立任务类（ChainDigTask）。
 */
final class ChainDigTask implements ActionTask {
        private static final int FIND = 0, WALK = 1, MINE = 2, PILLAR = 3;
        private final long myVersion;
        private final long callId = currentCallId;
        private final long startTime;
        private final long timeoutMs = 90000; // 90 秒
        private int state = FIND;
        private int stateTicks = 0;
        private BlockPos target = null;
        private BlockPos pillarBase = null;  // 垫脚起点（记录玩家起始站立格）
        private boolean pillarPlaced = false;// 当前跳跃周期是否已放置方块
        private int pillarFailTicks = 0;     // 放置失败累计 tick
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
                        pillarBase = null;
                        pillarPlaced = false;
                        pillarFailTicks = 0;
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
                default: { // PILLAR：垫脚（跳跃+脚下叠方块逐层上升，1x1 竖井内也能用）
                    stateTicks++;
                    if (pillarBase == null) {
                        // 记录起点后下一 tick 再开始垫
                        pillarBase = player.getBlockPos();
                        stateTicks = 0;
                        return false;
                    }
                    double feetY = player.getY();
                    double eyeDy = target.getY() + 0.5
                            - (feetY + player.getEyeHeight(player.getPose()));
                    double hDist = Math.sqrt(
                            (target.getX() + 0.5 - player.getX()) * (target.getX() + 0.5 - player.getX())
                                    + (target.getZ() + 0.5 - player.getZ()) * (target.getZ() + 0.5 - player.getZ()));
                    // 够得着了：回去挖（太远则先走两步）
                    if (Math.abs(eyeDy) <= 3.5 || stateTicks > 240) {
                        releaseAllKeys(client);
                        pillarBase = null;
                        pillarPlaced = false;
                        pillarFailTicks = 0;
                        stateTicks = 0;
                        if (Math.abs(eyeDy) <= 3.5) {
                            state = (hDist <= 4.5) ? MINE : WALK;
                        } else {
                            markedBlocks.remove(target);
                            skipped++;
                            StateCollector.addBehaviorLog("连续挖掘: 垫脚超时，跳过高处目标");
                            state = FIND;
                        }
                        stuckCount = 0;
                        return false;
                    }
                    int baseY = pillarBase.getY();
                    int standY = (int) Math.floor(feetY);
                    if (standY - baseY >= 8) {
                        // 已垫 8 层仍够不着：跳过该目标
                        releaseAllKeys(client);
                        pillarBase = null;
                        pillarPlaced = false;
                        pillarFailTicks = 0;
                        markedBlocks.remove(target);
                        skipped++;
                        StateCollector.addBehaviorLog("连续挖掘: 垫脚已达 8 层上限，跳过高处目标");
                        state = FIND;
                        stateTicks = 0;
                        return false;
                    }
                    int slot = findPillarSlot(client, player);
                    if (slot < 0) {
                        releaseAllKeys(client);
                        pillarBase = null;
                        markedBlocks.remove(target);
                        skipped++;
                        StateCollector.addBehaviorLog("连续挖掘: 背包没有可垫脚的方块，跳过高处目标");
                        state = FIND;
                        stateTicks = 0;
                        return false;
                    }
                    player.getInventory().selectedSlot = slot;
                    World world = player.getWorld();
                    int px = player.getBlockPos().getX();
                    int pz = player.getBlockPos().getZ();
                    // 头顶空间：站上新层后需要 standY+1、standY+2 两格空气
                    if (!world.getBlockState(new BlockPos(px, standY + 1, pz)).isAir()
                            || !world.getBlockState(new BlockPos(px, standY + 2, pz)).isAir()) {
                        releaseAllKeys(client);
                        pillarBase = null;
                        pillarPlaced = false;
                        pillarFailTicks = 0;
                        markedBlocks.remove(target);
                        skipped++;
                        StateCollector.addBehaviorLog("连续挖掘: 头顶空间不足，跳过高处目标");
                        state = FIND;
                        stateTicks = 0;
                        return false;
                    }
                    // 持续跳跃；脚升过当前格后，点击脚下方块顶面垫一块（经典搭高法）
                    client.options.jumpKey.setPressed(true);
                    double rise = feetY - standY;
                    if (!pillarPlaced && rise > 1.05) {
                        BlockPos below = new BlockPos(px, standY - 1, pz);
                        Vec3d hitPos = new Vec3d(below.getX() + 0.5, standY, below.getZ() + 0.5);
                        BlockHitResult bhr = new BlockHitResult(hitPos, Direction.UP, below, false);
                        ActionResult res = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, bhr);
                        if (res.isAccepted()) {
                            pillarPlaced = true;
                            pillarFailTicks = 0;
                            StateCollector.addBehaviorLog("连续挖掘: 垫脚 +1 层");
                        } else if (++pillarFailTicks > 20) {
                            releaseAllKeys(client);
                            pillarBase = null;
                            pillarPlaced = false;
                            pillarFailTicks = 0;
                            markedBlocks.remove(target);
                            skipped++;
                            StateCollector.addBehaviorLog("连续挖掘: 垫脚方块放置失败，跳过高处目标");
                            state = FIND;
                            stateTicks = 0;
                            return false;
                        }
                    } else if (pillarPlaced && rise < 0.5) {
                        // 已落到新层：允许下一轮起跳时再垫
                        pillarPlaced = false;
                    }
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
