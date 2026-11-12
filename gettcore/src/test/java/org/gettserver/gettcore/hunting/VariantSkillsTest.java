package org.gettserver.gettcore.hunting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.skill.SkillSpec;
import org.junit.jupiter.api.Test;

class VariantSkillsTest {
    private static HuntingConfig.Entry entry(List<SkillSpec> skills) {
        return new HuntingConfig.Entry("a", ResourceLocation.parse("minecraft:zombie"), 20, 3, 0.23, 0, 1, 1, 0, 1, skills);
    }

    @Test
    void noRunnerForSkillLessVariant() {
        assertFalse(VariantSkills.wantsRunner(entry(List.of())));
    }

    @Test
    void runnerForVariantWithSkills() {
        SkillSpec s = new SkillSpec("s", SkillSpec.STOMP, true, false, "", 10, 1, 0, 20, 1, 10,
                new SkillSpec.Shape(0, 0, 6), new SkillSpec.Aftershock(0, 0, 0), new SkillSpec.Terrain(false, 0, 0));
        assertTrue(VariantSkills.wantsRunner(entry(List.of(s))));
    }

    @Test
    void runnerLifecycleDecision() {
        assertEquals(VariantSkills.Action.CREATE, VariantSkills.decide(false, true));
        assertEquals(VariantSkills.Action.NONE, VariantSkills.decide(true, true));
        assertEquals(VariantSkills.Action.DROP, VariantSkills.decide(true, false));
        assertEquals(VariantSkills.Action.NONE, VariantSkills.decide(false, false));
    }
}
