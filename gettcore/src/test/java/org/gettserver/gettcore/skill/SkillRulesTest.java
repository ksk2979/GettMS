package org.gettserver.gettcore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SkillRulesTest {
    private static SkillSpec spec(String id, boolean enabled, double min, double max, double cooldownSeconds) {
        return new SkillSpec(id, SkillSpec.WIND_LINE, enabled, false, "", 10, 1, min, max, 1.0, cooldownSeconds,
                new SkillSpec.Shape(30, 3, 0), new SkillSpec.Aftershock(0, 0, 0), new SkillSpec.Terrain(false, 0, 0));
    }

    private static final SkillSpec RAMGAK = spec("ramgak", true, 8, 32, 8);
    private static final SkillSpec STOMP = spec("stomp", true, 0, 6, 12);

    @Test void picksFirstSkillInRange() {
        List<SkillSpec> list = List.of(RAMGAK, STOMP);
        assertEquals(Optional.of(RAMGAK), SkillRules.pick(list, 12, Map.of(), 400));
        assertEquals(Optional.of(STOMP), SkillRules.pick(list, 4, Map.of(), 400));
    }

    @Test void skipsSkillOnCooldown() {
        assertEquals(Optional.empty(), SkillRules.pick(List.of(RAMGAK, STOMP), 12, Map.of("ramgak", 500L), 400));
        assertEquals(Optional.of(RAMGAK), SkillRules.pick(List.of(RAMGAK, STOMP), 12, Map.of("ramgak", 400L), 400));
    }

    @Test void skipsDisabledSkill() {
        SkillSpec off = spec("ramgak", false, 8, 32, 8);
        SkillSpec other = spec("other", true, 8, 32, 8);
        assertEquals(Optional.of(other), SkillRules.pick(List.of(off, other), 12, Map.of(), 400));
    }

    @Test void emptyListPicksNothing() {
        assertEquals(Optional.empty(), SkillRules.pick(List.of(), 12, Map.of(), 400));
    }

    @Test void cooldownsKeyedById() {
        SkillSpec a = spec("a", true, 0, 32, 8);
        SkillSpec b = spec("b", true, 0, 32, 8);
        Map<String, Long> ready = Map.of("a", 500L);
        assertEquals(Optional.of(b), SkillRules.pick(List.of(a, b), 4, ready, 400));
        assertEquals(Optional.of(b), SkillRules.pick(List.of(b, a), 4, ready, 400));
    }

    @Test void enrageShortensCooldown() {
        assertEquals(96, SkillRules.cooldownTicks(RAMGAK, 0.29, 0.3, 0.6));
        assertEquals(160, SkillRules.cooldownTicks(RAMGAK, 0.31, 0.3, 0.6));
    }

    @Test void enrageBelowZeroDisablesEnrage() {
        assertEquals(160, SkillRules.cooldownTicks(RAMGAK, 0.0, 0.0, 0.6));
    }

    @Test void creativeAndSpectatorAreNotTargets() {
        assertFalse(SkillRules.validTarget(true, true, true, true));
        assertTrue(SkillRules.validTarget(true, true, false, true));
        assertFalse(SkillRules.validTarget(false, true, false, true));
        assertFalse(SkillRules.validTarget(true, false, false, true));
    }

    @Test void wildMobIsNotATarget() {
        assertFalse(SkillRules.validTarget(true, true, false, false));
    }

    @Test void shoutOnlyReachesNearbyPlayers() {
        assertTrue(SkillRules.hearsShout(24, 24));
        assertFalse(SkillRules.hearsShout(24.1, 24));
        assertFalse(SkillRules.hearsShout(0, 0));
    }
}
