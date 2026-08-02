package com.xt.mccontrol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.xt.mccontrol.ActionExecutor.*;

/**
 * Batch build task (v1.3, inspired by Numen BuildTool).
 *
 * <p>Ops: set / box / walls / line / clear (block_id = air).
 * <ul>
 *   <li>Whole-job material pre-check: refuses to start when ANY material is short;</li>
 *   <li>cells are built ground-up (Y asc, then nearest first);</li>
 *   <li>each placement is verified (block actually appears) with 3 retries;</li>
 *   <li>clear cells dig the block (containers protected);</li>
 *   <li>per-cell 30s timeout, overall timeout = 30s + 6s/cell (capped 10 min).</li>
 * </ul>
 */
final class BuildTask implements ActionTask {
    private static final int MAX_CELLS = 16384;
    private static final int PER_CELL_TIMEOUT_MS = 30000;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_SKIPS = 24;

    private final long myVersion;
    private final long callId = currentCallId;
    private final long startTime;
    private final long timeoutMs;
    private final List<Op> ops;

    private final ArrayDeque<Cell> cells = new ArrayDeque<>();
    private final Map<String, Integer> needMaterials = new HashMap<>();
    private int totalCells = 0;
    private int built = 0, cleared = 0, skipped = 0;

    // current cell execution state
    private Cell cur = null;
    private int phase = 0;          // 0=WALK 1=PLACE 2=VERIFY 3=DIG
    private int phaseTicks = 0;
    private int attempts = 0;
    private BlockPos curAnchor = null;
    private Direction curFace = Direction.UP;
    private BlockPos curSpot = null;
    private long cellStart = 0;
    private int hitDelay = 0;

    private static final int[][] ADJ4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** One parsed build op. */
    private static final class Op {
        final String op;        // set/box/walls/line/clear
        final String blockId;   // normalized id (no namespace) or "" for clear
        final boolean hollow;
        final int x1, y1, z1, x2, y2, z2;
        Op(String op, String blockId, boolean hollow,
           int x1, int y1, int z1, int x2, int y2, int z2) {
            this.op = op;
            this.blockId = blockId;
            this.hollow = hollow;
            this.x1 = x1; this.y1 = y1; this.z1 = z1;
            this.x2 = x2; this.y2 = y2; this.z2 = z2;
        }
    }

    /** One target cell. */
    private static final class Cell {
        final BlockPos pos;
        final String blockId;   // "" for clear
        final boolean clear;
        Cell(BlockPos pos, String blockId, boolean clear) {
            this.pos = pos;
            this.blockId = blockId;
            this.clear = clear;
        }
        @Override
        public boolean equals(Object o) {
            return o instanceof Cell c && pos.equals(c.pos);
        }
        @Override
        public int hashCode() {
            return pos.hashCode();
        }
    }

    /** Parse ops from the action JSON. Returns null on any parse error. */
    static BuildTask fromJson(JsonArray ops) {
        try {
            List<Op> parsed = new ArrayList<>();
            for (JsonElement e : ops) {
                JsonObject o = e.getAsJsonObject();
                String op = o.get("op").getAsString();
                if (op == null) return null;
                String blockId = o.has("block_id") ? o.get("block_id").getAsString() : "";
                boolean hollow = o.has("hollow") && o.get("hollow").getAsBoolean();
                int x1 = o.has("x") ? o.get("x").getAsInt() : o.get("x1").getAsInt();
                int y1 = o.has("y") ? o.get("y").getAsInt() : o.get("y1").getAsInt();
                int z1 = o.has("z") ? o.get("z").getAsInt() : o.get("z1").getAsInt();
                int x2 = o.has("x2") ? o.get("x2").getAsInt() : x1;
                int y2 = o.has("y2") ? o.get("y2").getAsInt() : y1;
                int z2 = o.has("z2") ? o.get("z2").getAsInt() : z1;
                switch (op) {
                    case "set":
                    case "box":
                    case "walls":
                    case "line":
                    case "clear":
                        parsed.add(new Op(op, normalizeId(blockId), hollow,
                                x1, y1, z1, x2, y2, z2));
                        break;
                    default:
                        return null;
                }
            }
            if (parsed.isEmpty()) return null;
            return new BuildTask(parsed);
        } catch (Exception ex) {
            return null;
        }
    }

