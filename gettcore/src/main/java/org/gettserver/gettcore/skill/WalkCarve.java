package org.gettserver.gettcore.skill;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Level-free planner for a walking boss: which blocks stop a body of the given height from walking through a cell,
 * and so must be broken. Floors are never broken, one-block steps and short drops are walked, long drops count as
 * walls.
 */
public final class WalkCarve {
    /** A fall of more than this many blocks is a cliff, not a step down. */
    public static final int MAX_DROP = 3;

    private WalkCarve() {}

    public interface Blocks {
        /** A block the body cannot pass (has a collision shape). */
        boolean solid(int x, int y, int z);

        /** A block without collision that is not air or liquid (plants, snow layers, ...). */
        boolean light(int x, int y, int z);
    }

    /** One block to break and its horizontal distance from the plan's center (the order a shockwave reaches it). */
    public record Carve(int x, int y, int z, double distance, boolean light) {}

    /** The result of walking into one cell; {@code carve} holds {x, y, z} triples. */
    public record Step(boolean walkable, int feetY, List<int[]> carve) {}

    /** The candidate feet heights for entering a cell from feet height f: same, one up, then up to MAX_DROP down. */
    private static int[] candidates(int f) {
        int[] c = new int[3 + MAX_DROP - 1];
        c[0] = f;
        c[1] = f + 1;
        for (int d = 1; d <= MAX_DROP; d++) c[1 + d] = f - d;
        return c;
    }

    private static boolean standable(int x, int g, int z, int bodyHeight, Blocks blocks) {
        if (!blocks.solid(x, g - 1, z)) return false;
        for (int y = g; y < g + bodyHeight; y++) if (blocks.solid(x, y, z)) return false;
        return true;
    }

    /** Free body room and nothing to stand on within the reachable drop: the cell is a cliff edge. */
    private static boolean cliff(int x, int f, int z, int bodyHeight, Blocks blocks) {
        for (int y = f - 1; y >= f - MAX_DROP - 1; y--) if (blocks.solid(x, y, z)) return false;
        for (int y = f; y < f + bodyHeight; y++) if (blocks.solid(x, y, z)) return false;
        return true;
    }

    private static List<int[]> body(int x, int g, int z, int bodyHeight, Blocks blocks, boolean solidsToo) {
        List<int[]> out = new ArrayList<>();
        for (int y = g; y < g + bodyHeight; y++) {
            if (blocks.light(x, y, z) || (solidsToo && blocks.solid(x, y, z))) out.add(new int[] {x, y, z});
        }
        return out;
    }

    /** Entering cell (x, z) from feet height {@code feetY}. */
    public static Step step(int x, int feetY, int z, int bodyHeight, Blocks blocks) {
        for (int g : candidates(feetY)) {
            if (standable(x, g, z, bodyHeight, blocks)) return new Step(true, g, body(x, g, z, bodyHeight, blocks, false));
        }
        boolean edge = cliff(x, feetY, z, bodyHeight, blocks);
        return new Step(false, feetY, body(x, feetY, z, bodyHeight, blocks, !edge));
    }

    /** A wall this many cells thick is pierced; a thicker mass (a hill) is not hollowed out. */
    public static final int MAX_BLOCKED_RUN = 2;

    /**
     * The plan for a disc of {@code radius} around (cx, feetY, cz): breadth-first over the four neighbours, each cell
     * once, spreading through blocked cells (they are broken) but not past a cliff, and not through more than
     * {@link #MAX_BLOCKED_RUN} blocked cells in a row. Sorted by distance.
     */
    public static List<Carve> disc(int cx, int feetY, int cz, double radius, int bodyHeight, Blocks blocks) {
        List<Carve> out = new ArrayList<>();
        for (int[] b : body(cx, feetY, cz, bodyHeight, blocks, false)) out.add(new Carve(b[0], b[1], b[2], 0, true));
        Set<Long> seen = new HashSet<>();
        seen.add(key(cx, cz));
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] {cx, cz, feetY, 0});
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            for (int[] d : dirs) {
                int x = cur[0] + d[0], z = cur[1] + d[1];
                double dist = Math.hypot(x - cx, z - cz);
                if (dist > radius || !seen.add(key(x, z))) continue;
                Step s = step(x, cur[2], z, bodyHeight, blocks);
                int run = s.walkable() ? 0 : cur[3] + 1;
                if (run > MAX_BLOCKED_RUN) continue;
                for (int[] b : s.carve()) out.add(new Carve(b[0], b[1], b[2], dist, !blocks.solid(b[0], b[1], b[2])));
                if (!s.walkable() && cliff(x, cur[2], z, bodyHeight, blocks)) continue;
                queue.add(new int[] {x, z, s.feetY(), run});
            }
        }
        out.sort(Comparator.comparingDouble(Carve::distance));
        return out;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
