package org.gettserver.gettcore.reinforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void armorRemovalClampIsNotDamage() {
        assertTrue(HealthRestore.clampedByMaxDrop(35f, 35f, 20f, 20f));
        // 진짜 피해(최대 체력 그대로)
        assertFalse(HealthRestore.clampedByMaxDrop(35f, 35f, 30f, 35f));
        // 다쳐 있던 상태에서 벗음 → 체력 변화 없음
        assertFalse(HealthRestore.clampedByMaxDrop(15f, 35f, 15f, 20f));
        // 벗은 틱에 피해까지 받아 새 최대치 아래 → 피격 연출 유지
        assertFalse(HealthRestore.clampedByMaxDrop(35f, 35f, 18f, 20f));
    }
}
