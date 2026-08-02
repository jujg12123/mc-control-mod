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
 * 从 ActionExecutor 拆出的独立任务类（NavTask）。
 */
final class NavTask implements ActionTask {
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
        private final int maxTicks;
        private final long navTimeoutMs;        // 约 30 秒（20 TPS）
        private int strafeDir = 0;
        private int state = NAV;
        private int stateTicks = 0;
        private BlockPos targetObstacle = null;  // BREAKING 正在挖掘的方块
        private double bypassStartX = 0, bypassStartZ = 0;
        // A* 路径点（玩家当前层可行走路径），沿路径点走避免直线撞墙/掉坑
        private List<BlockPos> path = null;
        private int pathIdx = 0;
        private int replanTicks = 0;
        private double pillarTargetY = 0;    // PILLAR 目标脚部高度（0=用任务目标判定）
        private int pillarBaseY = 0;         // PILLAR 起点（玩家起始站立格）
        private int pillarLayers = 0;        // PILLAR 已叠的垫脚层数
        private boolean pillarPlaced = false;// PILLAR 当前跳跃周期是否已放置方块
        private int pillarFailTicks = 0;     // PILLAR 放置失败累计 tick
        private boolean sprintHeld = false;   // 疾跑键状态（每 tick 重算）
        private int offPathTicks = 0;         // 离当前路径点过远的连续 tick 数

        NavTask(ClientPlayerEntity player, double tx, double ty, double tz, boolean emitResult, String resultAction) {
            this.tx = tx; this.ty = ty; this.tz = tz;
            this.emitResult = emitResult;
            this.resultAction = resultAction;
            this.callId = currentCallId;
            this.myVersion = actionVersion;
            // distance-budgeted timeout (v1.3): 10s + 0.6s per block, cap 6 min
            double navDist = Math.max(Math.hypot(tx - player.getX(), tz - player.getZ()),
                    Math.abs(ty - player.getY()));
            this.maxTicks = (int) Math.min(7200, Math.max(600, 200 + navDist * 15));
            this.navTimeoutMs = Math.min(360000L,
                    Math.max(20000L, 10000L + (long) (navDist * 600L)));
            this.startTime = System.currentTimeMillis();
            this.lastCheckX = player.getX();
            this.lastCheckZ = player.getZ();
        }

