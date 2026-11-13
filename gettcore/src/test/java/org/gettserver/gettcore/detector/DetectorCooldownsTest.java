package org.gettserver.gettcore.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DetectorCooldownsTest {
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void freshIsReady() {
        assertEquals(0, new DetectorCooldowns().remainingTicks(alice, "detector", 100));
    }

    @Test
    void countsDown() {
        DetectorCooldowns c = new DetectorCooldowns();
        c.start(alice, "detector", 100, 1200);
        assertEquals(1200, c.remainingTicks(alice, "detector", 100));
        assertEquals(600, c.remainingTicks(alice, "detector", 700));
        assertEquals(0, c.remainingTicks(alice, "detector", 1300));
        assertEquals(0, c.remainingTicks(alice, "detector", 5000));
    }

    @Test
    void keysAreIndependent() {
        DetectorCooldowns c = new DetectorCooldowns();
        c.start(alice, "ore:iron", 0, 1200);
        assertEquals(1000, c.remainingTicks(alice, "ore:iron", 200));
        assertEquals(0, c.remainingTicks(alice, "ore:copper", 200));
    }

    @Test
    void playersAreIndependent() {
        DetectorCooldowns c = new DetectorCooldowns();
        c.start(alice, "detector", 0, 1200);
        assertEquals(0, c.remainingTicks(bob, "detector", 10));
    }

    @Test
    void zeroCooldownAlwaysReady() {
        DetectorCooldowns c = new DetectorCooldowns();
        c.start(alice, "detector", 50, 0);
        assertEquals(0, c.remainingTicks(alice, "detector", 50));
    }

    @Test
    void forgetClearsEveryKeyOfThatPlayerOnly() {
        DetectorCooldowns c = new DetectorCooldowns();
        c.start(alice, "detector", 0, 1200);
        c.start(alice, "ore:iron", 0, 1200);
        c.start(alice, "ore:copper", 0, 1200);
        c.start(bob, "detector", 0, 1200);
        c.forget(alice);
        assertEquals(0, c.remainingTicks(alice, "detector", 10));
        assertEquals(0, c.remainingTicks(alice, "ore:iron", 10));
        assertEquals(0, c.remainingTicks(alice, "ore:copper", 10));
        assertEquals(1190, c.remainingTicks(bob, "detector", 10));
    }

    @Test
    void clearResets() {
        DetectorCooldowns c = new DetectorCooldowns();
        c.start(alice, "detector", 0, 1200);
        c.clear();
        assertEquals(0, c.remainingTicks(alice, "detector", 10));
    }
}
