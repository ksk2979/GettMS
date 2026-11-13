package org.gettserver.gettcore.detector.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class HighlightStoreTest {

    private static long p(int x) {
        return BlockPos.asLong(x, 64, 0);
    }

    @Test
    void applyReplacesSameKey() {
        HighlightStore s = new HighlightStore();
        s.apply("a", new long[] {p(1), p(2), p(3)}, 100, 0);
        s.apply("a", new long[] {p(9)}, 100, 0);
        assertEquals(1, s.union().size());
        assertTrue(s.union().contains(p(9)));
    }

    @Test
    void differentKeysMerge() {
        HighlightStore s = new HighlightStore();
        s.apply("a", new long[] {p(1), p(2)}, 100, 0);
        s.apply("b", new long[] {p(2), p(3)}, 100, 0);
        assertEquals(3, s.union().size());
    }

    @Test
    void expireDropsOnlyExpired() {
        HighlightStore s = new HighlightStore();
        s.apply("A", new long[] {p(1)}, 20, 0);
        s.apply("B", new long[] {p(2)}, 400, 0);
        assertTrue(s.expire(100));
        assertEquals(1, s.union().size());
        assertTrue(s.union().contains(p(2)));
        assertFalse(s.expire(100));
    }

    @Test
    void emptyPayloadDeletesKey() {
        HighlightStore s = new HighlightStore();
        s.apply("a", new long[] {p(1)}, 100, 0);
        s.apply("b", new long[] {p(2)}, 100, 0);
        s.apply("a", new long[] {}, 100, 0);
        assertEquals(1, s.union().size());
        s.apply("b", new long[] {p(2)}, 0, 0);
        assertTrue(s.isEmpty());
    }

    @Test
    void blankKeyClearsAll() {
        HighlightStore s = new HighlightStore();
        s.apply("a", new long[] {p(1)}, 100, 0);
        s.apply("b", new long[] {p(2)}, 100, 0);
        s.apply("", new long[] {}, 0, 0);
        assertTrue(s.isEmpty());
    }

    @Test
    void removeDropsMinedBlocks() {
        HighlightStore s = new HighlightStore();
        s.apply("a", new long[] {p(1), p(2)}, 100, 0);
        s.apply("b", new long[] {p(3)}, 100, 0);
        assertTrue(s.remove(l -> l == p(1) || l == p(3)));
        assertEquals(1, s.union().size());
        assertTrue(s.union().contains(p(2)));
        assertFalse(s.remove(l -> false));
        assertTrue(s.remove(l -> true));
        assertTrue(s.isEmpty());
    }
}