    private BuildTask(List<Op> ops) {
        this.ops = ops;
        this.myVersion = actionVersion;
        this.startTime = System.currentTimeMillis();
        // expand ops into cells
        LinkedHashSet<Cell> all = new LinkedHashSet<>();
        for (Op op : ops) {
            expandOp(op, all);
        }
        totalCells = all.size();
        // overall timeout: 30s + 6s per cell, capped at 10 minutes
        this.timeoutMs = Math.min(600000L, 30000L + (long) totalCells * 6000L);
    }

    private static void expandOp(Op op, Set<Cell> out) {
        boolean clear = op.op.equals("clear") || op.blockId.isEmpty()
                || op.blockId.equals("air");
        String bid = clear ? "" : op.blockId;
        int minX = Math.min(op.x1, op.x2), maxX = Math.max(op.x1, op.x2);
        int minY = Math.min(op.y1, op.y2), maxY = Math.max(op.y1, op.y2);
        int minZ = Math.min(op.z1, op.z2), maxZ = Math.max(op.z1, op.z2);
        if (maxX - minX + 1 > 256 || maxY - minY + 1 > 256 || maxZ - minZ + 1 > 256) return;
        if (out.size() >= MAX_CELLS) return;

        switch (op.op) {
            case "set", "clear" -> out.add(new Cell(new BlockPos(minX, minY, minZ), bid, clear));
            case "line" -> {
                int dx = op.x2 - op.x1, dy = op.y2 - op.y1, dz = op.z2 - op.z1;
                int steps = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
                if (steps == 0) {
                    out.add(new Cell(new BlockPos(op.x1, op.y1, op.z1), bid, clear));
                    return;
                }
                for (int i = 0; i <= steps; i++) {
                    double t = (double) i / steps;
                    int x = (int) Math.round(op.x1 + dx * t);
                    int y = (int) Math.round(op.y1 + dy * t);
                    int z = (int) Math.round(op.z1 + dz * t);
                    out.add(new Cell(new BlockPos(x, y, z), bid, clear));
                }
            }
            case "box", "walls" -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            boolean shell = x == minX || x == maxX
                                    || y == minY || y == maxY
                                    || z == minZ || z == maxZ;
                            if (op.op.equals("walls")) {
                                // perimeter ring only: no top/bottom faces
                                shell = (x == minX || x == maxX || z == minZ || z == maxZ)
                                        && y != minY && y != maxY;
                            } else if (op.hollow) {
                                shell = x == minX || x == maxX
                                        || y == minY || y == maxY
                                        || z == minZ || z == maxZ;
                            } else {
                                shell = true;
                            }
                            if (shell) {
                                out.add(new Cell(new BlockPos(x, y, z), bid, clear));
                                if (out.size() >= MAX_CELLS) return;
                            }
                        }
                    }
                }
            }
            default -> { }
        }
    }

    /**
     * Material pre-check + cell ordering. Returns an error message when the
     * whole job must be refused, or null when OK (cells ready to build).
     */
    String precheck(ClientPlayerEntity player) {
        if (totalCells == 0) {
            return "build: ops produced no cells (check coordinates)";
        }
        if (totalCells > MAX_CELLS) {
            return "build: too many cells (" + totalCells + " > " + MAX_CELLS + ")";
        }
        // count materials
        for (Cell c : cells) {
            if (!c.clear) {
                needMaterials.merge(c.blockId, 1, Integer::sum);
            }
        }
        if (!needMaterials.isEmpty()) {
            Map<String, Integer> have = countMaterials(player);
            for (Map.Entry<String, Integer> en : needMaterials.entrySet()) {
                int got = have.getOrDefault(en.getKey(), 0);
                if (got < en.getValue()) {
                    return "material shortage, whole job refused: " + en.getKey()
                            + " needs " + en.getValue() + ", inventory has " + got
                            + " (gather materials first, then retry mc_build)";
                }
            }
        }
        // order: Y asc (ground up), then distance to player
        List<Cell> ordered = new ArrayList<>(cells);
        ordered.sort((a, b) -> {
            int dy = Integer.compare(a.pos.getY(), b.pos.getY());
            if (dy != 0) return dy;
            double da = player.squaredDistanceTo(a.pos.getX() + 0.5,
                    a.pos.getY() + 0.5, a.pos.getZ() + 0.5);
            double db = player.squaredDistanceTo(b.pos.getX() + 0.5,
                    b.pos.getY() + 0.5, b.pos.getZ() + 0.5);
            return Double.compare(da, db);
        });
        cells.clear();
        cells.addAll(ordered);
        return null;
    }

    private Map<String, Integer> countMaterials(ClientPlayerEntity player) {
        Map<String, Integer> have = new HashMap<>();
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            Identifier id = Registries.ITEM.getId(stack.getItem());
            if (id == null) continue;
            String path = id.getPath().toLowerCase();
            if (needMaterials.containsKey(path)) {
                have.merge(path, stack.getCount(), Integer::sum);
            }
        }
        return have;
    }

    @Override
    public boolean tick(MinecraftClient client, ClientPlayerEntity player) {
        if (actionVersion != myVersion || navCancelled) {
            releaseAllKeys(client);
            return true;
        }
        if (hitDelay > 0) {
            hitDelay--;
            releaseAllKeys(client);
            return false;
        }

        // pick next cell
        if (cur == null) {
            if (cells.isEmpty()) {
                releaseAllKeys(client);
                sendResult("build", callId, true,
                        "build finished: placed " + built + ", cleared " + cleared
                                + (skipped > 0 ? ", skipped " + skipped : ""));
                return true;
            }
            cur = cells.poll();
            phase = 0;
            phaseTicks = 0;
            attempts = 0;
            cellStart = System.currentTimeMillis();
            // already satisfied?
            BlockState st = player.getWorld().getBlockState(cur.pos);
            boolean done = cur.clear ? st.isAir() : matches(st, cur.blockId);
            if (done) {
                built++;
                cur = null;
                return false;
            }
            if (!resolveAnchor(player)) {
                // cannot find a stand/aim spot for this cell: skip
                skipped++;
                cur = null;
                StateCollector.addBehaviorLog("build: cannot reach cell "
                        + "cell" + ", skipped");
                return false;
            }
        }

        // per-cell timeout
        if (System.currentTimeMillis() - cellStart > PER_CELL_TIMEOUT_MS) {
            skipped++;
            StateCollector.addBehaviorLog("build: cell timeout, skipped "
                    + (cur != null ? cur.pos.toShortString() : ""));
            cur = null;
            return false;
        }
        // overall timeout
        if (System.currentTimeMillis() - startTime > timeoutMs) {
            releaseAllKeys(client);
            sendResult("build", callId, false,
                    "build timeout: done " + built + "/" + totalCells
                            + " cells (" + cells.size() + " remaining), skipped " + skipped);
            return true;
        }

        if (phase == 0) return tickWalk(client, player);
        if (phase == 1) return tickPlace(client, player);
        if (phase == 2) return tickVerify(client, player);
        return tickDig(client, player);
    }

    /** Find an anchor block + stand spot for the current cell. */
    private boolean resolveAnchor(ClientPlayerEntity player) {
        World world = player.getWorld();
        BlockPos cell = cur.pos;
        // 1) block below the cell (click its top face)
        BlockPos below = cell.down();
        if (!world.getBlockState(below).isAir()) {
            BlockPos spot = bestSpot(player, cell, below, Direction.UP);
            if (spot != null) {
                curAnchor = below;
                curFace = Direction.UP;
                curSpot = spot;
                return true;
            }
        }
        // 2) adjacent horizontal blocks at the cell's level
        for (int[] d : ADJ4) {
            BlockPos side = cell.add(d[0], 0, d[1]);
            if (!world.getBlockState(side).isAir()) {
                BlockPos spot = bestSpot(player, cell, side, faceFrom(side, cell));
                if (spot != null) {
                    curAnchor = side;
                    curFace = faceFrom(side, cell);
                    curSpot = spot;
                    return true;
                }
            }
        }
        // 3) block below adjacent (click its top/side from the ground)
        for (int[] d : ADJ4) {
            BlockPos sideBelow = cell.add(d[0], -1, d[1]);
            if (!world.getBlockState(sideBelow).isAir()) {
                BlockPos spot = bestSpot(player, cell, sideBelow, Direction.UP);
                if (spot != null) {
                    curAnchor = sideBelow;
                    curFace = Direction.UP;
                    curSpot = spot;
                    return true;
                }
            }
        }
        return false;
    }

    /** Nearest standable position from which the anchor face is reachable. */
    private BlockPos bestSpot(ClientPlayerEntity player, BlockPos cell,
                              BlockPos anchor, Direction face) {
        World world = player.getWorld();
        Vec3d faceCenter = new Vec3d(anchor.getX() + 0.5 + face.getOffsetX() * 0.5,
                anchor.getY() + 0.5 + face.getOffsetY() * 0.5,
                anchor.getZ() + 0.5 + face.getOffsetZ() * 0.5);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = -2; dy <= 1; dy++) {
                    BlockPos spot = new BlockPos(cell.getX() + dx, cell.getY() + dy, cell.getZ() + dz);
                    if (!isStandable(world, spot)) continue;
                    // eye must reach the face center (MC reach ~4.5)
                    double ex = player.getX() - faceCenter.x;
                    double ey = (player.getY() + player.getEyeHeight(player.getPose())) - faceCenter.y;
                    double ez = player.getZ() - faceCenter.z;
                    double reach = Math.sqrt(ex * ex + ey * ey + ez * ez);
                    if (reach > 4.2) continue;
                    double d = player.squaredDistanceTo(spot.getX() + 0.5,
                            spot.getY() + 0.5, spot.getZ() + 0.5);
                    if (d < bestDist) {
                        bestDist = d;
                        best = spot;
                    }
                }
            }
        }
        return best;
    }

    /** Walk toward the stand spot. */
    private boolean tickWalk(MinecraftClient client, ClientPlayerEntity player) {
        phaseTicks++;
        double sx = curSpot.getX() + 0.5;
        double sy = curSpot.getY() + 0.5;
        double sz = curSpot.getZ() + 0.5;
        double dx = sx - player.getX();
        double dy = sy - player.getY();
        double dz = sz - player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);
        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setPitch(0);
        if (hDist < 1.6 && Math.abs(dy) < 1.1) {
            releaseAllKeys(client);
            phase = 1;
            phaseTicks = 0;
            return false;
        }
        client.options.forwardKey.setPressed(true);
        if (dy > 0.6 && phaseTicks % 16 < 4) {
            client.options.jumpKey.setPressed(true);
        } else {
            client.options.jumpKey.setPressed(false);
        }
        // stuck guard: 8s without moving horizontally -> skip cell
        if (phaseTicks > 160) {
            double moved = Math.abs(player.getX() - lastWalkX)
                    + Math.abs(player.getZ() - lastWalkZ);
            if (moved < 0.3) {
                releaseAllKeys(client);
                skipped++;
                cur = null;
                StateCollector.addBehaviorLog("build: stuck walking, skipped cell");
                return false;
            }
            lastWalkX = player.getX();
            lastWalkZ = player.getZ();
            phaseTicks = 0;
        }
        return false;
    }

    private double lastWalkX = 0, lastWalkZ = 0;

    /** Attempt placement (or clear digging). */
    private boolean tickPlace(MinecraftClient client, ClientPlayerEntity player) {
        phaseTicks++;
        if (cur.clear) {
            // dig the cell out
            BlockState st = player.getWorld().getBlockState(cur.pos);
            if (st.isAir()) {
                releaseAllKeys(client);
                hitDelay = 3;
                cleared++;
                cur = null;
                return false;
            }
            ensureBestTool(player, st);
            double dx = cur.pos.getX() + 0.5 - player.getX();
            double dy = cur.pos.getY() + 0.5
                    - (player.getY() + player.getEyeHeight(player.getPose()));
            double dz = cur.pos.getZ() + 0.5 - player.getZ();
            player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            player.setPitch((float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
            client.options.attackKey.setPressed(true);
            if (phaseTicks > 200) {
                releaseAllKeys(client);
                skipped++;
                cur = null;
                StateCollector.addBehaviorLog("build: clear timed out, skipped");
            }
            return false;
        }

        // place: find a stack of the material
        int slot = findBlockSlot(player, cur.blockId);
        if (slot < 0) {
            releaseAllKeys(client);
            skipped++;
            StateCollector.addBehaviorLog("build: no " + cur.blockId + " left, skipped cell");
            cur = null;
            return false;
        }
        PlayerInventory inv = player.getInventory();
        if (slot < 9) {
            inv.selectedSlot = slot;
        } else {
            client.interactionManager.pickFromInventory(slot);
        }
        releaseAllKeys(client);
        Vec3d hitPos = new Vec3d(curAnchor.getX() + 0.5 + curFace.getOffsetX() * 0.5,
                curAnchor.getY() + 0.5 + curFace.getOffsetY() * 0.5,
                curAnchor.getZ() + 0.5 + curFace.getOffsetZ() * 0.5);
        BlockHitResult bhr = new BlockHitResult(hitPos, curFace, curAnchor, false);
        ActionResult res = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, bhr);
        if (!res.isAccepted()) {
            if (++attempts >= MAX_ATTEMPTS) {
                skipped++;
                cur = null;
                StateCollector.addBehaviorLog("build: placement rejected, skipped");
                return false;
            }
            phaseTicks = 0;
            return false;
        }
        phase = 2;
        phaseTicks = 0;
        return false;
    }

    /** Verify the block actually appeared, retry up to MAX_ATTEMPTS. */
    private boolean tickVerify(MinecraftClient client, ClientPlayerEntity player) {
        phaseTicks++;
        if (phaseTicks < 8) return false;
        BlockState st = player.getWorld().getBlockState(cur.pos);
        boolean ok = cur.clear ? st.isAir() : matches(st, cur.blockId);
        if (ok) {
            built++;
            hitDelay = 2;
            cur = null;
            return false;
        }
        if (++attempts >= MAX_ATTEMPTS) {
            skipped++;
            cur = null;
            StateCollector.addBehaviorLog("build: verify failed after retries, skipped cell");
            return false;
        }
        phase = 1;
        phaseTicks = 0;
        return false;
    }

    private boolean tickDig(MinecraftClient client, ClientPlayerEntity player) {
        // unused: clear cells are handled inside tickPlace
        return true;
    }

    private static boolean matches(BlockState st, String blockId) {
        if (st.isAir()) return false;
        Identifier id = Registries.BLOCK.getId(st.getBlock());
        return id != null && id.getPath().equalsIgnoreCase(blockId);
    }

    private static Direction faceFrom(BlockPos anchor, BlockPos cell) {
        int dx = cell.getX() - anchor.getX();
        int dy = cell.getY() - anchor.getY();
        int dz = cell.getZ() - anchor.getZ();
        if (Math.abs(dx) >= Math.abs(dy) && Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (Math.abs(dz) >= Math.abs(dy)) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return dy > 0 ? Direction.UP : Direction.DOWN;
    }

    private static String normalizeId(String id) {
        if (id == null) return "";
        String s = id.trim().toLowerCase();
        if (s.startsWith("minecraft:")) s = s.substring(10);
        return s;
    }

    /** Find an inventory slot holding the given block id (path match). */
    private static int findBlockSlot(ClientPlayerEntity player, String blockId) {
        PlayerInventory inv = player.getInventory();
        for (int pass = 0; pass < 2; pass++) {
            int from = pass == 0 ? 0 : 9;
            int to = pass == 0 ? 9 : 36;
            for (int i = from; i < to; i++) {
                ItemStack stack = inv.getStack(i);
                if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) continue;
                Identifier id = Registries.ITEM.getId(stack.getItem());
                if (id != null && id.getPath().equalsIgnoreCase(blockId)) {
                    return i;
                }
            }
        }
        return -1;
    }
}
