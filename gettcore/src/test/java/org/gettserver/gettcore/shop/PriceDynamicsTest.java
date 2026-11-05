package org.gettserver.gettcore.shop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.gettserver.gettcore.data.PriceDynamicsConfig;
import org.junit.jupiter.api.Test;

class PriceDynamicsTest {
    private static final PriceDynamicsConfig ON = new PriceDynamicsConfig(true, 1.0, 10.0, 0.5, 2.0);

    @Test
    void disabledKeepsBase() {
        assertEquals(100, PriceDynamics.afterSale(70, 100, 5, PriceDynamicsConfig.DISABLED));
    }

    @Test
    void saleDropsAndClamps() {
        assertEquals(95, PriceDynamics.afterSale(100, 100, 5, ON));
        assertEquals(50, PriceDynamics.afterSale(100, 100, 200, ON));
    }

    @Test
    void recoverMovesTowardBase() {
        assertEquals(70, PriceDynamics.recover(50, 100, 2.0, ON));
        assertEquals(140, PriceDynamics.recover(150, 100, 1.0, ON));
    }

    @Test
    void recoverSmallStepStillProgresses() {
        long r = PriceDynamics.recover(95, 100, 1 / 60.0, ON);
        assertTrue(r > 95 && r <= 100);
    }

    @Test
    void recoverDoesNotOvershoot() {
        assertEquals(100, PriceDynamics.recover(95, 100, 1.0, ON));
        assertEquals(100, PriceDynamics.recover(105, 100, 1.0, ON));
    }

    @Test
    void deltaEmptyWhenEqual() {
        assertEquals(Optional.empty(), PriceDynamics.delta(100, 100));
        assertEquals(Optional.of(-20), PriceDynamics.delta(80, 100));
    }
}
