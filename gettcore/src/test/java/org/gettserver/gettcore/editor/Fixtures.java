package org.gettserver.gettcore.editor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.data.*;
import org.gettserver.gettcore.quest.*;

final class Fixtures {
    private Fixtures() {}

    static ResourceLocation rl(String s) {
        return ResourceLocation.parse(s);
    }

    static ShopEntry entry(String item, long buy, long sell) {
        return new ShopEntry(rl(item), "food", buy, sell, true, true, buy, sell);
    }

    static ShopDefinition shop(String id, ShopEntry... entries) {
        return new ShopDefinition(id, "상점", List.of(new ShopTab("food", "음식", rl("minecraft:bread"))),
                List.of(entries));
    }

    static DialogDefinition dialog(String id, String start, Map<String, DialogNode> nodes) {
        return new DialogDefinition(id, "대화", Optional.empty(), start, nodes);
    }

    static DialogChoice choice(ChoiceAction.Type t, String target) {
        return new DialogChoice("선택", new ChoiceAction(t, Optional.ofNullable(target)));
    }

    static Quest quest(String id, QuestTrigger start, List<Objective> objs, Rewards rewards) {
        return new Quest(id, "", "제목", "", start, QuestTrigger.AUTO, objs, rewards, "");
    }

    static QuestTrigger npc(String name) {
        return new QuestTrigger(QuestTrigger.Type.NPC, Optional.ofNullable(name));
    }

    static EditorBundle bundle(Map<String, ShopDefinition> shops, Map<String, DialogDefinition> dialogs,
                               NpcMapping npcs, Map<String, QuestLine> quests) {
        return new EditorBundle(shops, dialogs, npcs, quests);
    }
}
