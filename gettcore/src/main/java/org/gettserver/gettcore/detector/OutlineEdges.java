package org.gettserver.gettcore.detector;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;

/**
 * Outline of a clump of blocks: the unit grid edges where the surface bends. A unit edge is surrounded by four
 * cells; with 0 or 4 of them in the set, or 2 side by side (a flat face), no line is drawn. 1, 3 or 2 diagonal
 * cells leave a line.
 */
public final class OutlineEdges {
    private OutlineEdges() {}

    /** Segments of 6 ints each (x1,y1,z1,x2,y2,z2), unit length, in block grid coordinates. */
    public static int[] of(LongSet positions) {
        // Edge start points per axis (x, y, z); an edge runs from its start to start + 1 along that axis.
        LongSet[] seen = {new LongOpenHashSet(), new LongOpenHashSet(), new LongOpenHashSet()};
        LongList[] kept = {new LongArrayList(), new LongArrayList(), new LongArrayList()};
        for (long p : positions) {
            int x = BlockPos.getX(p);
            int y = BlockPos.getY(p);
            int z = BlockPos.getZ(p);
            for (int a = 0; a < 2; a++) {
                for (int b = 0; b < 2; b++) {
                    consider(positions, seen, kept, 0, x, y + a, z + b);
                    consider(positions, seen, kept, 1, x + a, y, z + b);
                    consider(positions, seen, kept, 2, x + a, y + b, z);
                }
            }
        }
        int total = kept[0].size() + kept[1].size() + kept[2].size();
        int[] out = new int[total * 6];
        int i = 0;
        for (int axis = 0; axis < 3; axis++) {
            for (long p : kept[axis]) {
                int x = BlockPos.getX(p);
                int y = BlockPos.getY(p);
                int z = BlockPos.getZ(p);
                out[i++] = x;
                out[i++] = y;
                out[i++] = z;
                out[i++] = x + (axis == 0 ? 1 : 0);
                out[i++] = y + (axis == 1 ? 1 : 0);
                out[i++] = z + (axis == 2 ? 1 : 0);
            }
        }
        return out;
    }

    private static void consider(LongSet positions, LongSet[] seen, LongList[] kept, int axis, int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        if (!seen[axis].add(key)) return;
        // The four cells around the edge, in the plane perpendicular to the axis: (0,0) (1,0) (0,1) (1,1).
        boolean c00 = has(positions, axis, x, y, z, 0, 0);
        boolean c10 = has(positions, axis, x, y, z, 1, 0);
        boolean c01 = has(positions, axis, x, y, z, 0, 1);
        boolean c11 = has(positions, axis, x, y, z, 1, 1);
        int n = (c00 ? 1 : 0) + (c10 ? 1 : 0) + (c01 ? 1 : 0) + (c11 ? 1 : 0);
        boolean line;
        if (n == 1 || n == 3) line = true;
        else if (n == 2) line = (c00 && c11) || (c10 && c01); // diagonal; adjacent pairs are a flat face
        else line = false;
        if (line) kept[axis].add(key);
    }

    private static boolean has(LongSet positions, int axis, int x, int y, int z, int u, int v) {
        // Cell offsets are -1 + u / -1 + v on the two axes other than the edge axis.
        int cx = x;
        int cy = y;
        int cz = z;
        switch (axis) {
            case 0 -> {
                cy = y - 1 + u;
                cz = z - 1 + v;
            }
            case 1 -> {
                cx = x - 1 + u;
                cz = z - 1 + v;
            }
            default -> {
                cx = x - 1 + u;
                cy = y - 1 + v;
            }
        }
        return positions.contains(BlockPos.asLong(cx, cy, cz));
    }
}
