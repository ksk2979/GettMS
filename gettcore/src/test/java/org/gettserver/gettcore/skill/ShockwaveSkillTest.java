package org.gettserver.gettcore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShockwaveSkillTest {
    @Test void radiusGrowsBySpeedPerTickAndStopsAtMax() {
        assertEquals(1.2, ShockwaveSkill.radiusAt(0, 24, 12), 1.0E-9);
        assertEquals(12, ShockwaveSkill.radiusAt(9, 24, 12), 1.0E-9);
        assertEquals(12, ShockwaveSkill.radiusAt(30, 24, 12), 1.0E-9);
    }

    @Test void reachedOnlyInTheTickTheFrontPassesIt() {
        assertTrue(ShockwaveSkill.reached(0, 1.2, 0));
        assertTrue(ShockwaveSkill.reached(1.2, 2.4, 2.4));
        assertFalse(ShockwaveSkill.reached(1.2, 2.4, 1.2));
        assertFalse(ShockwaveSkill.reached(1.2, 2.4, 2.5));
    }
}
