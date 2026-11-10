package org.gettserver.gettcore.quest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HudScrollTest {
    @Test
    void clampLinesBounds() {
        assertEquals(6, HudScroll.clampLines(3));
        assertEquals(30, HudScroll.clampLines(40));
        assertEquals(12, HudScroll.clampLines(12));
    }

    @Test
    void clampScrollNoOverflow() {
        assertEquals(0, HudScroll.clampScroll(3, 5, 8));
    }

    @Test
    void clampScrollMax() {
        assertEquals(12, HudScroll.clampScroll(99, 20, 8));
    }

    @Test
    void clampScrollNeverNegative() {
        assertEquals(0, HudScroll.clampScroll(-4, 20, 8));
    }

    @Test
    void availableSubtractsSeparator() {
        assertEquals(7, HudScroll.available(12, 4));
    }

    @Test
    void availableNeverNegative() {
        assertEquals(0, HudScroll.available(6, 10));
    }
}
