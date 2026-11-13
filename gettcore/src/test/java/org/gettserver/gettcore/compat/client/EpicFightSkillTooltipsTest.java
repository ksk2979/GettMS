package org.gettserver.gettcore.compat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EpicFightSkillTooltipsTest {
    private static final Map<String, String> NAMES = Map.of("sword", "검", "spear", "창", "staff", "지팡이");

    private static String tr(String arg) {
        return EpicFightSkillTooltips.translateWeaponTypes(arg, NAMES::get);
    }

    @Test
    void tooltipKeyAppendsSuffix() {
        assertEquals("skill.epicfight.guard.tooltip", EpicFightSkillTooltips.tooltipKey("skill.epicfight.guard"));
    }

    @Test
    void weaponTypesAllKnownAreTranslated() {
        assertEquals("검, 창, 지팡이", tr("sword, spear, staff"));
    }

    @Test
    void weaponTypesUnknownTokenKept() {
        assertEquals("검, mystery", tr("sword, mystery"));
    }

    @Test
    void sentenceWithoutKnownTokensUnchanged() {
        assertEquals("Hits hard, then retreats.", tr("Hits hard, then retreats."));
    }

    @Test
    void singleKnownWordIsTranslated() {
        assertEquals("검", tr("sword"));
    }

    @Test
    void emptyStringUnchanged() {
        assertEquals("", tr(""));
    }

    @Test
    void costUsesOneDecimal() {
        assertEquals("4.0", EpicFightSkillTooltips.formatCost(4f));
        assertEquals("2.5", EpicFightSkillTooltips.formatCost(2.5f));
    }

    @Test
    void bonusFormatting() {
        assertEquals("+6", EpicFightSkillTooltips.formatBonus(6.0, true));
        assertEquals("-2.5", EpicFightSkillTooltips.formatBonus(-2.5, true));
        assertEquals("+15%", EpicFightSkillTooltips.formatBonus(0.15, false));
        assertEquals("+12.5%", EpicFightSkillTooltips.formatBonus(0.125, false));
        assertEquals("-10%", EpicFightSkillTooltips.formatBonus(-0.1, false));
    }
}
