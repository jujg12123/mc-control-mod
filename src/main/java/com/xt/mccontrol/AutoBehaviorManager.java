package com.xt.mccontrol;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tick 级自动行为管理器。
 * <p>
 * 参考 Mindcraft 项目的 Modes 设计，处理 AI 不必关心的生存逻辑：
 * 自卫、防饥饿、防卡、拾取。所有行为按优先级在客户端主线程（tick 回调）中执行。
 * <p>
 * 行为触发时通过 {@link StateCollector#addBehaviorLog(String)} 记录日志。
 */
public class AutoBehaviorManager {
    private static volatile boolean enabled = true;
    private static long lastTickTime = 0;
    private static final long TICK_INTERVAL_MS = 500; // 每 500ms 检查一次

    // 防卡检测状态
    private static double lastX = 0, lastY = 0, lastZ = 0;
    private static long lastMoveTime = 0;
    private static volatile boolean isNavigating = false; // 由 ActionExecutor 设置
    private static long lastJumpTime = 0;
    private static int jumpCount = 0;
    private static long jumpResetTime = 0;

    // 自卫状态
    private static long lastAttackTime = 0;
    private static final long ATTACK_COOLDOWN_MS = 1000;
    private static long attackKeyPressedTime = 0; // 攻击键按下时间，用于延迟释放

    // 进食状态
    private static long lastEatTime = 0;
    private static final long EAT_COOLDOWN_MS = 5000;
    private static long useKeyPressedTime = 0; // 使用键按下时间，用于延迟释放

    // 拾取状态
    private static long lastPickupAttempt = 0;
    private static final long PICKUP_COOLDOWN_MS = 2000;
    private static long pickupForwardTime = 0; // 前进键按下时间，用于延迟释放

    // 用于延迟释放按键的调度器（与 ActionExecutor 保持一致的回调模式）
    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private AutoBehaviorManager() {
    }

    /**
     * 由客户端 tick 回调驱动。每 {@link #TICK_INTERVAL_MS} 毫秒执行一次各行为检查。
     */
    public static void tick(MinecraftClient client) {
        if (!enabled || client.player == null || client.world == null) return;

        long now = System.currentTimeMillis();

        // 统一检查需要释放的按键（在节流之前，确保及时释放）
        if (attackKeyPressedTime > 0 && now - attackKeyPressedTime > 200) {
            client.options.attackKey.setPressed(false);
            attackKeyPressedTime = 0;
        }
        if (useKeyPressedTime > 0 && now - useKeyPressedTime > 2000) {
            client.options.useKey.setPressed(false);
            useKeyPressedTime = 0;
        }
        if (pickupForwardTime > 0 && now - pickupForwardTime > 500) {
            client.options.forwardKey.setPressed(false);
            pickupForwardTime = 0;
        }

        if (now - lastTickTime < TICK_INTERVAL_MS) return;
        lastTickTime = now;

        ClientPlayerEntity player = client.player;
        World world = client.world;

        // 按优先级执行行为
        checkSelfDefense(client, player, world, now); // 最高优先级：保命
        checkHunger(client, player, now);             // 高优先级：维持生存
        checkStuck(client, player, now);              // 中优先级：防止卡住
        checkPickup(client, player, world, now);      // 低优先级：拾取物品
    }

    // ======================== 1. 自卫模式 ========================

    /**
     * 检测玩家 8 格范围内的敌对实体。
     * - 4 格内：自动转向并攻击（攻击冷却 1 秒）。
     * - 4~8 格且生命值 < 10：朝反方向逃跑。
     */
    private static void checkSelfDefense(MinecraftClient client, ClientPlayerEntity player, World world, long now) {
        if (isNavigating && player.getHealth() >= 10.0f) return; // 导航中且生命安全时不干预
        Box searchBox = new Box(player.getX() - 8, player.getY() - 4, player.getZ() - 8,
                player.getX() + 8, player.getY() + 4, player.getZ() + 8);
        List<Entity> entities = world.getOtherEntities(player, searchBox,
                e -> e instanceof HostileEntity && e.isAlive());

        if (entities.isEmpty()) return;

        // 找最近的敌对实体
        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity e : entities) {
            double dist = e.squaredDistanceTo(player);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = e;
            }
        }

        if (nearest == null) return;

        double dist = Math.sqrt(nearestDist);

        if (dist < 4.0 && now - lastAttackTime > ATTACK_COOLDOWN_MS) {
            // 攻击：转向实体并攻击
            double dx = nearest.getX() - player.getX();
            // 用与 ActionExecutor.attackEntity 一致的眼高计算方式（兼容 1.20.1）
            double dy = (nearest.getY() + nearest.getHeight() / 2)
                    - (player.getY() + player.getEyeHeight(player.getPose()));
            double dz = nearest.getZ() - player.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            player.setYaw(yaw);
            player.setPitch(pitch);

            // 按下攻击键，由 tick() 统一在 200ms 后释放
            client.options.attackKey.setPressed(true);
            attackKeyPressedTime = now;
            lastAttackTime = now;
            StateCollector.addBehaviorLog("自动攻击了 " + nearest.getName().getString());
        } else if (dist < 8.0 && player.getHealth() < 10.0f) {
            // 逃跑：朝远离实体的方向移动
            double dx = player.getX() - nearest.getX();
            double dz = player.getZ() - nearest.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            player.setYaw(yaw);
            client.options.forwardKey.setPressed(true);
            StateCollector.addBehaviorLog("自动逃离 " + nearest.getName().getString());
        }
    }

    // ======================== 2. 防饥饿模式 ========================

    /**
     * 当生命值 < 15 且饥饿值 < 15 时，在背包中查找食物并进食。
     * 进食冷却 5 秒。
     */
    private static void checkHunger(MinecraftClient client, ClientPlayerEntity player, long now) {
        if (isNavigating) return; // 导航中不进食
        if (now - lastEatTime < EAT_COOLDOWN_MS) return;

        if (player.getHealth() >= 15.0f || player.getHungerManager().getFoodLevel() >= 15) return;

        // 在背包中查找食物
        int foodSlot = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem().isFood()) {
                foodSlot = i;
                break;
            }
        }

        if (foodSlot == -1) return;

        // 切换到该槽位
        if (foodSlot < 9) {
            player.getInventory().selectedSlot = foodSlot;
        } else {
            client.interactionManager.pickFromInventory(foodSlot);
        }

        // 按住 useKey 2 秒吃食物，由 tick() 统一释放
        client.options.useKey.setPressed(true);
        useKeyPressedTime = now;
        lastEatTime = now;
        StateCollector.addBehaviorLog("自动进食补充体力");
    }

    // ======================== 3. 防卡模式 ========================

    /**
     * 当不在寻路中（isNavigating == false）时，检测玩家是否卡住。
     * 仅当玩家有移动意图（按下方向键）但 3 秒内移动距离 < 0.5 格时，才尝试跳跃脱困。
     * 跳跃冷却 8 秒，30 秒内最多跳跃 3 次，超过则记录失败日志。
     */
    private static void checkStuck(MinecraftClient client, ClientPlayerEntity player, long now) {
        if (isNavigating) return; // 寻路中不干预

        // 首次调用时初始化基准位置
        if (lastMoveTime == 0) {
            lastX = player.getX();
            lastY = player.getY();
            lastZ = player.getZ();
            lastMoveTime = now;
            return;
        }

        // 检查移动意图：只有玩家正在尝试移动时才判定为卡住
        boolean tryingToMove = client.options.forwardKey.isPressed()
                || client.options.backKey.isPressed()
                || client.options.leftKey.isPressed()
                || client.options.rightKey.isPressed();
        if (!tryingToMove) {
            // 没有移动意图，重置基准位置但不触发跳跃
            lastX = player.getX();
            lastY = player.getY();
            lastZ = player.getZ();
            lastMoveTime = now;
            return;
        }

        double dx = player.getX() - lastX;
        double dy = player.getY() - lastY;
        double dz = player.getZ() - lastZ;
        double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (moved > 0.5) {
            // 移动距离足够，重置基准
            lastX = player.getX();
            lastY = player.getY();
            lastZ = player.getZ();
            lastMoveTime = now;
            return;
        }

        // 移动距离 < 0.5 格，检查是否已持续 3 秒
        if (now - lastMoveTime > 3000) {
            // 30 秒内跳跃次数重置
            if (jumpResetTime == 0) {
                jumpResetTime = now;
            }
            if (now - jumpResetTime > 30000) {
                jumpCount = 0;
                jumpResetTime = now;
            }

            // 检查冷却时间（8 秒）和跳跃次数上限（3 次）
            if (now - lastJumpTime > 8000 && jumpCount < 3) {
                // 尝试跳跃一次脱困
                client.options.jumpKey.setPressed(true);
                scheduler.schedule(() ->
                        client.execute(() -> client.options.jumpKey.setPressed(false)),
                        300, TimeUnit.MILLISECONDS);
                lastJumpTime = now;
                jumpCount++;
                StateCollector.addBehaviorLog("自动跳跃尝试脱困");

                if (jumpCount >= 3) {
                    StateCollector.addBehaviorLog("防卡失败，连续跳跃3次仍未脱困");
                }
            }

            // 重置计时，避免连续触发
            lastX = player.getX();
            lastY = player.getY();
            lastZ = player.getZ();
            lastMoveTime = now;
        }
    }

    // ======================== 4. 拾取模式 ========================

    /**
     * 检测玩家 5 格范围内的掉落物（ItemEntity）。
     * 若存在且距离 > 1.5 格，朝最近掉落物方向短暂移动。
     * 拾取冷却 2 秒。
     */
    private static void checkPickup(MinecraftClient client, ClientPlayerEntity player, World world, long now) {
        if (isNavigating) return; // 导航中不拾取
        if (now - lastPickupAttempt < PICKUP_COOLDOWN_MS) return;

        Box searchBox = new Box(player.getX() - 5, player.getY() - 2, player.getZ() - 5,
                player.getX() + 5, player.getY() + 2, player.getZ() + 5);
        List<Entity> items = world.getOtherEntities(player, searchBox,
                e -> e instanceof ItemEntity && e.isAlive());

        if (items.isEmpty()) return;

        // 找最近的掉落物
        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity e : items) {
            double dist = e.squaredDistanceTo(player);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = e;
            }
        }

        if (nearest == null) return;

        // 已经非常近（< 1.5 格），物品会被自动吸取，无需主动移动
        if (Math.sqrt(nearestDist) < 1.5) return;

        // 朝最近的掉落物方向短暂移动
        double dx = nearest.getX() - player.getX();
        double dz = nearest.getZ() - player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.setYaw(yaw);
        client.options.forwardKey.setPressed(true);
        // 500ms 后释放前进键，由 tick() 统一释放
        pickupForwardTime = now;
        lastPickupAttempt = now;
        StateCollector.addBehaviorLog("自动拾取掉落物");
    }

    // ======================== 公开接口 ========================

    public static void setEnabled(boolean e) {
        enabled = e;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setNavigating(boolean n) {
        isNavigating = n;
    }
}
