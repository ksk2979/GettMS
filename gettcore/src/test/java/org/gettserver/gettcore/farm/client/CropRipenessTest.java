package org.gettserver.gettcore.farm.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CropRipenessTest {
    @Test
    void matureOnlyAtOrAboveMaxAge() {
        assertTrue(CropRipeness.isMature(7, 7));
        assertTrue(CropRipeness.isMature(3, 3));
        assertTrue(CropRipeness.isMature(2, 2));
        assertFalse(CropRipeness.isMature(6, 7));
        assertFalse(CropRipeness.isMature(0, 3));
    }

    @Test
    void zeroOrNegativeMaxIsNeverMature() {
        assertFalse(CropRipeness.isMature(0, 0));
        assertFalse(CropRipeness.isMature(-1, -1));
    }

    @Test
    void scanRangeIsCircleOfRadius8AndMinus3ToPlus3() {
        assertTrue(CropRipeness.inRange(0, 0, 0));
        assertTrue(CropRipeness.inRange(8, 0, 0));
        assertTrue(CropRipeness.inRange(0, -3, -8));
        assertTrue(CropRipeness.inRange(0, 3, 0));
        assertFalse(CropRipeness.inRange(9, 0, 0));
        assertFalse(CropRipeness.inRange(0, 4, 0));
        assertFalse(CropRipeness.inRange(0, -4, 0));
        assertFalse(CropRipeness.inRange(6, 0, 6)); // 72 > 64: corner of the square is outside
        assertTrue(CropRipeness.inRange(5, 1, 6)); // 61 <= 64
    }

    @Test
    void scansEveryTenTicks() {
        assertTrue(CropRipeness.shouldScan(0));
        assertFalse(CropRipeness.shouldScan(1));
        assertFalse(CropRipeness.shouldScan(9));
        assertTrue(CropRipeness.shouldScan(10));
        assertTrue(CropRipeness.shouldScan(250));
    }

    @Test
    void boxCapIs256() {
        assertEquals(256, CropRipeness.MAX_BOXES);
    }

    @Test
    void buddingPlantsAreExcludedFromTagFallback() {
        // farmersdelight:budding_tomatoes at max age turns into a vine — nothing to harvest yet.
        assertTrue(CropRipeness.excludedFromFallback("budding_tomatoes"));
        assertFalse(CropRipeness.excludedFromFallback("tomatoes"));
        assertFalse(CropRipeness.excludedFromFallback("rice_panicles"));
    }
}
