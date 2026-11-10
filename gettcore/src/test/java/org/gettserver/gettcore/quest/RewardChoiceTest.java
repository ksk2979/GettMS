package org.gettserver.gettcore.quest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** Server-side check of the reward index a report carries. */
class RewardChoiceTest {
    private static Quest quest(List<ItemReward> choices) {
        QuestTrigger npc = new QuestTrigger(QuestTrigger.Type.NPC, Optional.of("이장"));
        return new Quest("q", "", "t", "d", npc, npc, List.of(), Rewards.EMPTY, "", choices);
    }

    @Test
    void noChoicesAlwaysValid() {
        Quest q = quest(List.of());
        assertTrue(QuestDialogs.validChoice(q, -1));
        assertTrue(QuestDialogs.validChoice(q, 0));
        assertTrue(QuestDialogs.validChoice(q, 7));
    }

    @Test
    void choiceMustBeInRange() {
        Quest q = quest(List.of(new ItemReward(ResourceLocation.parse("minecraft:torch"), 1),
                new ItemReward(ResourceLocation.parse("minecraft:bread"), 2)));
        assertFalse(QuestDialogs.validChoice(q, -1));
        assertTrue(QuestDialogs.validChoice(q, 0));
        assertTrue(QuestDialogs.validChoice(q, 1));
        assertFalse(QuestDialogs.validChoice(q, 2));
    }
}
