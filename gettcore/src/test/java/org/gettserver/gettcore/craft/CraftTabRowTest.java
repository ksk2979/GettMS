package org.gettserver.gettcore.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CraftTabRowTest {
    private static final int GAP = 2;

    @Test
    void everythingFitsWithoutArrows() {
        int[] w = {50, 50, 50};
        assertFalse(CraftTabRow.overflows(w, 154, GAP));
        assertTrue(CraftTabRow.overflows(w, 153, GAP));
        assertEquals(3, CraftTabRow.visible(w, 0, 154, GAP));
    }

    @Test
    void visibleCountsWholeTabsFromTheOffset() {
        int[] w = {50, 60, 70, 40, 40};
        assertEquals(2, CraftTabRow.visible(w, 0, 120, GAP));   // 50 + 2 + 60 = 112
        assertEquals(1, CraftTabRow.visible(w, 1, 120, GAP));   // 60, then 70 does not fit
        assertEquals(2, CraftTabRow.visible(w, 3, 120, GAP));   // the last two
        // A tab wider than the row is still shown alone, so every tab can be reached.
        assertEquals(1, CraftTabRow.visible(new int[] {200, 30}, 0, 120, GAP));
    }

    @Test
    void offsetStopsWhereTheLastTabsFillTheRow() {
        int[] w = {50, 60, 70, 40, 40};
        assertEquals(3, CraftTabRow.maxOffset(w, 120, GAP));    // 40 + 2 + 40 fits, adding 70 does not
        assertEquals(0, CraftTabRow.clampOffset(-1, w, 120, GAP));
        assertEquals(3, CraftTabRow.clampOffset(9, w, 120, GAP));
        assertEquals(0, CraftTabRow.maxOffset(w, 1000, GAP));
        assertEquals(0, CraftTabRow.maxOffset(new int[0], 120, GAP));
    }

    @Test
    void ensureVisibleMovesOnlyAsFarAsNeeded() {
        int[] w = {50, 60, 70, 40, 40};
        assertEquals(0, CraftTabRow.ensureVisible(0, 1, w, 120, GAP));
        assertEquals(2, CraftTabRow.ensureVisible(0, 2, w, 120, GAP));   // 70 alone, or with 40 after it
        assertEquals(3, CraftTabRow.ensureVisible(0, 4, w, 120, GAP));
        assertEquals(1, CraftTabRow.ensureVisible(3, 1, w, 120, GAP));
    }
}
