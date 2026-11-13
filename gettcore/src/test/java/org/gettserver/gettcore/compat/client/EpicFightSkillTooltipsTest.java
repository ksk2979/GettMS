package org.gettserver.gettcore.compat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EpicFightSkillTooltipsTest {
    @Test
    void tooltipKeyAppendsSuffix() {
        assertEquals("skill.epicfight.guard.tooltip", EpicFightSkillTooltips.tooltipKey("skill.epicfight.guard"));
    }
}
