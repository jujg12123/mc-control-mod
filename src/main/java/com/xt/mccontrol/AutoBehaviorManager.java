package com.xt.mccontrol;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.item.TridentItem;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tick-level automatic behavior manager (v1.3, Numen-inspired).
 *
 * <p>Priority-based reflexes (higher wins; only the highest active one runs):
 * <pre>MLG(10) &gt; breath(6) &gt; mob-defense(5) &gt; food-regen(4) &gt; hunger(3)
 *      &gt; unstuck(2) &gt; pickup(1) &gt; idle</pre>
 *
 * <p>During a long task (ActionExecutor busy) only life-threatening reflexes run:
 * MLG, breath, and an emergency escape when health &lt; 10.
 *
 * <p>Per-reflex toggles via setReflexEnabled: self_defense, eat, stuck, pickup,
 * mlg, breath. The master switch (enabled) still gates everything.
 */
public class AutoBehaviorManager {
    private static volatile boolean enabled = true;
    private static long lastTickTime = 0;
    private static final long TICK_INTERVAL_MS = 500;

    // ---- per-reflex toggles ----
    private static volatile boolean reflexSelfDefense = true;
    private static volatile boolean reflexEat = true;
    private static volatile boolean reflexStuck = true;
    private static volatile boolean reflexPickup = true;
    private static volatile boolean reflexMlg = true;
    private static volatile boolean reflexBreath = true;

    // ---- key-release bookkeeping ----
    private static long escapeActiveUntil = 0;
    private static long fleeActiveUntil = 0;
    private static long attackKeyPressedTime = 0;
    private static long useKeyPressedTime = 0;
    private static long mlgUseUntil = 0;         // MLG water release time
    private static long pickupForwardTime = 0;

    // ---- self-defense ----
    private static long lastAttackTime = 0;
    private static final long ATTACK_COOLDOWN_MS = 1000;
    // Numen thresholds: flee when health <= 8 or unarmed
    private static final float FLEE_HEALTH = 8.0f;

    // ---- eating ----
    private static long lastEatTime = 0;
    private static final long EAT_COOLDOWN_MS = 5000;
    private static final float REGEN_HEALTH = 12.0f;   // eat for regen when hurt
    private static final int REGEN_FOOD_LEVEL = 18;    // regen needs food >= 18
    private static final int HUNGRY_LEVEL = 6;         // cannot sprint below 7

    // ---- stuck detection (windowed) + wander burst ----
    private static double lastX = 0, lastY = 0, lastZ = 0;
    private static long lastMoveTime = 0;
    private static long lastJumpTime = 0;
    private static int jumpCount = 0;
    private static long jumpResetTime = 0;
    private static long lastWanderTime = 0;
    private static final long WANDER_COOLDOWN_MS = 15000;
    private static final long WANDER_DURATION_MS = 2000;
    private static long wanderActiveUntil = 0;
    private static double wanderStartX = 0, wanderStartZ = 0;
    private static float wanderYaw = 0;
    private static int wanderHopPhase = 0;

    // ---- MLG ----
    private static long mlgActiveUntil = 0;      // per-fall retrigger guard
    private static final float MLG_FALL_TRIGGER = 4.0f;

    // ---- breath ----
    private static final int LOW_AIR_TICKS = 240;
    private static boolean breathActive = false;

    // ---- pickup ----
    private static long lastPickupAttempt = 0;
    private static final long PICKUP_COOLDOWN_MS = 2000;

    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private AutoBehaviorManager() {
    }

