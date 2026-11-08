package org.gettserver.gettcore.editor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import org.gettserver.gettcore.data.*;
import org.gettserver.gettcore.pet.PetConfig;
import org.gettserver.gettcore.pet.PetSpecies;
import org.gettserver.gettcore.pet.boss.BossPoint;
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
        return new EditorBundle(shops, dialogs, npcs, quests, List.of(), PetConfig.DEFAULT);
    }

    static EditorBundle bundle(Map<String, ShopDefinition> shops, Map<String, DialogDefinition> dialogs,
                               NpcMapping npcs, Map<String, QuestLine> quests, List<BossPoint> bossPoints,
                               PetConfig pets) {
        return new EditorBundle(shops, dialogs, npcs, quests, bossPoints, pets);
    }

    static BossPoint bossPoint(String id, String bossType, int radius, int maxCount, int respawnMinutes) {
        return new BossPoint(id, ResourceKey.create(Registries.DIMENSION, rl("minecraft:overworld")),
                new BlockPos(10, 64, -20), radius, rl(bossType), maxCount, respawnMinutes);
    }

    static PetSpecies species(String key, boolean boss) {
        return new PetSpecies(key, PetSpecies.Kind.FIGHTER, rl("gettcore:pet_lion"), 6, 0.3, 1, 0.6, 0.3, boss,
                boss ? 1.6 : 1, boss ? 5 : 1, boss ? 2 : 1, 0, boss ? 20 : 0, 1);
    }

    /** Pets config with the given species (keyed by their id) and default health bar/capture settings. */
    static PetConfig pets(PetSpecies... species) {
        Map<String, PetSpecies> m = new java.util.HashMap<>();
        for (PetSpecies s : species) m.put(s.id(), s);
        return new PetConfig(PetConfig.DEFAULT.healthbar(), PetConfig.DEFAULT.capture(), Map.copyOf(m));
    }
}
