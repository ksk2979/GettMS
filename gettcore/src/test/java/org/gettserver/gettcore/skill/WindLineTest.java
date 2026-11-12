package org.gettserver.gettcore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.gettserver.gettcore.skill.WindLineSkill.Line;
import org.gettserver.gettcore.skill.WindLineSkill.Shock;
import org.junit.jupiter.api.Test;

/** The level-free bookkeeping inside {@link WindLineSkill}. */
class WindLineTest {
    private static final Vec3 X = new Vec3(1, 0, 0);

    @Test void eachVictimHitOncePerCast() {
        Line line = new Line(Vec3.ZERO, X, 30, 1.5, 4, 0);
        Vec3 victim = new Vec3(1.5, 0, 0.5); // on the border of sections 0 and 1
        assertTrue(line.sweepHits(line.from(0), line.to(0), 7, victim));
        assertFalse(line.sweepHits(line.from(1), line.to(1), 7, victim));
        assertTrue(line.sweepHits(line.from(1), line.to(1), 8, new Vec3(2.5, 0, 0)));
        assertFalse(line.sweepHits(line.from(0), line.to(0), 9, new Vec3(2.5, 0, 0)), "not in this section yet");
    }

    @Test void pendingAftershocksSurviveCaster() {
        Line line = new Line(Vec3.ZERO, X, 30, 1.5, 4, 0);
        UUID caster = UUID.randomUUID();
        List<Shock> all = new ArrayList<>();
        SkillSpec.Terrain terrain = new SkillSpec.Terrain(true, 5, 10);
        for (int age = 0; WindLineSkill.sweeping(age, 30); age++) {
            all.addAll(line.passedShocks(line.to(age), 8, 3, terrain, caster));
        }
        assertEquals(8, all.size(), "every segment exactly once");
        assertEquals(new Vec3(2, 0, 0), all.get(0).point());
        assertEquals(caster, all.get(0).casterId());
        assertEquals(8.0, all.get(0).damage());
        assertEquals(3.0, all.get(0).radius());
        assertEquals(terrain, all.get(0).terrain());
        for (RecordComponent c : Shock.class.getRecordComponents()) {
            assertFalse(Entity.class.isAssignableFrom(c.getType()), c.getName() + " holds an entity");
        }
    }

    /** Solid at and below y 64: the surface (top of the ground) is y 65. */
    private static final java.util.function.IntPredicate GROUND_64 = y -> y <= 64;

    @Test void shockInAirLandsOnTheGroundBelow() {
        assertEquals(OptionalDouble.of(65), WindLineSkill.groundY(70.3, GROUND_64));
        assertEquals(OptionalDouble.of(65), WindLineSkill.groundY(65.0, GROUND_64), "already on the surface");
    }

    @Test void shockUnderTheSurfaceRisesToIt() {
        assertEquals(OptionalDouble.of(65), WindLineSkill.groundY(61.5, GROUND_64), "3 blocks under the surface");
        assertEquals(OptionalDouble.of(65), WindLineSkill.groundY(53.0, GROUND_64), "surface 12 up still found");
    }

    @Test void shockBuriedDeeperThanTheScanIsSkipped() {
        assertEquals(OptionalDouble.empty(), WindLineSkill.groundY(52.0, GROUND_64), "surface 13 up");
        assertEquals(OptionalDouble.empty(), WindLineSkill.groundY(10.0, y -> true));
    }

    @Test void shockOverDeepAirStaysAtThePoint() {
        assertEquals(OptionalDouble.of(90.5), WindLineSkill.groundY(90.5, GROUND_64), "ground 26 down");
        assertEquals(OptionalDouble.of(77.0), WindLineSkill.groundY(77.0, GROUND_64), "ground 13 down");
        assertEquals(OptionalDouble.of(65), WindLineSkill.groundY(76.0, GROUND_64), "ground 12 down");
    }

    @Test void frontReachesLengthIn20Ticks() {
        for (int age = 0; age < 20; age++) assertTrue(WindLineSkill.sweeping(age, 30), "age " + age);
        assertFalse(WindLineSkill.sweeping(20, 30));
        Line line = new Line(Vec3.ZERO, X, 30, 1.5, 4, 0);
        assertEquals(28.5, line.from(19), 1e-9);
        assertEquals(30.0, line.to(19), 1e-9);
    }
}