    /** Called from the client tick. Runs at most once per TICK_INTERVAL_MS. */
    public static void tick(MinecraftClient client) {
        if (!enabled || client.player == null || client.world == null) return;

        long now = System.currentTimeMillis();

        // ---- unified stale-key release (before throttling) ----
        if (escapeActiveUntil > 0 && now > escapeActiveUntil) {
            client.options.forwardKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            escapeActiveUntil = 0;
        }
        if (fleeActiveUntil > 0 && now > fleeActiveUntil) {
            client.options.forwardKey.setPressed(false);
            fleeActiveUntil = 0;
        }
        if (attackKeyPressedTime > 0 && now - attackKeyPressedTime > 200) {
            client.options.attackKey.setPressed(false);
            attackKeyPressedTime = 0;
        }
        if (useKeyPressedTime > 0 && now - useKeyPressedTime > 2000) {
            client.options.useKey.setPressed(false);
            useKeyPressedTime = 0;
        }
        if (mlgUseUntil > 0 && now > mlgUseUntil) {
            client.options.useKey.setPressed(false);
            mlgUseUntil = 0;
        }
        if (pickupForwardTime > 0 && now - pickupForwardTime > 500) {
            client.options.forwardKey.setPressed(false);
            pickupForwardTime = 0;
        }

        if (now - lastTickTime < TICK_INTERVAL_MS) return;
        lastTickTime = now;

        ClientPlayerEntity player = client.player;
        World world = client.world;

        boolean inAction = ActionExecutor.isActionInProgress();

        // ---- life-threatening reflexes run regardless of long tasks ----
        if (reflexMlg && checkMlg(client, player, now)) return;
        if (reflexBreath && checkBreath(client, player)) return;

        if (inAction) {
            // during a long task: only an emergency escape (no key stealing)
            if (reflexSelfDefense && player.getHealth() < 10.0f) {
                checkSelfDefenseEscape(client, player, world, now);
            }
            return;
        }

        // ---- outside long tasks: full priority chain ----
        if (reflexSelfDefense && checkSelfDefense(client, player, world, now)) return;
        if (reflexEat && checkHunger(client, player, now)) return;
        if (reflexStuck && checkStuck(client, player, now)) return;
        if (reflexPickup && checkPickup(client, player, world, now)) return;
    }

    // ======================== MLG: water-bucket fall save (priority 10) ========================

    /**
     * If falling past 4 blocks with a water bucket available, place water below.
     * Once per fall (guarded by mlgActiveUntil).
     */
    private static boolean checkMlg(MinecraftClient client, ClientPlayerEntity player, long now) {
        if (player.isOnGround()) {
            mlgActiveUntil = 0;
            return false;
        }
        if (now < mlgActiveUntil) return false;              // already handled this fall
        if (player.fallDistance < MLG_FALL_TRIGGER) return false;

        int slot = findItemSlot(player, "water_bucket");
        if (slot < 0) {
            // remember the fall so we do not spam-scan every tick
            mlgActiveUntil = now + 1500;
            return false;
        }
        PlayerInventory inv = player.getInventory();
        if (slot < 9) {
            inv.selectedSlot = slot;
        } else {
            client.interactionManager.pickFromInventory(slot);
        }
        player.setPitch(90f);                                // look straight down
        client.options.useKey.setPressed(true);
        mlgUseUntil = now + 400;                             // short click
        mlgActiveUntil = now + 3000;                         // no retrigger this fall
        StateCollector.addBehaviorLog("MLG: falling " + String.format("%.1f", player.fallDistance)
                + " blocks, placing water bucket");
        return true;
    }

    // ======================== Breath: surface before drowning (priority 6) ========================

    /**
     * Head underwater with air below 240 ticks: swim up (jump in water ascends)
     * until air refills. Releases keys when no longer submerged.
     */
    private static boolean checkBreath(MinecraftClient client, ClientPlayerEntity player) {
        if (!player.isSubmergedInWater()) {
            if (breathActive) {
                client.options.jumpKey.setPressed(false);
                client.options.forwardKey.setPressed(false);
                breathActive = false;
                StateCollector.addBehaviorLog("breath: surfaced, air refilling");
            }
            return false;
        }
        if (player.getAir() > LOW_AIR_TICKS) {
            if (breathActive) {
                client.options.jumpKey.setPressed(false);
                client.options.forwardKey.setPressed(false);
                breathActive = false;
            }
            return false;
        }
        if (!breathActive) {
            breathActive = true;
            StateCollector.addBehaviorLog("breath: air low (" + player.getAir()
                    + "), swimming up");
        }
        client.options.jumpKey.setPressed(true);
        client.options.forwardKey.setPressed(true);
        player.setPitch(-80f);
        return true;
    }

