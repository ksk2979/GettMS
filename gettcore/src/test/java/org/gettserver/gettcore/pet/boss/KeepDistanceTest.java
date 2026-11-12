package org.gettserver.gettcore.pet.boss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.gettserver.gettcore.pet.boss.KeepDistanceGoal.Move;
import org.junit.jupiter.api.Test;

class KeepDistanceTest {
    @Test void retreatsWhenTooClose() {
        assertEquals(Move.RETREAT, KeepDistanceGoal.decide(9, 12, 32));
    }

    @Test void holdsInTheBand() {
        assertEquals(Move.HOLD, KeepDistanceGoal.decide(11, 12, 32));
        assertEquals(Move.HOLD, KeepDistanceGoal.decide(20, 12, 32));
    }

    @Test void approachesBeyondSkillRange() {
        assertEquals(Move.APPROACH, KeepDistanceGoal.decide(33, 12, 32));
    }

    @Test void zeroPreferredNeverRetreats() {
        assertNotEquals(Move.RETREAT, KeepDistanceGoal.decide(1, 0, 6));
    }

    @Test void keepsDistanceFromASkillTarget() {
        assertEquals(Move.RETREAT, KeepDistanceGoal.move(9, 12, 32, true));
        assertEquals(Move.HOLD, KeepDistanceGoal.move(20, 12, 32, true));
        assertEquals(Move.APPROACH, KeepDistanceGoal.move(33, 12, 32, true));
    }

    @Test void chasesWithoutAnEnabledSkill() {
        assertEquals(Move.APPROACH, KeepDistanceGoal.move(1, 12, 0, true));
        assertEquals(Move.APPROACH, KeepDistanceGoal.move(11, 12, 0, true));
    }

    @Test void chasesATargetSkillsCannotHit() {
        assertEquals(Move.APPROACH, KeepDistanceGoal.move(9, 12, 32, false));
        assertEquals(Move.APPROACH, KeepDistanceGoal.move(20, 12, 32, false));
    }

    @Test void chasesWithZeroPreferredDistance() {
        assertEquals(Move.APPROACH, KeepDistanceGoal.move(1, 0, 32, true));
        assertEquals(Move.APPROACH, KeepDistanceGoal.move(20, 0, 32, true));
    }
}
