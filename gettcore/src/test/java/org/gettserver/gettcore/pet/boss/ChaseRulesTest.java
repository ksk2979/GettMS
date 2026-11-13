package org.gettserver.gettcore.pet.boss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.gettserver.gettcore.pet.boss.ChaseRules.Ground;
import org.gettserver.gettcore.pet.boss.ChaseRules.Mode;
import org.gettserver.gettcore.skill.BossConfig;
import org.junit.jupiter.api.Test;

class ChaseRulesTest {
    @Test void groundSpeedIsQuadraticInTheMoveSpeed() {
        // Mob.setSpeed puts the speed into zza as well, so the push per tick is speed x speed x 0.98.
        assertEquals(2.28, ChaseRules.groundBlocksPerSecond(0.23), 0.01);   // a zombie's walk
        assertEquals(4 * ChaseRules.groundBlocksPerSecond(0.1), ChaseRules.groundBlocksPerSecond(0.2), 1e-9);
        assertEquals(0, ChaseRules.groundBlocksPerSecond(0), 1e-9);
    }

    @Test void bossBearChasesAtAboutSevenPointThreeBlocksPerSecond() {
        double v = BossConfig.BEAR_SPEED * ChaseRules.SPRINT_FACTOR * BossBehavior.BEAR_CHASE_MODIFIER;
        assertEquals(7.3, ChaseRules.groundBlocksPerSecond(v), 0.05);
    }

    @Test void stuckWhenItCoversLessThanAQuarterOfTheExpectedWay() {
        double v = 0.4;   // 6.9 blocks/s: 3.45 blocks in 10 ticks
        assertTrue(ChaseRules.stuck(0.0, v, 10));
        assertTrue(ChaseRules.stuck(0.8, v, 10));
        assertFalse(ChaseRules.stuck(0.9, v, 10));
        assertFalse(ChaseRules.stuck(3.4, v, 10));
    }

    @Test void aSlowBossIsJudgedByItsOwnSpeed() {
        double v = 0.2;   // 1.73 blocks/s: 0.86 blocks in 10 ticks
        assertFalse(ChaseRules.stuck(0.5, v, 10));
        assertTrue(ChaseRules.stuck(0.1, v, 10));
    }

    @Test void aStillMobIsNeverStuck() {
        assertFalse(ChaseRules.stuck(0.0, 0.0, 10));
    }

    @Test void aStallOnThePathMeansWalkingStraightForASecond() {
        assertEquals(ChaseRules.DIRECT_HOLD_TICKS, ChaseRules.straightTicksAfterWindow(true, false));
        assertEquals(0, ChaseRules.straightTicksAfterWindow(false, false));
    }

    @Test void aStallWhileWalkingStraightGivesThePathItsTurnAgain() {
        // A tree in the straight line must not keep the boss pressed against it: the path may go round.
        assertEquals(0, ChaseRules.straightTicksAfterWindow(true, true));
    }

    @Test void castingAndReachComeFirst() {
        assertEquals(Mode.CAST, ChaseRules.mode(true, true, false, true));
        assertEquals(Mode.ATTACK, ChaseRules.mode(false, true, false, true));
    }

    @Test void followsAWorkingPath() {
        assertEquals(Mode.PATH, ChaseRules.mode(false, false, true, false));
    }

    @Test void walksStraightWithoutAPathOrWhenStuck() {
        assertEquals(Mode.DIRECT, ChaseRules.mode(false, false, false, false));
        assertEquals(Mode.DIRECT, ChaseRules.mode(false, false, true, true));
    }

    @Test void groundAheadIsSafeOnAFloorOrAWall() {
        assertTrue(ChaseRules.safeAhead(List.of(Ground.AIR, Ground.SOLID, Ground.AIR, Ground.AIR, Ground.AIR)));
        assertTrue(ChaseRules.safeAhead(List.of(Ground.SOLID, Ground.AIR, Ground.AIR, Ground.AIR, Ground.AIR)));
        // a step down of three blocks
        assertTrue(ChaseRules.safeAhead(List.of(Ground.AIR, Ground.AIR, Ground.AIR, Ground.AIR, Ground.SOLID)));
        assertTrue(ChaseRules.safeAhead(List.of(Ground.AIR, Ground.AIR, Ground.WATER, Ground.AIR, Ground.AIR)));
    }

    @Test void cliffsAndLavaAreNotWalkedInto() {
        assertFalse(ChaseRules.safeAhead(List.of(Ground.AIR, Ground.AIR, Ground.AIR, Ground.AIR, Ground.AIR)));
        assertFalse(ChaseRules.safeAhead(List.of(Ground.AIR, Ground.HAZARD, Ground.SOLID, Ground.AIR, Ground.AIR)));
        assertFalse(ChaseRules.safeAhead(List.of(Ground.AIR, Ground.AIR, Ground.HAZARD, Ground.SOLID, Ground.AIR)));
        assertFalse(ChaseRules.safeAhead(List.of()));
    }
}
