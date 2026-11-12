package org.gettserver.gettcore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.gettserver.gettcore.skill.AggroRules.Memory;
import org.gettserver.gettcore.skill.BossConfig.Aggro;
import org.junit.jupiter.api.Test;

class AggroRulesTest {
    private static final Memory ANGRY0 = new Memory(true, 0);

    @Test void afterModeCalmBossIgnoresNearbyPlayer() {
        assertFalse(AggroRules.mayAcquirePlayer(Aggro.AFTER, Memory.CALM, 3, 16));
    }

    @Test void playerHitMakesAngry() {
        assertEquals(new Memory(true, 100), AggroRules.onHurt(Memory.CALM, true, 100));
    }

    @Test void mobHitDoesNotMakeAngry() {
        assertEquals(Memory.CALM, AggroRules.onHurt(Memory.CALM, false, 100));
    }

    @Test void petOrProjectileCountsAsPlayer() {
        assertTrue(AggroRules.playerSide(false, true));
        assertTrue(AggroRules.playerSide(true, false));
        assertFalse(AggroRules.playerSide(false, false));
    }

    @Test void angryBossAcquiresAnyPlayerInReaggroRange() {
        assertTrue(AggroRules.mayAcquirePlayer(Aggro.AFTER, ANGRY0, 15.9, 16));
        assertFalse(AggroRules.mayAcquirePlayer(Aggro.AFTER, ANGRY0, 16.1, 16));
    }

    @Test void calmsAfterFiveMinutesUnseen() {
        assertFalse(AggroRules.tick(ANGRY0, false, 6000, 300).angry());
        assertTrue(AggroRules.tick(ANGRY0, false, 5999, 300).angry());
    }

    @Test void seeingAPlayerRestartsTheTimer() {
        assertEquals(new Memory(true, 5000), AggroRules.tick(ANGRY0, true, 5000, 300));
    }

    @Test void calmSecondsZeroNeverCalms() {
        assertTrue(AggroRules.tick(ANGRY0, false, 10_000_000, 0).angry());
    }

    @Test void releaseOnlyInAfterMode() {
        assertTrue(AggroRules.shouldRelease(Aggro.AFTER, 41, 40));
        assertFalse(AggroRules.shouldRelease(Aggro.AFTER, 40, 40));
        assertFalse(AggroRules.shouldRelease(Aggro.FIRST, 999, 40));
    }

    @Test void modeChangeAppliesNextTick() {
        assertTrue(AggroRules.mayAcquirePlayer(Aggro.FIRST, Memory.CALM, 3, 16));
        assertFalse(AggroRules.mayAcquirePlayer(Aggro.AFTER, Memory.CALM, 3, 16));
    }

    @Test void firstModeAlwaysAcquires() {
        assertTrue(AggroRules.mayAcquirePlayer(Aggro.FIRST, Memory.CALM, 999, 16));
        assertTrue(AggroRules.mayAcquirePlayer(Aggro.FIRST, ANGRY0, 999, 16));
    }
}
