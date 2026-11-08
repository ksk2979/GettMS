package org.gettserver.gettcore.reinforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.gettserver.gettcore.reinforce.ReinforceRules.Target;
import org.junit.jupiter.api.Test;

/**
 * Pure rules. {@link ReinforceRules#target} / {@link ReinforceRules#level} need live item registries (and the
 * component holder), so only the classification priority ({@link ReinforceRules#classify}) is tested here.
 */
class ReinforceRulesTest {

    private static final ReinforceConfig D = ReinforceConfig.DEFAULT;
    private static final double EPS = 1e-9;

    @Test
    void gunOrRangedMainHandBlocksShield() {
        assertTrue(ReinforceRules.blocksShield(true, false), "gun");
        assertTrue(ReinforceRules.blocksShield(false, true), "bow / crossbow / trident");
        assertTrue(ReinforceRules.blocksShield(true, true));
        assertFalse(ReinforceRules.blocksShield(false, false), "sword, shield, empty, ...");
    }

    @Test
    void gunWinsOverEverything() {
        assertEquals(Optional.of(Target.GUN), ReinforceRules.classify(true, true, true, true));
        assertEquals(Optional.of(Target.GUN), ReinforceRules.classify(true, false, false, false));
    }

    @Test
    void meleeThenShieldThenArmor() {
        assertEquals(Optional.of(Target.MELEE), ReinforceRules.classify(false, true, true, true));
        assertEquals(Optional.of(Target.SHIELD), ReinforceRules.classify(false, false, true, true));
        assertEquals(Optional.of(Target.ARMOR), ReinforceRules.classify(false, false, false, true));
        assertEquals(Optional.empty(), ReinforceRules.classify(false, false, false, false));
    }

    @Test
    void canReinforceBelowMax() {
        assertTrue(ReinforceRules.canReinforce(0, D));
        assertTrue(ReinforceRules.canReinforce(29, D));
        assertFalse(ReinforceRules.canReinforce(30, D));
        assertFalse(ReinforceRules.canReinforce(35, D));
        assertFalse(ReinforceRules.canReinforce(-1, D));
    }

    @Test
    void costAndPercentPerLevel() {
        assertEquals(100.0, ReinforceRules.successPercent(0, D));
        assertEquals(90.0, ReinforceRules.successPercent(3, D));
        assertEquals(10.0, ReinforceRules.successPercent(19, D));
        assertEquals(5.0, ReinforceRules.successPercent(29, D));
        assertEquals(0.0, ReinforceRules.successPercent(30, D));
        assertEquals(1, ReinforceRules.stoneCost(0, D));
        assertEquals(0, ReinforceRules.stoneCost(30, D));
        ReinforceConfig c = new ReinforceConfig(3, List.of(100.0, 50.0, 0.0), List.of(1, 2, 3), 10, 10, 2, 1.5, 60);
        assertEquals(3, ReinforceRules.stoneCost(2, c));
        assertEquals(50.0, ReinforceRules.successPercent(1, c));
    }

    @Test
    void rollBoundaries() {
        ReinforceConfig c = new ReinforceConfig(3, List.of(100.0, 50.0, 0.0), List.of(1, 1, 1), 10, 10, 2, 1.5, 60);
        assertTrue(ReinforceRules.roll(0, c, 0.0));
        assertTrue(ReinforceRules.roll(0, c, 0.999999));
        assertTrue(ReinforceRules.roll(1, c, 0.499999));
        assertFalse(ReinforceRules.roll(1, c, 0.5));
        assertFalse(ReinforceRules.roll(2, c, 0.0));
        assertFalse(ReinforceRules.roll(3, c, 0.0), "at max level");
    }

    @Test
    void effectValues() {
        assertEquals(1.0, ReinforceRules.gunMultiplier(0, D), EPS);
        assertEquals(3.0, ReinforceRules.gunMultiplier(20, D), EPS);
        assertEquals(0.5, ReinforceRules.meleeFraction(5, D), EPS);
        assertEquals(0.34, ReinforceRules.shieldSpeedFraction(20, D), EPS);
        assertEquals(0.51, ReinforceRules.shieldSpeedFraction(30, D), EPS);
        assertEquals(15.0, ReinforceRules.armorHealth(10, D), EPS);
        assertEquals(0.0, ReinforceRules.armorHealth(0, D), EPS);
    }

    @Test
    void healthCapAdjust() {
        assertEquals(-10.0, ReinforceRules.healthCapAdjust(70, D), EPS);
        assertEquals(0.0, ReinforceRules.healthCapAdjust(50, D), EPS);
        assertEquals(0.0, ReinforceRules.healthCapAdjust(60, D), EPS);
        ReinforceConfig noCap = new ReinforceConfig(20, D.successPercent(), D.stoneCost(), 10, 10, 2, 1.5, 0);
        assertEquals(0.0, ReinforceRules.healthCapAdjust(500, noCap), EPS);
    }
}
