package org.gettserver.gettcore.reinforce;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ElytraBoostTest {
    private static final double EPS = 1e-9;

    /** Vanilla-like glide: each tick v' = 0.99 (v + a) on the horizontal axis, then our boost. */
    private static double glideSteady(double accel, double percent) {
        double v = 0;
        for (int t = 0; t < 3000; t++) {
            double before = v;
            double after = 0.99 * (v + accel);
            v = ElytraBoost.glide(before, 0, after, 0, percent)[0];
        }
        return v;
    }

    @Test
    void steadyStateBounded() {
        double base = glideSteady(0.02, 0);
        double boosted = glideSteady(0.02, 29);
        assertEquals(1.29, boosted / base, 0.05);
        assertEquals(base, glideSteady(0.02, 0), EPS);
    }

    @Test
    void zeroPercentLeavesVelocityAlone() {
        assertArrayEquals(new double[]{0.7, -0.3}, ElytraBoost.glide(1.0, 0.2, 0.7, -0.3, 0), EPS);
    }

    @Test
    void fireworkExtraScales() {
        double[] look = {1, 0, 0};
        double[] v = {0.5, 0, 0};
        // vanilla push on x: 1*0.1 + (1*1.5 - 0.5)*0.5 = 0.6
        assertArrayEquals(new double[]{0.18, 0, 0}, ElytraBoost.fireworkExtra(look, v, 30), EPS);
        assertArrayEquals(new double[]{0, 0, 0}, ElytraBoost.fireworkExtra(look, v, 0), EPS);
    }
}