    // ======================== 1. self defense (priority 5) ========================

    /**
     * Numen fight-vs-flee: flee when too hurt to trade blows (health <= 8) or
     * unarmed; otherwise attack hostiles within 4 blocks (1s cooldown).
     */
    private static boolean checkSelfDefense(MinecraftClient client, ClientPlayerEntity player,
                                            World world, long now) {
        Box searchBox = new Box(player.getX() - 8, player.getY() - 4, player.getZ() - 8,
                player.getX() + 8, player.getY() + 4, player.getZ() + 8);
        List<Entity> entities = world.getOtherEntities(player, searchBox,
                e -> e instanceof HostileEntity && e.isAlive());
        if (entities.isEmpty()) return false;

        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity e : entities) {
            double dist = e.squaredDistanceTo(player);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = e;
            }
        }
        if (nearest == null) return false;
        double dist = Math.sqrt(nearestDist);
        if (dist >= 8.0) return false;

        boolean armed = hasWeapon(player);
        if (player.getHealth() <= FLEE_HEALTH || !armed) {
            // flee away from the threat for 2s
            double dx = player.getX() - nearest.getX();
            double dz = player.getZ() - nearest.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            player.setYaw(yaw);
            client.options.forwardKey.setPressed(true);
            client.options.jumpKey.setPressed(true);
            fleeActiveUntil = now + 2000;
            StateCollector.addBehaviorLog("auto flee from " + nearest.getName().getString()
                    + (armed ? " (low health)" : " (unarmed)"));
            return true;
        }

        if (dist < 4.0 && now - lastAttackTime > ATTACK_COOLDOWN_MS) {
            double dx = nearest.getX() - player.getX();
            double dy = (nearest.getY() + nearest.getHeight() / 2)
                    - (player.getY() + player.getEyeHeight(player.getPose()));
            double dz = nearest.getZ() - player.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            player.setYaw(yaw);
            player.setPitch(pitch);
            client.options.attackKey.setPressed(true);
            attackKeyPressedTime = now;
            lastAttackTime = now;
            StateCollector.addBehaviorLog("auto attack " + nearest.getName().getString());
            return true;
        }
        return false;
    }

    /** Escape during a long task: run away (no attack key) when health is critical. */
    private static void checkSelfDefenseEscape(MinecraftClient client, ClientPlayerEntity player,
                                               World world, long now) {
        Box searchBox = new Box(player.getX() - 8, player.getY() - 4, player.getZ() - 8,
                player.getX() + 8, player.getY() + 4, player.getZ() + 8);
        List<Entity> entities = world.getOtherEntities(player, searchBox,
                e -> e instanceof HostileEntity && e.isAlive());
        if (entities.isEmpty()) return;

        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity e : entities) {
            double dist = e.squaredDistanceTo(player);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = e;
            }
        }
        if (nearest == null || Math.sqrt(nearestDist) >= 8.0) return;

        double dx = player.getX() - nearest.getX();
        double dz = player.getZ() - nearest.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.setYaw(yaw);
        client.options.forwardKey.setPressed(true);
        client.options.jumpKey.setPressed(true);
        escapeActiveUntil = now + 3000;
        StateCollector.addBehaviorLog("task escape from " + nearest.getName().getString());
    }

    /** A melee-capable item in hand/hotbar? (sword, axe, trident) */
    private static boolean hasWeapon(ClientPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            net.minecraft.item.Item item = stack.getItem();
            if (item instanceof SwordItem || item instanceof AxeItem || item instanceof TridentItem) {
                return true;
            }
        }
        return false;
    }

    // ======================== 2. eating (priority 4 regen / 3 hunger) ========================

    /**
     * Eat when hurt + food below regen threshold (priority 4) or genuinely
     * hungry (priority 3). 5s cooldown.
     */
    private static boolean checkHunger(MinecraftClient client, ClientPlayerEntity player, long now) {
        if (now - lastEatTime < EAT_COOLDOWN_MS) return false;

        float health = player.getHealth();
        int food = player.getHungerManager().getFoodLevel();
        boolean regenEat = health <= REGEN_HEALTH && food < REGEN_FOOD_LEVEL;
        boolean plainHungry = food <= HUNGRY_LEVEL;
        if (!regenEat && !plainHungry) return false;

        int foodSlot = findFoodSlot(player);
        if (foodSlot < 0) return false;

        if (foodSlot < 9) {
            player.getInventory().selectedSlot = foodSlot;
        } else {
            client.interactionManager.pickFromInventory(foodSlot);
        }
        client.options.useKey.setPressed(true);
        useKeyPressedTime = now;
        lastEatTime = now;
        StateCollector.addBehaviorLog("auto eat (health=" + String.format("%.0f", health)
                + ", food=" + food + ")");
        return true;
    }

    private static int findFoodSlot(ClientPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.getItem().isFood()) {
                return i;
            }
        }
        return -1;
    }

    // ======================== 3. stuck (priority 2) ========================

    /**
     * Windowed stuck detection (only while the player is trying to move), then:
     * up to 3 jump attempts, then a Numen-style wander burst (turn ~137 deg,
     * walk forward, periodic hops) to break out of geometry.
     */
    private static boolean checkStuck(MinecraftClient client, ClientPlayerEntity player, long now) {
        // ---- wander burst in progress ----
        if (now < wanderActiveUntil) {
            player.setYaw(wanderYaw);
            player.setPitch(0);
            client.options.forwardKey.setPressed(true);
            wanderHopPhase++;
            if (wanderHopPhase % 5 == 0) {
                client.options.jumpKey.setPressed(true);
                scheduler.schedule(() ->
                        client.execute(() -> client.options.jumpKey.setPressed(false)),
                        200, TimeUnit.MILLISECONDS);
            }
            double moved = Math.hypot(player.getX() - wanderStartX, player.getZ() - wanderStartZ);
            if (moved > 1.2 || now > wanderActiveUntil - 100) {
                // escaped or burst time out
                client.options.forwardKey.setPressed(false);
                client.options.jumpKey.setPressed(false);
                wanderActiveUntil = 0;
                lastWanderTime = now;
                resetStuckBaseline(player, now);
                StateCollector.addBehaviorLog(moved > 1.2
                        ? "unstuck: wander burst broke free (" + String.format("%.1f", moved) + " blocks)"
                        : "unstuck: wander burst ended");
            }
            return true;
        }

        // ---- detection: first call initializes baseline ----
        if (lastMoveTime == 0) {
            resetStuckBaseline(player, now);
            return false;
        }
        boolean tryingToMove = client.options.forwardKey.isPressed()
                || client.options.backKey.isPressed()
                || client.options.leftKey.isPressed()
                || client.options.rightKey.isPressed();
        if (!tryingToMove) {
            resetStuckBaseline(player, now);
            return false;
        }
        double moved = Math.sqrt(
                Math.pow(player.getX() - lastX, 2)
                        + Math.pow(player.getY() - lastY, 2)
                        + Math.pow(player.getZ() - lastZ, 2));
        if (moved > 0.5) {
            resetStuckBaseline(player, now);
            return false;
        }
        if (now - lastMoveTime <= 3000) return false;

        // ---- stuck for 3s while trying to move ----
        if (jumpResetTime == 0) jumpResetTime = now;
        if (now - jumpResetTime > 30000) {
            jumpCount = 0;
            jumpResetTime = now;
        }

        if (now - lastJumpTime > 8000 && jumpCount < 3) {
            client.options.jumpKey.setPressed(true);
            scheduler.schedule(() ->
                    client.execute(() -> client.options.jumpKey.setPressed(false)),
                    300, TimeUnit.MILLISECONDS);
            lastJumpTime = now;
            jumpCount++;
            resetStuckBaseline(player, now);
            StateCollector.addBehaviorLog("unstuck: jump attempt " + jumpCount + "/3");
            return true;
        }

        // ---- jumps exhausted: wander burst (cooldown-gated) ----
        if (now - lastWanderTime > WANDER_COOLDOWN_MS) {
            wanderYaw = player.getYaw() + 137.0f;
            wanderActiveUntil = now + WANDER_DURATION_MS;
            wanderStartX = player.getX();
            wanderStartZ = player.getZ();
            wanderHopPhase = 0;
            jumpCount = 0;   // allow jumps again after the burst
            jumpResetTime = now;
            StateCollector.addBehaviorLog("unstuck: 3 jumps failed, wander burst");
            return true;
        }
        resetStuckBaseline(player, now);
        return false;
    }

    private static void resetStuckBaseline(ClientPlayerEntity player, long now) {
        lastX = player.getX();
        lastY = player.getY();
        lastZ = player.getZ();
        lastMoveTime = now;
    }

    // ======================== 4. pickup (priority 1) ========================

    /**
     * Walk toward the nearest item drop within 5 blocks (2s cooldown).
     */
    private static boolean checkPickup(MinecraftClient client, ClientPlayerEntity player,
                                       World world, long now) {
        if (now - lastPickupAttempt < PICKUP_COOLDOWN_MS) return false;

        Box searchBox = new Box(player.getX() - 5, player.getY() - 2, player.getZ() - 5,
                player.getX() + 5, player.getY() + 2, player.getZ() + 5);
        List<Entity> items = world.getOtherEntities(player, searchBox,
                e -> e instanceof ItemEntity && e.isAlive());
        if (items.isEmpty()) return false;

        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity e : items) {
            double dist = e.squaredDistanceTo(player);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = e;
            }
        }
        if (nearest == null || Math.sqrt(nearestDist) < 1.5) return false;

        double dx = nearest.getX() - player.getX();
        double dz = nearest.getZ() - player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.setYaw(yaw);
        client.options.forwardKey.setPressed(true);
        pickupForwardTime = now;
        lastPickupAttempt = now;
        StateCollector.addBehaviorLog("auto pickup item");
        return true;
    }

    // ======================== helpers & public API ========================

    /** Find an inventory slot whose item id/name contains {@code name}. */
    private static int findItemSlot(ClientPlayerEntity player, String name) {
        PlayerInventory inv = player.getInventory();
        String needle = name.toLowerCase();
        String needleDisplay = needle.replace('_', ' ');
        for (int pass = 0; pass < 2; pass++) {
            int from = pass == 0 ? 0 : 9;
            int to = pass == 0 ? 9 : 36;
            for (int i = from; i < to; i++) {
                ItemStack stack = inv.getStack(i);
                if (stack.isEmpty()) continue;
                Identifier id = Registries.ITEM.getId(stack.getItem());
                String idPath = id != null ? id.getPath().toLowerCase() : "";
                String display = stack.getItem().getName().getString().toLowerCase();
                if (idPath.contains(needle) || display.contains(needleDisplay)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Master switch. */
    public static void setEnabled(boolean e) {
        enabled = e;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Toggle one reflex or the master switch.
     * Names: self_defense, eat, stuck, pickup, mlg, breath, all.
     * @return a human-readable result, or null for an unknown name.
     */
    public static String setReflexEnabled(String name, boolean enable) {
        String label = name;
        switch (name) {
            case "":
            case "all":
                enabled = enable;
                label = "all";
                break;
            case "self_defense":
                reflexSelfDefense = enable;
                break;
            case "eat":
                reflexEat = enable;
                break;
            case "stuck":
                reflexStuck = enable;
                break;
            case "pickup":
                reflexPickup = enable;
                break;
            case "mlg":
                reflexMlg = enable;
                break;
            case "breath":
                reflexBreath = enable;
                break;
            default:
                return null;
        }
        return "reflex " + label + " " + (enable ? "enabled" : "disabled");
    }

    public static void setNavigating(boolean n) {
        // deprecated: navigation state is managed by ActionExecutor.actionInProgress
    }

    public static boolean isNavigating() {
        return ActionExecutor.isActionInProgress();
    }
}
