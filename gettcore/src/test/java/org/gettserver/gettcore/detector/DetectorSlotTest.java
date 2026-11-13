package org.gettserver.gettcore.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.gettserver.gettcore.detector.DetectorSlot.Result;
import org.gettserver.gettcore.detector.DetectorSlot.Stack;
import org.junit.jupiter.api.Test;

class DetectorSlotTest {
    private static final Stack COAL1 = new Stack("minecraft:coal", 1);

    private static int total(Result r, Stack stored, Stack cursor) {
        return count(r.newStored()) + count(r.cursorAfter()) + count(r.giveBack());
    }

    private static int count(Stack s) {
        return s == null ? 0 : s.count();
    }

    @Test
    void emptyCursorTakesOut() {
        Result r = DetectorSlot.click(COAL1, null, false);
        assertTrue(r.accepted());
        assertNull(r.newStored());
        assertEquals(COAL1, r.cursorAfter());
        assertNull(r.giveBack());
    }

    @Test
    void emptyCursorOnEmptySlotNotAccepted() {
        assertFalse(DetectorSlot.click(null, null, false).accepted());
    }

    @Test
    void nonTargetCursorChangesNothing() {
        Stack cursor = new Stack("minecraft:dirt", 5);
        Result r = DetectorSlot.click(COAL1, cursor, false);
        assertTrue(r.accepted());
        assertEquals(COAL1, r.newStored());
        assertEquals(cursor, r.cursorAfter());
        assertNull(r.giveBack());
    }

    @Test
    void singleItemSwaps() {
        Result r = DetectorSlot.click(COAL1, new Stack("minecraft:raw_iron", 1), true);
        assertTrue(r.accepted());
        assertEquals(new Stack("minecraft:raw_iron", 1), r.newStored());
        assertEquals(COAL1, r.cursorAfter());
        assertNull(r.giveBack());
    }

    @Test
    void swapKeepsEveryItem() {
        Stack cursor = new Stack("minecraft:raw_iron", 5);
        Result r = DetectorSlot.click(COAL1, cursor, true);
        assertEquals(new Stack("minecraft:raw_iron", 1), r.newStored());
        assertEquals(new Stack("minecraft:raw_iron", 4), r.cursorAfter());
        assertEquals(COAL1, r.giveBack());
        assertEquals(6, total(r, COAL1, cursor));
    }

    @Test
    void fillEmptyFromStack() {
        Result r = DetectorSlot.click(null, new Stack("minecraft:coal", 3), true);
        assertEquals(COAL1, r.newStored());
        assertEquals(new Stack("minecraft:coal", 2), r.cursorAfter());
        assertNull(r.giveBack());
    }

    @Test
    void sameItemIsNoop() {
        Stack cursor = new Stack("minecraft:coal", 3);
        Result r = DetectorSlot.click(COAL1, cursor, true);
        assertTrue(r.accepted());
        assertEquals(COAL1, r.newStored());
        assertEquals(cursor, r.cursorAfter());
        assertNull(r.giveBack());
    }
}
