package org.gettserver.gettcore.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.data.NpcMapping;
import org.gettserver.gettcore.data.RegistryChecks;
import org.gettserver.gettcore.data.ShopDefinition;
import org.gettserver.gettcore.data.ShopEntry;
import org.gettserver.gettcore.quest.ItemReward;
import org.gettserver.gettcore.quest.Objective;
import org.gettserver.gettcore.quest.Quest;
import org.gettserver.gettcore.quest.QuestLine;
import org.gettserver.gettcore.quest.QuestTrigger;
import org.gettserver.gettcore.quest.Rewards;
import org.junit.jupiter.api.Test;

class MissingRefsTest {
    private static ResourceLocation rl(String s) {
        return ResourceLocation.parse(s);
    }

    @Test
    void collectsSortedUniqueMissingIdsSkippingTags() {
        ShopEntry e1 = new ShopEntry(rl("zmod:b"), "t", 1, 1, true, true, 1, 1);
        ShopEntry e2 = new ShopEntry(rl("minecraft:stone"), "t", 1, 1, true, true, 1, 1);
        ShopDefinition shop = new ShopDefinition("s", "S", List.of(), List.of(e1, e2));
        Quest q = new Quest("q", "", "Q", "", QuestTrigger.AUTO, QuestTrigger.AUTO, List.of(
                new Objective(Objective.Type.COLLECT, "zmod:b", 1, false, Optional.empty()),
                new Objective(Objective.Type.COLLECT, "#zmod:tag", 1, false, Optional.empty()),
                new Objective(Objective.Type.KILL, "amod:mob", 1, false, Optional.empty()),
                new Objective(Objective.Type.KILL, "minecraft:zombie", 1, false, Optional.empty())),
                new Rewards(0, List.of(new ItemReward(rl("amod:a"), 1)), 0), "");
        EditorBundle b = new EditorBundle(Map.of("s", shop), Map.of(), new NpcMapping(Map.of(), Map.of()),
                Map.of("l", new QuestLine("l", "L", "", List.of(q))), List.of(),
                org.gettserver.gettcore.pet.PetConfig.DEFAULT);
        RegistryChecks checks = new RegistryChecks(r -> r.getNamespace().equals("minecraft"),
                r -> r.getNamespace().equals("minecraft"));
        assertEquals(List.of("amod:a", "zmod:b"), MissingRefs.items(b, checks));
        assertEquals(List.of("amod:mob"), MissingRefs.entities(b, checks));
    }
}
