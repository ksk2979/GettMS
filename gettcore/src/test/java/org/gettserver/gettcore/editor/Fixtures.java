package org.gettserver.gettcore.editor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import org.gettserver.gettcore.data.*;
import org.gettserver.gettcore.pet.CaptureTier;
import org.gettserver.gettcore.pet.PetConfig;
import org.gettserver.gettcore.pet.PetSpecies;
import org.gettserver.gettcore.pet.boss.BossPoint;
import org.gettserver.gettcore.protect.ProtectConfig;
import org.gettserver.gettcore.quest.*;
import org.gettserver.gettcore.reinforce.ReinforceConfig;

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
        return new EditorBundle(shops, dialogs, npcs, quests, List.of(), PetConfig.DEFAULT, ProtectConfig.DEFAULT,
                ReinforceConfig.DEFAULT);
    }

    static EditorBundle bundle(Map<String, ShopDefinition> shops, Map<String, DialogDefinition> dialogs,
                               NpcMapping npcs, Map<String, QuestLine> quests, List<BossPoint> bossPoints,
                               PetConfig pets) {
        return new EditorBundle(shops, dialogs, npcs, quests, bossPoints, pets, ProtectConfig.DEFAULT, ReinforceConfig.DEFAULT);
    }

    static EditorBundle bundle(Map<String, ShopDefinition> shops, Map<String, DialogDefinition> dialogs,
                               NpcMapping npcs, Map<String, QuestLine> quests, List<BossPoint> bossPoints,
                               PetConfig pets, ProtectConfig protect) {
        return new EditorBundle(shops, dialogs, npcs, quests, bossPoints, pets, protect, ReinforceConfig.DEFAULT);
    }

    static EditorBundle bundle(Map<String, ShopDefinition> shops, Map<String, DialogDefinition> dialogs,
                               NpcMapping npcs, Map<String, QuestLine> quests, List<BossPoint> bossPoints,
                               PetConfig pets, ProtectConfig protect, ReinforceConfig reinforce) {
        return new EditorBundle(shops, dialogs, npcs, quests, bossPoints, pets, protect, reinforce);
    }

    /** A valid, non-default reinforce config (3 levels; every number differs from DEFAULT; one milestone). */
    static ReinforceConfig reinforce() {
        return new ReinforceConfig(3, List.of(95.0, 62.5, 30.0), List.of(1, 2, 4), 12.5, 8, 3, 2.5, 40,
                List.of(new ReinforceConfig.Milestone(org.gettserver.gettcore.reinforce.ReinforceRules.Target.SHIELD,
                        2, rl("minecraft:speed"), 1)));
    }

    /** An overworld region from min to max (as given, not normalized) with the given flags. */
    static ProtectConfig.Region region(String id, BlockPos min, BlockPos max, ProtectConfig.Flags flags) {
        return new ProtectConfig.Region(id, ResourceKey.create(Registries.DIMENSION, rl("minecraft:overworld")),
                min, max, flags);
    }

    /** Protect config with both global toggles off and the given regions. */
    static ProtectConfig protect(ProtectConfig.Region... regions) {
        return new ProtectConfig(false, false, List.of(regions));
    }

    static BossPoint bossPoint(String id, String bossType, int radius, int maxCount, int respawnMinutes) {
        return new BossPoint(id, ResourceKey.create(Registries.DIMENSION, rl("minecraft:overworld")),
                new BlockPos(10, 64, -20), radius, rl(bossType), maxCount, respawnMinutes);
    }

    static PetSpecies species(String key, boolean boss) {
        return new PetSpecies(key, PetSpecies.Kind.FIGHTER, rl("gettcore:pet_lion"), 6, 0.3, 1, 0.6, 0.3, boss,
                boss ? 1.6 : 1, boss ? 5 : 1, boss ? 2 : 1, 0, 1);
    }

    /** {@link #species} with partial per-tier overrides, as the editor's "확률" row builds them. */
    static PetSpecies speciesWithOverrides(String key, boolean boss) {
        PetSpecies s = species(key, boss);
        return new PetSpecies(s.id(), s.kind(), s.petEntity(), s.attack(), s.speed(), s.attackSpeed(), s.flySpeed(),
                s.climbSpeed(), s.boss(), s.sizeScale(), s.healthMult(), s.attackMult(), s.killGold(), s.jumpHeight(),
                Map.of(CaptureTier.GOLD, new PetConfig.ChanceRange(10, 40)), Map.of(CaptureTier.DIAMOND, 55.0));
    }

    /** Non-default global obtain tables (normal and boss). */
    static PetConfig.Capture captureWithObtainTables() {
        PetConfig.Capture d = PetConfig.DEFAULT.capture();
        return new PetConfig.Capture(d.minPercent(), d.maxPercent(), d.ballChance(), d.bossBallChance(),
                Map.of(CaptureTier.IRON, 11.0, CaptureTier.GOLD, 22.0, CaptureTier.DIAMOND, 33.0, CaptureTier.NETHERITE, 44.0),
                Map.of(CaptureTier.IRON, 5.0, CaptureTier.GOLD, 6.0, CaptureTier.DIAMOND, 7.0, CaptureTier.NETHERITE, 8.0),
                d.throwCooldownTicks());
    }

    /** Pets config with the given species (keyed by their id) and default health bar/capture settings. */
    static PetConfig pets(PetSpecies... species) {
        Map<String, PetSpecies> m = new java.util.HashMap<>();
        for (PetSpecies s : species) m.put(s.id(), s);
        return new PetConfig(PetConfig.DEFAULT.healthbar(), PetConfig.DEFAULT.capture(), Map.copyOf(m));
    }
}
