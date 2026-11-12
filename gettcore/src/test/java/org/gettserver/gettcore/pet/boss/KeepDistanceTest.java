package org.gettserver.gettcore.pet.boss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.gettserver.gettcore.pet.boss.KeepDistanceGoal.Move;
import org.junit.jupiter.api.Test;

class KeepDistanceTest {
    @Test void retreatsWhenTooClose() {
        assertEquals(Move.RETREAT, KeepDistanceGoal.decide(9, 12));
        assertEquals(Move.RETREAT, KeepDistanceGoal.decide(9.9, 12));
    }

    @Test void holdsNearThePreferredDistance() {
        assertEquals(Move.HOLD, KeepDistanceGoal.decide(10, 12));
        assertEquals(Move.HOLD, KeepDistanceGoal.decide(12, 12));
        assertEquals(Move.HOLD, KeepDistanceGoal.decide(16, 12));
    }

    @Test void followsAPlayerWhoBacksOff() {
        assertEquals(Move.APPROACH, KeepDistanceGoal.decide(16.1, 12));
        assertEquals(Move.APPROACH, KeepDistanceGoal.decide(20, 12));
        assertEquals(Move.APPROACH, KeepDistanceGoal.decide(33, 12));
    }

    @Test void zeroPreferredNeverRetreats() {
        assertNotEquals(Move.RETREAT, KeepDistanceGoal.decide(1, 0));
    }

    @Test void keepsDistanceFromASkillTarget() {
        assertEquals(Move.RETREAT, KeepDistanceGoal.move(9, 12, true, true));
        assertEquals(Move.HOLD, KeepDistanceGoal.move(14, 12, true, true));
        assertEquals(Move.APPROACH, KeepDistanceGoal.move(20, 12, true, true));
    }

    @Test void chasesWithoutAnEnabledSkill() {
        assertEquals(Move.APPROACH, KeepDistanceGoal.move(1, 12, false, true));
        assertEquals(Move.APPROACH, KeepDistanceGoal.move(11, 12, false, true));
    }

    @Test void chasesATargetSkillsCannotHit() {
        assertEquals(Move.APPROACH, KeepDistanceGoal.move(9, 12, true, false));
        assertEquals(Move.APPROACH, KeepDistanceGoal.move(14, 12, true, false));
    }

    @Test void chasesWithZeroPreferredDistance() {
        assertEquals(Move.APPROACH, KeepDistanceGoal.move(1, 0, true, true));
        assertEquals(Move.APPROACH, KeepDistanceGoal.move(14, 0, true, true));
    }
}
