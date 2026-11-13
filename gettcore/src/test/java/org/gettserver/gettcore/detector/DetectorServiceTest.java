package org.gettserver.gettcore.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DetectorServiceTest {
    @Test
    void prospectRangeIsClampedTo1Through64() {
        assertEquals(8, DetectorService.prospectRange(8));
        assertEquals(1, DetectorService.prospectRange(0));
        assertEquals(64, DetectorService.prospectRange(160));
        assertEquals(12, DetectorService.prospectRange(12.9));
    }

    @Test
    void prospectTicksAreClampedTo1Through72000() {
        assertEquals(400, DetectorService.prospectTicks(20));
        assertEquals(1, DetectorService.prospectTicks(0));
        assertEquals(72000, DetectorService.prospectTicks(1e12));
        assertEquals(1, DetectorService.prospectTicks(0.01));
    }
}
