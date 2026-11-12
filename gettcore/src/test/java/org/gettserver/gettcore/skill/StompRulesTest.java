package org.gettserver.gettcore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class StompRulesTest {
    private static final Vec3 CENTER = new Vec3(0, 0, 0);

    @Test void pushIsAwayAndUp() {
        Vec3 p = StompSkill.push(CENTER, new Vec3(3, 0, 0), 1.6);
        assertTrue(p.x > 0);
        assertEquals(0.0, p.z);
        assertEquals(0.8, p.y, 1e-9);
    }

    @Test void victimOnCenterGoesStraightUp() {
        Vec3 p = StompSkill.push(CENTER, CENTER, 1.6);
        assertFalse(Double.isNaN(p.x) || Double.isNaN(p.y) || Double.isNaN(p.z));
        assertEquals(0.0, p.x);
        assertEquals(0.0, p.z);
        assertTrue(p.y > 0);
    }

    @Test void radiusIsInclusive() {
        assertTrue(StompSkill.inRadius(CENTER, new Vec3(6.0, 0, 0), 6));
        assertFalse(StompSkill.inRadius(CENTER, new Vec3(6.01, 0, 0), 6));
    }
}
