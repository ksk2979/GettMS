package org.gettserver.gettcore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ChargeSkillTest {
    @Test void dashTargetIsHorizontalAndCapped() {
        assertEquals(new Vec3(10, 64, 0), ChargeSkill.dashTarget(new Vec3(0, 64, 0), new Vec3(10, 70, 0), 24));
        assertEquals(new Vec3(24, 64, 0), ChargeSkill.dashTarget(new Vec3(0, 64, 0), new Vec3(40, 64, 0), 24));
        assertEquals(new Vec3(0, 64, 0), ChargeSkill.dashTarget(new Vec3(0, 64, 0), new Vec3(0, 80, 0), 24));
    }

    @Test void stepsPerTickRoundsAndIsAtLeastOne() {
        assertEquals(2, ChargeSkill.stepsPerTick(30));
        assertEquals(1, ChargeSkill.stepsPerTick(5));
        assertEquals(5, ChargeSkill.stepsPerTick(100));
    }
}
