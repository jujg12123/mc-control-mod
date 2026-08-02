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
 * 从 ActionExecutor 拆出的独立任务类（PlaceTask）。
 */
final class PlaceTask implements ActionTask {
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
