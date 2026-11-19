package org.gettserver.gettcore.compat;

import static org.junit.jupiter.api.Assertions.*;

import org.gettserver.gettcore.compat.AutoModeRules.Action;
import org.gettserver.gettcore.compat.AutoModeRules.Mode;
import org.junit.jupiter.api.Test;

/** Epic Fight 모드 자동 전환 규칙: 주 손 무기 → 전투 모드, 그 밖 → 기본 모드, 시전·감정표현 중에는 잠깐 기본 모드. */
class AutoModeRulesTest {
    @Test
    void weaponsWithAttackMotionsGoToEpicFightMode() {
        for (String c : new String[] {"SWORD", "LONGSWORD", "GREATSWORD", "TACHI", "UCHIGATANA", "SPEAR", "DAGGER", "TRIDENT",
                "sword", "RUINE", "SOME_MOD_CATEGORY"}) {
            assertEquals(Mode.EPIC_FIGHT, AutoModeRules.forMainHand(c, false), c);
        }
    }

    @Test
    void toolsBowsShieldsAndBareHandsStayVanilla() {
        for (String c : new String[] {null, "", "NOT_WEAPON", "PICKAXE", "SHOVEL", "HOE", "SHIELD", "RANGED"}) {
            assertEquals(Mode.VANILLA, AutoModeRules.forMainHand(c, false), String.valueOf(c));
        }
        assertEquals(Mode.VANILLA, AutoModeRules.forMainHand("PICKAXE", true));
    }

    /** Gloves and clawed gauntlets are fist-category items; an empty hand has no category at all. */
    @Test
    void fistWeaponsGoToEpicFightMode() {
        assertEquals(Mode.EPIC_FIGHT, AutoModeRules.forMainHand("FIST", false));
        assertEquals(Mode.EPIC_FIGHT, AutoModeRules.forMainHand("fist", false));
    }

    @Test
    void anAxeIsAWeaponUnlessItIsADiggingTool() {
        assertEquals(Mode.VANILLA, AutoModeRules.forMainHand("AXE", true));
        assertEquals(Mode.EPIC_FIGHT, AutoModeRules.forMainHand("AXE", false));
    }

    @Test
    void settingIsOnUnlessTurnedOff() {
        assertTrue(AutoModeRules.enabled(null));
        assertTrue(AutoModeRules.enabled("on"));
        assertTrue(AutoModeRules.enabled("anything"));
        assertFalse(AutoModeRules.enabled("off"));
        assertFalse(AutoModeRules.enabled(" FALSE "));
    }

    @Test
    void switchesOnlyWhenTheHeldItemChanges() {
        // picked up a sword in vanilla mode / a pickaxe in Epic Fight mode
        assertEquals(Action.TO_EPIC, AutoModeRules.decide(false, false, false, true, Mode.EPIC_FIGHT));
        assertEquals(Action.TO_VANILLA, AutoModeRules.decide(false, true, false, true, Mode.VANILLA));
        // already in the right mode: only remember the item
        assertEquals(Action.REMEMBER, AutoModeRules.decide(false, true, false, true, Mode.EPIC_FIGHT));
        assertEquals(Action.REMEMBER, AutoModeRules.decide(false, false, false, true, Mode.VANILLA));
        // same item as last time: a manual toggle (sword in vanilla mode, pickaxe in Epic Fight mode) is kept
        assertEquals(Action.NONE, AutoModeRules.decide(false, false, false, false, Mode.EPIC_FIGHT));
        assertEquals(Action.NONE, AutoModeRules.decide(false, true, false, false, Mode.VANILLA));
    }

    @Test
    void castingOrEmotingSuspendsEpicFightModeAndRestoresItAfterwards() {
        // cast starts with a sword out
        Action start = AutoModeRules.decide(true, true, false, false, Mode.EPIC_FIGHT);
        assertEquals(Action.SUSPEND, start);
        assertEquals(Mode.VANILLA, start.switchTo);
        assertTrue(start.startsSuspend);
        assertFalse(start.remember);
        // while it lasts nothing more happens, even if the held item changes
        assertEquals(Action.NONE, AutoModeRules.decide(true, false, true, false, Mode.EPIC_FIGHT));
        assertEquals(Action.NONE, AutoModeRules.decide(true, false, true, true, Mode.VANILLA));
        // it ends with the same item: back to Epic Fight mode
        Action end = AutoModeRules.decide(false, false, true, false, Mode.EPIC_FIGHT);
        assertEquals(Action.RESUME_EPIC, end);
        assertTrue(end.endsSuspend);
        // it ends with a pickaxe in hand: stay vanilla
        Action other = AutoModeRules.decide(false, false, true, true, Mode.VANILLA);
        assertNull(other.switchTo);
        assertTrue(other.endsSuspend);
        assertTrue(other.remember);
        // it ends with another weapon in hand: Epic Fight mode for that one
        assertEquals(Mode.EPIC_FIGHT, AutoModeRules.decide(false, false, true, true, Mode.EPIC_FIGHT).switchTo);
    }

    @Test
    void castingInVanillaModeChangesNothing() {
        assertEquals(Action.NONE, AutoModeRules.decide(true, false, false, false, Mode.VANILLA));
        // and a manual Epic Fight toggle with a pickaxe is restored after the cast
        assertEquals(Action.SUSPEND, AutoModeRules.decide(true, true, false, false, Mode.VANILLA));
        assertEquals(Action.RESUME_EPIC, AutoModeRules.decide(false, false, true, false, Mode.VANILLA));
    }
}
