package org.gettserver.gettcore.shop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TabStripTest {
    @Test
    void sevenOrFewerTabsFitWithoutArrows() {
        assertFalse(TabStrip.overflows(7));
        assertEquals(7, TabStrip.visibleCount(7));
        assertEquals(3, TabStrip.visibleCount(3));
        assertEquals(0, TabStrip.clampOffset(5, 7));
    }

    @Test
    void overflowShowsSixTabsBetweenArrows() {
        assertTrue(TabStrip.overflows(8));
        assertEquals(6, TabStrip.visibleCount(8));
        assertEquals(6, TabStrip.visibleCount(20));
    }

    @Test
    void offsetClampsToLastWindow() {
        assertEquals(0, TabStrip.clampOffset(-1, 10));
        assertEquals(4, TabStrip.clampOffset(9, 10));
        assertEquals(2, TabStrip.clampOffset(2, 10));
    }

    @Test
    void ensureVisibleScrollsJustEnough() {
        assertEquals(0, TabStrip.ensureVisible(0, 3, 10));
        assertEquals(2, TabStrip.ensureVisible(0, 7, 10));
        assertEquals(1, TabStrip.ensureVisible(4, 1, 10));
        assertEquals(0, TabStrip.ensureVisible(0, 9, 7));
    }
}
