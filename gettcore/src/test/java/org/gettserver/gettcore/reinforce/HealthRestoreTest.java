package org.gettserver.gettcore.reinforce;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HealthRestoreTest {
    @Test
    void restoresOnceMaxHealthIsBack() {
        assertEquals(35f, HealthRestore.decide(35f, 20f, 38f, 20));
    }

    @Test
    void waitsWhileEquipmentNotApplied() {
        assertEquals(-1f, HealthRestore.decide(35f, 20f, 20f, 5));
    }

    @Test
    void givesUpAtDeadlineWithCurrentMax() {
        assertEquals(26f, HealthRestore.decide(35f, 20f, 26f, 0));
    }

    @Test
    void nothingToDoWhenAlreadyHigher() {
        assertEquals(30f, HealthRestore.decide(25f, 30f, 40f, 20));
    }
}