        @Override
        public boolean tick(MinecraftClient client, ClientPlayerEntity player) {
            // 被新动作取消
            if (actionVersion != myVersion || navCancelled) {
                client.options.sprintKey.setPressed(false);
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
                client.options.sprintKey.setPressed(false);
                if (emitResult) sendResult(resultAction, callId, true, "已到达目标位置");
                return true;
            }
            if (totalTicks >= maxTicks || System.currentTimeMillis() - startTime > navTimeoutMs) {
                client.options.sprintKey.setPressed(false);
                if (emitResult) sendResult(resultAction, callId, false, "导航超时");
                return true;
            }

            // 目标在脚下（水平已贴住但垂直差较大）：直接向下挖穿，不再对着地面抽动视角
            if (hDist < 1.5 && ty < player.getY() - 1.2) {
                startDrillDown(client, player);
                return false;
            }
            // 目标太高且水平已贴近（够不着）：尝试垫脚（跳跃+脚下叠方块，逐层上升）
            double eyeDy = ty - (player.getY() + player.getEyeHeight(player.getPose()));
            if (eyeDy > 2.0 && hDist < 2.2) {
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
                // 路径点比玩家高 2 格以上（2 格台阶/陡坡）：原地垫脚叠一层再走
                double wpRise = wp.getY() - player.getY();
                double wpDist = Math.sqrt((wp.getX() + 0.5 - px) * (wp.getX() + 0.5 - px)
                        + (wp.getZ() + 0.5 - pz) * (wp.getZ() + 0.5 - pz));
                if (wpRise > 1.6 && wpDist < 2.5) {
                    startPillar(client, player, wp.getY());
                    return false;
                }
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

                // 脱轨看门狗（借鉴 Numen PathExecutor）：离当前路径点过远持续 1 秒则强制重规划
                if (path != null && pathIdx < path.size()) {
                    BlockPos wp = path.get(pathIdx);
                    double offDist = Math.abs(player.getX() - (wp.getX() + 0.5))
                            + Math.abs(player.getZ() - (wp.getZ() + 0.5));
                    if (offDist > 3.0) {
                        offPathTicks += 10;
                        if (offPathTicks >= 20) {
                            offPathTicks = 0;
                            path = null;
                            StateCollector.addBehaviorLog("寻路脱轨：离路径点过远，重新规划");
                        }
                    } else {
                        offPathTicks = 0;
                    }
                }

                if (stuckCount >= 2) {
                    // 卡住 ≥1 秒：路径失效，强制重新规划
                    path = null;
                    // 目标在脚下（被地面挡住走不动）：直接挖脚下
                    if (hDist < 1.5 && ty < player.getY() - 0.5) {
                        startDrillDown(client, player);
                        return false;
                    }
                    // 目标在上方且水平贴近：先垫脚爬升，而不是挖穿前方墙壁
                    if (ty > player.getY() + 2.2 && hDist < 3.0) {
                        startPillar(client, player);
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

            // ---- 疾跑决策（借鉴 Numen SprintPolicy：饥饿充足 + 前方净空 + 距离够）----
            // 需要跳台阶时不疾跑；卡住、距离太近、前方有实体墙都不疾跑
            boolean wantSprint = stuckCount == 0
                    && jumpCooldown <= 0
                    && Math.hypot(gx - px, gz - pz) > 2.5
                    && player.getHungerManager().getFoodLevel() > 6
                    && sprintPathClear(player, gx, gz);
            client.options.sprintKey.setPressed(wantSprint);
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
            client.options.sprintKey.setPressed(false);
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

        /** 垫脚子状态：目标太高够不着时，跳跃+在脚下垫方块逐层上升（1x1 竖井内也能用） */
        private boolean tickPillar(MinecraftClient client, ClientPlayerEntity player) {
            stateTicks++;
            double eyeY = player.getY() + player.getEyeHeight(player.getPose());
            double eyeDy = ty - eyeY;
            if (pillarTargetY > 0) {
                // 按脚部高度判定（路径点垫脚）：脚达到目标层即结束
                if (player.getY() >= pillarTargetY - 0.6) {
                    endPillar(client, player);
                    return false;
                }
            } else if (eyeDy <= 1.0) {
                // 按目标判定：目标已够得着，交给正常导航收尾
                endPillar(client, player);
                return false;
            }
            // 超时/跌落保护
            if (stateTicks > 260 || player.getY() < pillarBaseY - 1.0) {
                endPillar(client, player);
                StateCollector.addBehaviorLog("寻路垫脚超时，放弃垫脚继续前进");
                return false;
            }
            if (pillarLayers >= 8) {
                endPillar(client, player);
                StateCollector.addBehaviorLog("寻路垫脚已达 8 层上限，停止垫脚");
                return false;
            }
            // 背包垫脚方块
            int slot = findPillarSlot(client, player);
            if (slot < 0) {
                endPillar(client, player);
                StateCollector.addBehaviorLog("寻路垫脚: 背包没有可垫脚的方块，放弃垫脚");
                return false;
            }
            player.getInventory().selectedSlot = slot;
            World world = player.getWorld();
            int px = player.getBlockPos().getX();
            int pz = player.getBlockPos().getZ();
            int standY = pillarBaseY + pillarLayers;   // 玩家当前站立格
            // 头顶空间：站上新层后需要 standY+1、standY+2 两格空气
            if (!world.getBlockState(new BlockPos(px, standY + 1, pz)).isAir()
                    || !world.getBlockState(new BlockPos(px, standY + 2, pz)).isAir()) {
                endPillar(client, player);
                StateCollector.addBehaviorLog("寻路垫脚: 头顶空间不足，停止垫脚");
                return false;
            }
            // 持续跳跃；脚升过当前格后，点击脚下方块顶面垫一块（经典搭高法）
            client.options.jumpKey.setPressed(true);
            double rise = player.getY() - standY;
            if (!pillarPlaced && rise > 1.05) {
                BlockPos below = new BlockPos(px, standY - 1, pz);
                Vec3d hitPos = new Vec3d(below.getX() + 0.5, standY, below.getZ() + 0.5);
                BlockHitResult bhr = new BlockHitResult(hitPos, Direction.UP, below, false);
                ActionResult res = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, bhr);
                if (res.isAccepted()) {
                    pillarPlaced = true;
                    pillarLayers++;
                    pillarFailTicks = 0;
                    StateCollector.addBehaviorLog("寻路垫脚: 已垫 " + pillarLayers + " 层");
                } else if (++pillarFailTicks > 20) {
                    endPillar(client, player);
                    StateCollector.addBehaviorLog("寻路垫脚: 方块放置失败，放弃垫脚");
                    return false;
                }
            } else if (pillarPlaced && rise < 0.5) {
                // 已落到新层：允许下一轮起跳时再垫
                pillarPlaced = false;
            }
            return false;
        }

        private void startPillar(MinecraftClient client, ClientPlayerEntity player) {
            startPillar(client, player, 0);
        }

        /** 开始垫脚：targetY>0 时按脚部高度目标（路径点），否则按任务目标高度判定 */
        private void startPillar(MinecraftClient client, ClientPlayerEntity player, double targetY) {
            state = PILLAR;
            stateTicks = 0;
            pillarTargetY = targetY;
            pillarBaseY = (int) Math.floor(player.getY());
            pillarLayers = 0;
            pillarPlaced = false;
            pillarFailTicks = 0;
            client.options.attackKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
            client.options.sprintKey.setPressed(false);
            StateCollector.addBehaviorLog("寻路垫脚开始" + (targetY > 0 ? "（目标层 " + (int) targetY + "）" : ""));
        }

        private void endPillar(MinecraftClient client, ClientPlayerEntity player) {
            client.options.forwardKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            pillarTargetY = 0;
            pillarLayers = 0;
            pillarPlaced = false;
            pillarFailTicks = 0;
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
            client.options.sprintKey.setPressed(false);
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
            client.options.sprintKey.setPressed(false);
            StateCollector.addBehaviorLog("寻路中尝试绕行");
        }

        /** 疾跑净空检查：移动方向前方 2 格（脚部+头部）无实体墙才允许疾跑 */
        private boolean sprintPathClear(ClientPlayerEntity player, double gx, double gz) {
            double dx = gx - player.getX();
            double dz = gz - player.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-4) return false;
            double ux = dx / len;
            double uz = dz / len;
            BlockPos feet = player.getBlockPos();
            for (int d = 1; d <= 2; d++) {
                int bx = (int) Math.floor(feet.getX() + ux * d + 0.5);
                int bz = (int) Math.floor(feet.getZ() + uz * d + 0.5);
                BlockPos p = new BlockPos(bx, feet.getY(), bz);
                if (isSolid(player.getWorld(), p) || isSolid(player.getWorld(), p.up())) {
                    return false;
                }
            }
            return true;
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
