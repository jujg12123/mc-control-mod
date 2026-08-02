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
 * 从 ActionExecutor 拆出的独立任务类（GoToSurfaceTask）。
 */
final class GoToSurfaceTask implements ActionTask {
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
        private int jumpTimer = 0;            // 按住跳跃的剩余 tick（跳满 0.4 秒才能爬 1 格）

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
            if (jumpTimer > 0) {
                // 跳跃计时：挖穿后保持跳跃 8 tick（0.4 秒），确保能跳上 1 格
                jumpTimer--;
                client.options.jumpKey.setPressed(true);
                if (jumpTimer == 0) {
                    client.options.jumpKey.setPressed(false);
                }
                return false;
            }
            client.options.jumpKey.setPressed(false);
            World world = player.getWorld();
            player.setPitch(-90f);
            // 找头顶可达范围内最近的实心方块（+1..+3）：一次挖穿 2-3 格高的间隙，
            // 避免“头顶是空气但上面还有方块”时原地空跳永远上不去
            BlockPos upDigTarget = null;
            for (int k = 1; k <= 3; k++) {
                BlockPos cand = pos.up(k);
                if (world.getBlockState(cand).isAir()) continue;
                double eyeY = player.getY() + player.getEyeHeight(player.getPose());
                if (Math.abs(cand.getY() + 0.5 - eyeY) <= 4.2) {
                    upDigTarget = cand;
                }
                break;
            }
            if (upDigTarget != null) {
                // 瞄准头顶方块挖掘（自动切工具）；挖穿后跳 8 tick 上去
                BlockState targetState = world.getBlockState(upDigTarget);
                ensureBestTool(player, targetState);
                client.options.attackKey.setPressed(true);
                double ddx = upDigTarget.getX() + 0.5 - player.getX();
                double ddy = upDigTarget.getY() + 0.5
                        - (player.getY() + player.getEyeHeight(player.getPose()));
                double ddz = upDigTarget.getZ() + 0.5 - player.getZ();
                player.setYaw((float) Math.toDegrees(Math.atan2(-ddx, ddz)));
                player.setPitch((float) Math.toDegrees(-Math.atan2(ddy, Math.sqrt(ddx * ddx + ddz * ddz))));
                // 检测是否挖不动（基岩/黑曜石等）
                if (upDigTarget.equals(lastDigPos)) {
                    digStuckTicks++;
                } else {
                    lastDigPos = upDigTarget;
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
                // 头顶 +1..+3 全部挖空才起跳：只挖 1 格就跳会被上面的方块卡住（竖井爬升至少需要 2 格高）
                boolean clearAbove = true;
                for (int k = 1; k <= 3; k++) {
                    if (!world.getBlockState(pos.up(k)).isAir()) {
                        clearAbove = false;
                        break;
                    }
                }
                if (clearAbove) {
                    client.options.attackKey.setPressed(false);
                    jumpTimer = 8;
                }
            } else {
                // 头顶 3 格内没有方块：起跳上 1 格（跳 8 tick 后松开）
                client.options.attackKey.setPressed(false);
                jumpTimer = 8;
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
