package org.gettserver.gettcore.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class OutlineEdgesTest {

    private static LongSet set(int[]... cells) {
        LongSet s = new LongOpenHashSet();
        for (int[] c : cells) s.add(BlockPos.asLong(c[0], c[1], c[2]));
        return s;
    }

    private static int segments(int[] edges) {
        assertEquals(0, edges.length % 6);
        return edges.length / 6;
    }

    private static Set<String> segmentSet(int[] e) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i < e.length; i += 6) {
            out.add(e[i] + "," + e[i + 1] + "," + e[i + 2] + ">" + e[i + 3] + "," + e[i + 4] + "," + e[i + 5]);
        }
        return out;
    }

    @Test
    void singleBlockHas12Edges() {
        assertEquals(12, segments(OutlineEdges.of(set(new int[] {0, 0, 0}))));
    }

    @Test
    void twoAdjacentBlocksHave16Segments() {
        assertEquals(16, segments(OutlineEdges.of(set(new int[] {0, 0, 0}, new int[] {0, 0, 1}))));
    }

    @Test
    void twoByTwoFlatSlabHasNoInnerLines() {
        int[] e = OutlineEdges.of(set(new int[] {0, 0, 0}, new int[] {1, 0, 0}, new int[] {0, 0, 1},
                new int[] {1, 0, 1}));
        assertEquals(20, segments(e));
        // The centre cross on the top face must not be drawn.
        assertTrue(!segmentSet(e).contains("1,1,0>1,1,1"));
    }

    @Test
    void diagonalPairKeepsSharedEdge() {
        int[] e = OutlineEdges.of(set(new int[] {0, 0, 0}, new int[] {1, 1, 0}));
        assertEquals(23, segments(e));
        assertTrue(segmentSet(e).contains("1,1,0>1,1,1"));
    }

    @Test
    void emptySetIsEmpty() {
        assertEquals(0, OutlineEdges.of(new LongOpenHashSet()).length);
    }
}
