package org.gettserver.gettcore.data;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.editor.EditorBundle;
import org.gettserver.gettcore.hunting.HuntingConfig;
import org.gettserver.gettcore.mobs.MobStatsConfig;
import org.gettserver.gettcore.pet.PetConfig;
import org.gettserver.gettcore.pet.boss.BossPoint;
import org.gettserver.gettcore.protect.ProtectConfig;
import org.gettserver.gettcore.reinforce.ReinforceConfig;
import org.gettserver.gettcore.skill.BossConfig;
import org.gettserver.gettcore.skill.SkillSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GettDataSaveBundleTest {
    private static GettData loaded(Path dir) {
        GettData data = new GettData();
        data.setRegistryChecks(RegistryChecks.ALL_EXIST);
        data.load(dir);
        return data;
    }

    private static Map<String, String> snapshot(Path dir) throws IOException {
        Map<String, String> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                m.put(dir.relativize(p).toString().replace(java.io.File.separatorChar, '/'), Files.readString(p));
            }
        }
        return m;
    }

    private static ShopDefinition withId(ShopDefinition s, String id) {
        return new ShopDefinition(id, s.name(), s.tabs(), s.entries());
    }

    @Test
    void invalidBundleWritesNothing(@TempDir Path dir) throws IOException {
        GettData data = loaded(dir);
        EditorBundle raw = data.rawBundle();
        ShopDefinition farmer = raw.shops().get("farmer");
        var bad = farmer.entries().get(0);
        Map<String, ShopDefinition> shops = new LinkedHashMap<>(raw.shops());
        shops.put("farmer", new ShopDefinition("farmer", farmer.name(), farmer.tabs(),
                List.of(new ShopEntry(bad.item(), bad.tab(), -5, 1, true, true, 1, 1))));
        Map<String, String> before = snapshot(dir);

        GettData.SaveResult r = data.saveBundle(new EditorBundle(shops, raw.dialogs(), raw.npcs(), raw.quests(), raw.bossPoints(), raw.pets(), raw.protect(), raw.reinforce()),
                RegistryChecks.ALL_EXIST);

        assertFalse(r.saved());
        assertFalse(r.errors().isEmpty());
        assertEquals(before, snapshot(dir));
        assertFalse(Files.exists(dir.resolve(".backup")));
    }

    @Test
    void saveWritesDeletesAndReloads(@TempDir Path dir) throws IOException {
        GettData data = loaded(dir);
        EditorBundle raw = data.rawBundle();
        Map<String, ShopDefinition> shops = new LinkedHashMap<>(raw.shops());
        shops.put("old", withId(shops.get("farmer"), "old"));
        assertTrue(data.saveBundle(new EditorBundle(shops, raw.dialogs(), raw.npcs(), raw.quests(), raw.bossPoints(), raw.pets(), raw.protect(), raw.reinforce()),
                RegistryChecks.ALL_EXIST).saved());
        assertTrue(Files.isRegularFile(dir.resolve("shops/old.json")));

        shops.remove("old");
        shops.put("newshop", withId(shops.get("farmer"), "newshop"));
        GettData.SaveResult r = data.saveBundle(new EditorBundle(shops, raw.dialogs(), raw.npcs(), raw.quests(), raw.bossPoints(), raw.pets(), raw.protect(), raw.reinforce()),
                RegistryChecks.ALL_EXIST);

        assertTrue(r.saved(), r.errors().toString());
        assertFalse(Files.exists(dir.resolve("shops/old.json")));
        assertTrue(Files.isRegularFile(dir.resolve("shops/newshop.json")));
        assertTrue(Files.isRegularFile(dir.resolve("shops/farmer.json")));
        assertTrue(data.shop("newshop").isPresent());
        assertTrue(data.shop("old").isEmpty());
        assertEquals(2, r.reload().shops());
        assertTrue(r.reload().errors().isEmpty(), r.reload().errors().toString());
        Path latest;
        try (Stream<Path> s = Files.list(dir.resolve(".backup"))) {
            latest = s.sorted().reduce((x, y) -> y).orElseThrow();
        }
        assertTrue(Files.isRegularFile(latest.resolve("shops/old.json")));
        assertTrue(Files.isRegularFile(latest.resolve("npcs.json")));
        assertTrue(snapshot(dir).keySet().stream().noneMatch(k -> k.endsWith(".tmp")));
    }

    @Test
    void removedDialogAndQuestFilesAreDeleted(@TempDir Path dir) throws IOException {
        GettData data = loaded(dir);
        EditorBundle raw = data.rawBundle();
        Map<String, DialogDefinition> dialogs = new LinkedHashMap<>(raw.dialogs());
        dialogs.remove("enchanter");
        Map<String, String> byName = new LinkedHashMap<>(raw.npcs().byName());
        byName.values().removeIf("enchanter"::equals);
        NpcMapping npcs = new NpcMapping(raw.npcs().byUuid(), byName);
        GettData.SaveResult r = data.saveBundle(new EditorBundle(raw.shops(), dialogs, npcs, Map.of(), raw.bossPoints(), raw.pets(), raw.protect(), raw.reinforce()),
                RegistryChecks.ALL_EXIST);
        assertTrue(r.saved(), r.errors().toString());
        assertFalse(Files.exists(dir.resolve("dialogs/enchanter.json")));
        assertTrue(Files.exists(dir.resolve("dialogs/chief.json")));
        assertFalse(Files.exists(dir.resolve("quests/main.json")));
        assertTrue(data.dialog("enchanter").isEmpty());
        assertTrue(data.allQuestLines().isEmpty());
    }

    @Test
    void backupKeepsLast20(@TempDir Path dir) throws IOException {
        GettData data = loaded(dir);
        assertTrue(data.saveBundle(data.rawBundle(), RegistryChecks.ALL_EXIST).saved());
        Path first;
        try (Stream<Path> s = Files.list(dir.resolve(".backup"))) {
            first = s.findFirst().orElseThrow();
        }

        for (int n = 0; n < 21; n++) {
            GettData.SaveResult r = data.saveBundle(data.rawBundle(), RegistryChecks.ALL_EXIST);
            assertTrue(r.saved(), r.errors().toString());
        }
        List<Path> kept;
        try (Stream<Path> s = Files.list(dir.resolve(".backup"))) {
            kept = s.sorted().toList();
        }
        assertEquals(20, kept.size());
        assertFalse(Files.exists(first), "oldest backup must be dropped");
        assertTrue(kept.get(19).getFileName().toString().compareTo(kept.get(0).getFileName().toString()) > 0);
    }

    @Test
    void newIdNeverOverwritesFileTrackedForAnotherId(@TempDir Path dir) throws IOException {
        GettData data = loaded(dir);
        EditorBundle raw = data.rawBundle();
        // farmer.json holds id "farmer"; rename the file's content so tracked name differs from id
        Files.move(dir.resolve("shops/farmer.json"), dir.resolve("shops/Foo.json"));
        data.load(dir);
        Map<String, ShopDefinition> shops = new LinkedHashMap<>(data.rawBundle().shops());
        shops.put("foo", withId(shops.get("farmer"), "foo"));
        GettData.SaveResult r = data.saveBundle(new EditorBundle(shops, raw.dialogs(), raw.npcs(), raw.quests(), raw.bossPoints(), raw.pets(), raw.protect(), raw.reinforce()),
                RegistryChecks.ALL_EXIST);
        assertTrue(r.saved(), r.errors().toString());
        assertEquals(2, r.reload().shops(), r.reload().errors().toString());
        assertTrue(r.reload().errors().isEmpty(), r.reload().errors().toString());
        assertTrue(data.shop("farmer").isPresent() && data.shop("foo").isPresent());
    }

    @Test
    void rawBundleKeepsMissingItems(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("shops"));
        Files.writeString(dir.resolve("shops/s.json"), """
                {"id":"s","name":"S","tabs":[{"id":"t","name":"T","icon":"minecraft:carrot"}],
                 "entries":[
                  {"item":"minecraft:carrot","tab":"t","buyPrice":10,"sellPrice":3,"canBuy":true,"canSell":true},
                  {"item":"othermod:thing","tab":"t","buyPrice":20,"sellPrice":5,"canBuy":true,"canSell":true}]}""");
        GettData data = new GettData();
        data.setRegistryChecks(new RegistryChecks(rl -> rl.getNamespace().equals("minecraft"), rl -> true));
        data.load(dir);
        assertEquals(1, data.shop("s").orElseThrow().entries().size());
        assertEquals(2, data.rawBundle().shops().get("s").entries().size());
    }

    @Test
    void savesBossPointsAndPets(@TempDir Path dir) throws IOException {
        GettData data = loaded(dir);
        EditorBundle raw = data.rawBundle();
        assertTrue(raw.pets().species().containsKey("gettcore:boss_lion"), "bundled pets.json has boss species");
        BossPoint point = new BossPoint("den", ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("minecraft:overworld")), new BlockPos(1, 70, 2), 24,
                ResourceLocation.parse("gettcore:boss_lion"), 3, 60);
        PetConfig p = raw.pets();
        PetConfig pets = new PetConfig(new PetConfig.HealthBar(48, 100, 10, 0xFF112233), p.capture(), p.species());

        GettData.SaveResult r = data.saveBundle(new EditorBundle(raw.shops(), raw.dialogs(), raw.npcs(), raw.quests(),
                List.of(point), pets, raw.protect(), raw.reinforce()), RegistryChecks.ALL_EXIST);

        assertTrue(r.saved(), r.errors().toString());
        assertTrue(r.reload().errors().isEmpty(), r.reload().errors().toString());
        assertEquals(List.of(point), data.bossPoints());
        assertEquals(pets, data.pets());
        assertTrue(Files.readString(dir.resolve("pets.json")).contains("\"captureColor\": \"#112233\""));
        assertTrue(Files.readString(dir.resolve("boss_points.json")).contains("\"den\""));
        Path latest;
        try (Stream<Path> s = Files.list(dir.resolve(".backup"))) {
            latest = s.sorted().reduce((x, y) -> y).orElseThrow();
        }
        assertTrue(Files.isRegularFile(latest.resolve("pets.json")));
        assertTrue(Files.readString(latest.resolve("pets.json")).contains("#55FF55"), "backup holds the old file");
        assertTrue(Files.isRegularFile(latest.resolve("boss_points.json")));
        assertEquals(List.of(point), data.rawBundle().bossPoints());
    }

    @Test
    void reportsRemovedBossPoints(@TempDir Path dir) throws IOException {
        GettData data = loaded(dir);
        EditorBundle raw = data.rawBundle();
        ResourceKey<net.minecraft.world.level.Level> overworld = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("minecraft:overworld"));
        BossPoint den = new BossPoint("den", overworld, new BlockPos(1, 70, 2), 24,
                ResourceLocation.parse("gettcore:boss_lion"), 1, 60);
        BossPoint cave = new BossPoint("cave", overworld, new BlockPos(9, 40, 9), 24,
                ResourceLocation.parse("gettcore:boss_lion"), 1, 60);
        GettData.SaveResult first = data.saveBundle(new EditorBundle(raw.shops(), raw.dialogs(), raw.npcs(),
                raw.quests(), List.of(den, cave), raw.pets(), raw.protect(), raw.reinforce()), RegistryChecks.ALL_EXIST);
        assertTrue(first.saved(), first.errors().toString());
        assertEquals(List.of(), first.removedBossPoints());

        GettData.SaveResult second = data.saveBundle(new EditorBundle(raw.shops(), raw.dialogs(), raw.npcs(),
                raw.quests(), List.of(cave), raw.pets(), raw.protect(), raw.reinforce()), RegistryChecks.ALL_EXIST);
        assertTrue(second.saved(), second.errors().toString());
        assertEquals(List.of("den"), second.removedBossPoints());
        assertEquals(List.of(cave), data.bossPoints());
    }

    @Test
    void savesProtect(@TempDir Path dir) throws IOException {
        GettData data = loaded(dir);
        Files.writeString(dir.resolve("protect.json"), "{\"explosionsBreakBlocks\": false, \"regions\": []}");
        data.load(dir);
        EditorBundle raw = data.rawBundle();
        ProtectConfig.Region town = new ProtectConfig.Region("town", ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("minecraft:overworld")), new BlockPos(-5, 0, -5), new BlockPos(5, 100, 5),
                new ProtectConfig.Flags(true, false, false));
        ProtectConfig protect = new ProtectConfig(true, false, List.of(town));

        GettData.SaveResult r = data.saveBundle(new EditorBundle(raw.shops(), raw.dialogs(), raw.npcs(), raw.quests(),
                raw.bossPoints(), raw.pets(), protect, raw.reinforce()), RegistryChecks.ALL_EXIST);

        assertTrue(r.saved(), r.errors().toString());
        assertTrue(r.reload().errors().isEmpty(), r.reload().errors().toString());
        assertEquals(protect, data.protect());
        assertEquals(protect, data.rawBundle().protect());
        assertTrue(Files.readString(dir.resolve("protect.json")).contains("\"town\""));
        Path latest;
        try (Stream<Path> s = Files.list(dir.resolve(".backup"))) {
            latest = s.sorted().reduce((x, y) -> y).orElseThrow();
        }
        assertTrue(Files.isRegularFile(latest.resolve("protect.json")));
        assertFalse(Files.readString(latest.resolve("protect.json")).contains("town"), "backup holds the old file");
    }

    @Test
    void savesReinforce(@TempDir Path dir) throws IOException {
        GettData data = loaded(dir);
        EditorBundle raw = data.rawBundle();
        assertEquals(ReinforceConfig.DEFAULT, raw.reinforce());
        // One value out of range on purpose: the save path sanitizes it (warning) before writing.
        ReinforceConfig cfg = new ReinforceConfig(3, List.of(90.0, 60.0, 130.0), List.of(1, 2, 3), 12, 8, 3, 2.5, 0);

        GettData.SaveResult r = data.saveBundle(new EditorBundle(raw.shops(), raw.dialogs(), raw.npcs(), raw.quests(),
                raw.bossPoints(), raw.pets(), raw.protect(), cfg), RegistryChecks.ALL_EXIST);

        assertTrue(r.saved(), r.errors().toString());
        assertTrue(r.warnings().contains("reinforce: successPercent values must be within [0, 100], clamped"),
                r.warnings().toString());
        assertTrue(r.reload().errors().isEmpty(), r.reload().errors().toString());
        ReinforceConfig expected = new ReinforceConfig(3, List.of(90.0, 60.0, 100.0), List.of(1, 2, 3), 12, 8, 3, 2.5, 0);
        assertEquals(expected, data.reinforce());
        assertEquals(expected, data.rawBundle().reinforce());
        assertTrue(Files.readString(dir.resolve("reinforce.json")).contains("\"maxLevel\": 3"));
        Path latest;
        try (Stream<Path> s = Files.list(dir.resolve(".backup"))) {
            latest = s.sorted().reduce((x, y) -> y).orElseThrow();
        }
        assertTrue(Files.isRegularFile(latest.resolve("reinforce.json")));
        assertTrue(Files.readString(latest.resolve("reinforce.json")).contains("\"maxLevel\": 30"),
                "backup holds the old file");
    }

    @Test
    void savesHuntingAndMobs(@TempDir Path dir) throws IOException {
        GettData data = loaded(dir);
        EditorBundle raw = data.rawBundle();
        assertEquals(HuntingConfig.DEFAULT, raw.hunting());
        assertEquals(MobStatsConfig.DEFAULT, raw.mobs());
        // maxAlive out of range on purpose: the save path sanitizes it (warning) before writing.
        HuntingConfig hunting = new HuntingConfig(List.of(new HuntingConfig.Zone("숲",
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld")),
                new BlockPos(0, 60, 0), new BlockPos(30, 80, 30), 500, 10, List.of(new HuntingConfig.Entry("큰 좀비",
                ResourceLocation.parse("minecraft:zombie"), 80, 6, 0.3, 2, 1.4, 2, 30, 1.5)))));
        MobStatsConfig mobs = new MobStatsConfig(Map.of("minecraft:zombie", new MobStatsConfig.Stats(
                Optional.of(40.0), Optional.empty(), Optional.empty(), Optional.empty())));

        GettData.SaveResult r = data.saveBundle(new EditorBundle(raw.shops(), raw.dialogs(), raw.npcs(), raw.quests(),
                raw.bossPoints(), raw.pets(), raw.protect(), raw.reinforce(), hunting, mobs), RegistryChecks.ALL_EXIST);

        assertTrue(r.saved(), r.errors().toString());
        assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("hunting: ") && w.contains("maxAlive")),
                r.warnings().toString());
        assertTrue(r.reload().errors().isEmpty(), r.reload().errors().toString());
        assertEquals(HuntingConfig.MAX_ALIVE_LIMIT, data.hunting().zones().get(0).maxAlive());
        assertEquals(mobs, data.mobs());
        assertEquals(mobs, data.rawBundle().mobs());
        assertTrue(Files.readString(dir.resolve("hunting.json"), java.nio.charset.StandardCharsets.UTF_8).contains("큰 좀비"));
        Path latest;
        try (Stream<Path> s = Files.list(dir.resolve(".backup"))) {
            latest = s.sorted().reduce((x, y) -> y).orElseThrow();
        }
        assertTrue(Files.isRegularFile(latest.resolve("hunting.json")));
        assertTrue(Files.isRegularFile(latest.resolve("mobs.json")));
        assertFalse(Files.readString(latest.resolve("mobs.json")).contains("zombie"), "backup holds the old file");
    }

    @Test
    void savesEquipmentWithBackup(@TempDir Path dir) throws IOException {
        GettData data = loaded(dir);
        EditorBundle raw = data.rawBundle();
        assertEquals(org.gettserver.gettcore.equipment.EquipmentConfig.DEFAULT, raw.equipment());
        var equipment = new org.gettserver.gettcore.equipment.EquipmentConfig(Map.of("minecraft:diamond_sword",
                new org.gettserver.gettcore.equipment.EquipmentConfig.Values(Optional.of(9.0), Optional.of(1.2),
                        Optional.empty(), Optional.empty(), Optional.empty())));
        GettData.SaveResult r = data.saveBundle(new EditorBundle(raw.shops(), raw.dialogs(), raw.npcs(), raw.quests(),
                raw.bossPoints(), raw.pets(), raw.protect(), raw.reinforce(), raw.hunting(), raw.mobs(), equipment),
                RegistryChecks.ALL_EXIST);
        assertTrue(r.saved(), r.errors().toString());
        assertEquals(equipment, data.equipment());
        assertEquals(equipment, data.rawBundle().equipment());
        assertTrue(Files.readString(dir.resolve("equipment.json")).contains("diamond_sword"));
        Path latest;
        try (Stream<Path> s = Files.list(dir.resolve(".backup"))) {
            latest = s.sorted().reduce((x, y) -> y).orElseThrow();
        }
        assertTrue(Files.isRegularFile(latest.resolve("equipment.json")));
        assertFalse(Files.readString(latest.resolve("equipment.json")).contains("diamond_sword"), "backup holds the old file");
    }

    @Test
    void savesUpgradeWithBackup(@TempDir Path dir) throws IOException {
        GettData data = loaded(dir);
        EditorBundle raw = data.rawBundle();
        assertEquals(136, raw.upgrade().steps().size(), "bundled default is installed");
        var upgrade = new org.gettserver.gettcore.upgrade.UpgradeConfig(List.of(
                new org.gettserver.gettcore.upgrade.UpgradeConfig.Step("minecraft:iron_sword",
                        "minecraft:diamond_sword", "minecraft:diamond", 2, 50)));
        GettData.SaveResult r = data.saveBundle(new EditorBundle(raw.shops(), raw.dialogs(), raw.npcs(), raw.quests(),
                raw.bossPoints(), raw.pets(), raw.protect(), raw.reinforce(), raw.hunting(), raw.mobs(),
                raw.equipment(), upgrade), RegistryChecks.ALL_EXIST);
        assertTrue(r.saved(), r.errors().toString());
        assertEquals(upgrade, data.upgrade());
        assertEquals(upgrade, data.rawBundle().upgrade());
        assertTrue(Files.readString(dir.resolve("upgrade.json")).contains("iron_sword"));
        Path latest;
        try (Stream<Path> s = Files.list(dir.resolve(".backup"))) {
            latest = s.sorted().reduce((x, y) -> y).orElseThrow();
        }
        assertTrue(Files.readString(latest.resolve("upgrade.json")).contains("magistuarmory"), "backup holds the old file");

        var cyclic = new org.gettserver.gettcore.upgrade.UpgradeConfig(List.of(
                new org.gettserver.gettcore.upgrade.UpgradeConfig.Step("a:x", "a:y", "minecraft:diamond", 1, 0),
                new org.gettserver.gettcore.upgrade.UpgradeConfig.Step("a:y", "a:x", "minecraft:diamond", 1, 0)));
        GettData.SaveResult bad = data.saveBundle(new EditorBundle(raw.shops(), raw.dialogs(), raw.npcs(),
                raw.quests(), raw.bossPoints(), raw.pets(), raw.protect(), raw.reinforce(), raw.hunting(), raw.mobs(),
                raw.equipment(), cyclic), RegistryChecks.ALL_EXIST);
        assertFalse(bad.saved());
        assertTrue(bad.errors().stream().anyMatch(e -> e.startsWith("upgrade.steps[1]") && e.contains("cycle")),
                bad.errors().toString());
        assertEquals(upgrade, data.upgrade());
    }

    @Test
    void saveBundleWritesBossesJsonAndReloads(@TempDir Path dir) throws IOException {
        GettData data = loaded(dir);
        EditorBundle raw = data.rawBundle();
        String giraffe = "gettcore:boss_giraffe";
        BossConfig.Boss g = raw.bosses().bosses().get(giraffe);
        SkillSpec ramgak = g.skills().get(0);
        assertEquals("ramgak", ramgak.id(), "bundled default is installed");
        SkillSpec harder = new SkillSpec(ramgak.id(), ramgak.type(), ramgak.enabled(), ramgak.shout(),
                ramgak.shoutText(), 20, ramgak.knockback(), ramgak.minRange(), ramgak.maxRange(),
                ramgak.chargeSeconds(), ramgak.cooldownSeconds(), ramgak.shape(), ramgak.aftershock(), ramgak.terrain());
        List<SkillSpec> skills = new java.util.ArrayList<>(g.skills());
        skills.set(0, harder);
        Map<String, BossConfig.Boss> bosses = new TreeMap<>(raw.bosses().bosses());
        bosses.put(giraffe, new BossConfig.Boss(g.aggro(), g.searchRange(), g.releaseRange(), g.reaggroRange(),
                g.calmSeconds(), g.speed(), g.armor(), g.preferredDistance(), g.shoutRange(), g.enrageBelow(),
                g.enrageCooldownMult(), skills));

        GettData.SaveResult r = data.saveBundle(new EditorBundle(raw.shops(), raw.dialogs(), raw.npcs(), raw.quests(),
                raw.bossPoints(), raw.pets(), raw.protect(), raw.reinforce(), raw.hunting(), raw.mobs(),
                raw.equipment(), raw.upgrade(), new BossConfig(bosses)), RegistryChecks.ALL_EXIST);
        assertTrue(r.saved(), r.errors().toString());
        assertEquals(20.0, ramgakDamage(Files.readString(dir.resolve("bosses.json"))));
        assertEquals(20.0, data.bosses().boss(ResourceLocation.parse(giraffe)).skills().get(0).damage());
        Path latest;
        try (Stream<Path> s = Files.list(dir.resolve(".backup"))) {
            latest = s.sorted().reduce((x, y) -> y).orElseThrow();
        }
        assertEquals(14.0, ramgakDamage(Files.readString(latest.resolve("bosses.json"))), "backup holds the old file");
    }

    @Test
    void savingAPayloadWithoutBossesKeepsBossesJson(@TempDir Path dir) throws IOException {
        GettData data = loaded(dir);
        var bossesJson = com.google.gson.JsonParser.parseString(Files.readString(dir.resolve("bosses.json")))
                .getAsJsonObject();
        bossesJson.getAsJsonObject("bosses").getAsJsonObject("gettcore:boss_giraffe").getAsJsonArray("skills").get(0)
                .getAsJsonObject().addProperty("damage", 20.0);
        Files.writeString(dir.resolve("bosses.json"), bossesJson.toString());
        data.load(dir);
        BossConfig customised = data.bosses();
        assertEquals(20.0, customised.boss(ResourceLocation.parse("gettcore:boss_giraffe")).skills().get(0).damage());

        // A stale editor page: its payload has no "bosses" section.
        var payload = com.google.gson.JsonParser.parseString(
                org.gettserver.gettcore.editor.EditorJson.encode(data.rawBundle(), null)).getAsJsonObject();
        payload.remove("bosses");
        EditorBundle stale = org.gettserver.gettcore.editor.EditorJson.decode(payload.toString(), data.bosses());
        GettData.SaveResult r = data.saveBundle(stale, RegistryChecks.ALL_EXIST);

        assertTrue(r.saved(), r.errors().toString());
        assertEquals(20.0, ramgakDamage(Files.readString(dir.resolve("bosses.json"))));
        assertEquals(customised, data.bosses());
    }

    private static double ramgakDamage(String bossesJson) {
        return com.google.gson.JsonParser.parseString(bossesJson).getAsJsonObject().getAsJsonObject("bosses")
                .getAsJsonObject("gettcore:boss_giraffe").getAsJsonArray("skills").get(0).getAsJsonObject()
                .get("damage").getAsDouble();
    }

    @Test
    void savesDetectorWithBackup(@TempDir Path dir) throws IOException {
        GettData data = loaded(dir);
        EditorBundle raw = data.rawBundle();
        assertEquals(org.gettserver.gettcore.detector.DetectorConfig.DEFAULT, raw.detector());
        var d = org.gettserver.gettcore.detector.DetectorConfig.DEFAULT;
        var changed = new org.gettserver.gettcore.detector.DetectorConfig(12, d.durationSeconds(), d.cooldownSeconds(),
                d.maxBlocks(), d.selfBlockTags(), d.targets());
        GettData.SaveResult r = data.saveBundle(new EditorBundle(raw.shops(), raw.dialogs(), raw.npcs(), raw.quests(),
                raw.bossPoints(), raw.pets(), raw.protect(), raw.reinforce(), raw.hunting(), raw.mobs(),
                raw.equipment(), raw.upgrade(), raw.bosses(), changed), RegistryChecks.ALL_EXIST);
        assertTrue(r.saved(), r.errors().toString());
        assertEquals(changed, data.detector());
        assertTrue(Files.readString(dir.resolve("detector.json")).contains("\"range\": 12"));
        Path latest;
        try (Stream<Path> s = Files.list(dir.resolve(".backup"))) {
            latest = s.sorted().reduce((x, y) -> y).orElseThrow();
        }
        assertTrue(Files.readString(latest.resolve("detector.json")).contains("\"range\": 24"),
                "backup holds the old file");
    }
}
