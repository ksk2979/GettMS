package org.gettserver.gettcore.reinforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.gettserver.gettcore.reinforce.ReinforceRules.Target;
import org.junit.jupiter.api.Test;

/** Pure parts of {@link ReinforceService}: the apply gate and the outcome message key. */
class ReinforceServiceTest {

    private static final ReinforceConfig D = ReinforceConfig.DEFAULT;

    @Test
    void canApplyNeedsTarget() {
        assertFalse(ReinforceService.canApply(Optional.empty(), 0, 64, D));
        for (Target t : Target.values()) {
            assertTrue(ReinforceService.canApply(Optional.of(t), 0, 1, D), t.name());
        }
    }

    @Test
    void canApplyNeedsLevelBelowMax() {
        assertTrue(ReinforceService.canApply(Optional.of(Target.MELEE), D.maxLevel() - 1, 64, D));
        assertFalse(ReinforceService.canApply(Optional.of(Target.MELEE), D.maxLevel(), 64, D));
        assertFalse(ReinforceService.canApply(Optional.of(Target.MELEE), D.maxLevel() + 5, 64, D));
        assertFalse(ReinforceService.canApply(Optional.of(Target.MELEE), -1, 64, D));
    }

    @Test
    void canApplyNeedsEnoughStonesForThisLevel() {
        List<Integer> cost = new java.util.ArrayList<>(Collections.nCopies(D.maxLevel(), 1));
        cost.set(5, 3);
        ReinforceConfig c = new ReinforceConfig(D.maxLevel(), D.successPercent(), cost,
                D.gunDamagePercentPerLevel(), D.meleeDamagePercentPerLevel(), D.shieldSpeedPercentPerLevel(),
                D.armorHealthPerLevel(), D.armorHealthMax());
        assertFalse(ReinforceService.canApply(Optional.of(Target.ARMOR), 0, 0, c));
        assertTrue(ReinforceService.canApply(Optional.of(Target.ARMOR), 0, 1, c));
        assertFalse(ReinforceService.canApply(Optional.of(Target.ARMOR), 5, 2, c));
        assertTrue(ReinforceService.canApply(Optional.of(Target.ARMOR), 5, 3, c));
        assertTrue(ReinforceService.canApply(Optional.of(Target.ARMOR), 6, 1, c));
    }

    @Test
    void outcomeMessageKeys() {
        assertEquals("gettcore.msg.reinforce.success", ReinforceService.outcomeMessageKey(true));
        assertEquals("gettcore.msg.reinforce.fail", ReinforceService.outcomeMessageKey(false));
    }
}
