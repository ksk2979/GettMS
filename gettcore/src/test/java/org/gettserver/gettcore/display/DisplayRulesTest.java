package org.gettserver.gettcore.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DisplayRulesTest {
    @Test void runningTimelineReachesItsEnd() {
        // 60 s video, stored position 10 s at t=1000
        assertFalse(DisplayRules.reachedEnd(10_000, 1_000, false, 60_000, 40_000, 700)); // at 49 s
        assertFalse(DisplayRules.reachedEnd(10_000, 1_000, false, 60_000, 50_200, 700)); // at 59.2 s
        assertTrue(DisplayRules.reachedEnd(10_000, 1_000, false, 60_000, 50_300, 700));  // at 59.3 s
        assertTrue(DisplayRules.reachedEnd(10_000, 1_000, false, 60_000, 90_000, 700));  // long past
    }

    @Test void pausedOrUnknownDurationNeverEnds() {
        assertFalse(DisplayRules.reachedEnd(59_900, 1_000, true, 60_000, 90_000, 700));
        assertFalse(DisplayRules.reachedEnd(59_900, 1_000, false, 0, 90_000, 700));
    }

    @Test void clockGoingBackwardsDoesNotRewind() {
        assertTrue(DisplayRules.reachedEnd(59_500, 5_000, false, 60_000, 1_000, 700));
        assertFalse(DisplayRules.reachedEnd(10_000, 5_000, false, 60_000, 1_000, 700));
    }

    @Test void urlsAreTrimmedAndBounded() {
        assertEquals("https://youtu.be/abc", DisplayRules.cleanUrl("  https://youtu.be/abc \n"));
        assertEquals("", DisplayRules.cleanUrl(null));
        assertEquals("", DisplayRules.cleanUrl("   "));
        assertEquals("", DisplayRules.cleanUrl("x".repeat(DisplayRules.MAX_URL_LENGTH + 1)));
    }
}
