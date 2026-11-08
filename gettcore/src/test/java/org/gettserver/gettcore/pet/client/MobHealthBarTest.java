package org.gettserver.gettcore.pet.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** {@link MobHealthBar#alpha} delegates to {@link HealthBarFade#alpha}; the pure class is tested so no client classes load. */
class MobHealthBarTest {
    private static float alpha(long since, double ratio) {
        return HealthBarFade.alpha(since, ratio, 200, 20, 0.20);
    }

    @Test
    void justHitIsFullyVisible() {
        assertEquals(1f, alpha(0, 0.5), 1e-6);
    }

    @Test
    void atFadeDelayStillFullyVisible() {
        assertEquals(1f, alpha(200, 0.5), 1e-6);
    }

    @Test
    void halfwayThroughFadeIsHalf() {
        assertEquals(0.5f, alpha(210, 0.5), 1e-6);
    }

    @Test
    void afterFadeIsHidden() {
        assertEquals(0f, alpha(220, 0.5), 1e-6);
    }

    @Test
    void atCaptureMaxStaysVisible() {
        assertEquals(1f, alpha(999, 0.20), 1e-6);
    }

    @Test
    void justAboveCaptureMaxFadesOut() {
        assertEquals(0f, alpha(999, 0.21), 1e-6);
    }
}
