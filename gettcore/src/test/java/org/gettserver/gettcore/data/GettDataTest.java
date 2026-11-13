package org.gettserver.gettcore.data;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.gettserver.gettcore.pet.PetConfig;
import org.gettserver.gettcore.pet.PetSpecies;
import org.gettserver.gettcore.pet.boss.BossPoint;
import org.gettserver.gettcore.reinforce.ReinforceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GettDataTest {
    private static final String SHOP = """
            {"id":"s","name":"S","tabs":[{"id":"t","name":"T","icon":"minecraft:carrot"}],
             "entries":[{"item":"minecraft:carrot","tab":"t","buyPrice":10,"sellPrice":3,"canBuy":true,"canSell":true}]}""";

    @Test
    void loadReportsBrokenFileAndSaveRoundTrips(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("shops"));
        Files.writeString(dir.resolve("shops/good.json"), SHOP);
        Files.writeString(dir.resolve("shops/broken.json"), "{ not json");
        GettData data = new GettData();
        GettData.LoadReport report = data.load(dir);
        assertEquals(1, report.shops()); // pre-marker dir: the old farmer sample is not resurrected
        assertFalse(Files.exists(dir.resolve("shops/farmer.json")));
        assertEquals(1, report.errors().size());
        assertTrue(report.errors().get(0).contains("broken.json"));

        ShopDefinition shop = data.shop("s").orElseThrow();
        var carrot = shop.entries().get(0).item();
        data.saveShop(shop.withPrices(carrot, 99, 7));
        GettData again = new GettData();
        again.load(dir);
        assertEquals(99, again.shop("s").orElseThrow().entries().get(0).buyPrice());
    }

    @Test
    void dirWithoutBossesJsonGetsDefaultBosses(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("shops"));
        Files.writeString(dir.resolve("shops/good.json"), SHOP);
        GettData data = new GettData();
        data.load(dir);
        var giraffe = net.minecraft.resources.ResourceLocation.parse("gettcore:boss_giraffe");
        assertEquals(2, data.bosses().boss(giraffe).skills().size());
    }

    @Test
    void emptyDirGetsDefaults(@TempDir Path dir) {
        GettData data = new GettData();
        GettData.LoadReport r = data.load(dir.resolve("gettcore"));
        assertEquals(1, r.shops());
        assertEquals(4, r.dialogs());
        assertEquals(1, r.quests());
        assertEquals(16, data.questLine("main").orElseThrow().quests().size());
        assertEquals("chief", data.dialogForNpc(UUID.randomUUID(), "이장").orElseThrow());
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertEquals("farmer", data.dialogForNpc(UUID.randomUUID(), "농부").orElseThrow());
        assertEquals("enchanter", data.dialogForNpc(UUID.randomUUID(), "마법부여사").orElseThrow());
        assertEquals("blacksmith", data.dialogForNpc(UUID.randomUUID(), "대장장이").orElseThrow());
        DialogDefinition smith = data.dialog("blacksmith").orElseThrow();
        assertEquals("대장장이", smith.name());
        var choices = smith.nodes().get(smith.start()).choices();
        assertEquals(3, choices.size());
        assertEquals("강화하기", choices.get(0).label());
        assertEquals(ChoiceAction.Type.OPEN_REINFORCE, choices.get(0).action().type());
        assertEquals("업그레이드", choices.get(1).label());
        assertEquals(ChoiceAction.Type.OPEN_UPGRADE, choices.get(1).action().type());
        assertEquals(ChoiceAction.Type.CLOSE, choices.get(2).action().type());
    }

    @Test
    void blacksmithMappingIsAddedOnceToEarlierInstall(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("gettcore");
        new GettData().load(root);
        // Simulate an install from before reinforce: no 대장장이 mapping and no upgrade line in the marker.
        Files.writeString(root.resolve("npcs.json"), "{\"byUuid\":{},\"byName\":{\"마법부여사\":\"enchanter\"}}");
        Path marker = root.resolve(GettData.MARKER);
        Files.write(marker, Files.readAllLines(marker).stream()
                .filter(l -> !l.equals(GettData.NPC_BLACKSMITH_UPGRADE)).toList());
        GettData data = new GettData();
        GettData.LoadReport r = data.load(root);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertEquals("blacksmith", data.npcMapping().byName().get("대장장이"));
        assertFalse(data.npcMapping().byName().containsKey("농부")); // deleted default not resurrected
        assertTrue(Files.readAllLines(marker).contains(GettData.NPC_BLACKSMITH_UPGRADE));

        // One-shot: removed afterwards stays removed.
        Files.writeString(root.resolve("npcs.json"), "{\"byUuid\":{},\"byName\":{\"마법부여사\":\"enchanter\"}}");
        GettData again = new GettData();
        again.load(root);
        assertFalse(again.npcMapping().byName().containsKey("대장장이"));
    }

    @Test
    void missingDefaultFilesAreAddedToExistingDir(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("shops"));
        Files.writeString(dir.resolve("shops/good.json"), SHOP);
        GettData data = new GettData();
        GettData.LoadReport r = data.load(dir);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertTrue(Files.isRegularFile(dir.resolve("dialogs/enchanter.json")));
        assertTrue(Files.isRegularFile(dir.resolve("enchant.json")));
        assertEquals(SHOP, Files.readString(dir.resolve("shops/good.json")));
        assertTrue(data.dialog("enchanter").isPresent());
        String marker = Files.readString(dir.resolve(GettData.MARKER));
        assertTrue(marker.contains("dialogs/enchanter.json") && marker.contains("shops/farmer.json"), marker);
    }

    @Test
    void deletedDefaultIsNotRestoredAfterInstall(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("gettcore");
        new GettData().load(root);
        Path farmer = root.resolve("shops/farmer.json");
        assertTrue(Files.isRegularFile(farmer));
        Files.delete(farmer);
        GettData.LoadReport r = new GettData().load(root);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertFalse(Files.exists(farmer));
        assertTrue(Files.readAllLines(root.resolve(GettData.MARKER)).contains("shops/farmer.json"));
    }

    @Test
    void npcMergeFailureDoesNotBlockOtherDefaults(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("npcs.json.tmp")); // writeJson cannot create its tmp file
        Files.writeString(dir.resolve("npcs.json"), "{\"byName\":{}}");
        GettData.LoadReport r = new GettData().load(dir);
        assertTrue(r.errors().stream().anyMatch(e -> e.startsWith("npcs.json")), r.errors().toString());
        assertTrue(Files.isRegularFile(dir.resolve("enchant.json")));
        assertTrue(Files.isRegularFile(dir.resolve("dialogs/enchanter.json")));
        assertFalse(Files.readAllLines(dir.resolve(GettData.MARKER)).contains("npcs.json"));
    }

    @Test
    void npcMergeKeepsOperatorValues(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("npcs.json"), "{\"byUuid\":{},\"byName\":{\"농부\":\"custom\"}}");
        GettData data = new GettData();
        data.load(dir);
        assertEquals("custom", data.npcMapping().byName().get("농부"));
        assertEquals("enchanter", data.npcMapping().byName().get("마법부여사"));
        GettData again = new GettData();
        again.load(dir);
        assertEquals("custom", again.npcMapping().byName().get("농부"));
        assertEquals("enchanter", again.npcMapping().byName().get("마법부여사"));
    }

    /** Simulates a 1.1 install: npcs.json already in the marker, no 이장 mapping, 농부 deliberately deleted. */
    private static Path oldInstall(Path dir) throws IOException {
        Path root = dir.resolve("gettcore");
        new GettData().load(root);
        Files.writeString(root.resolve("npcs.json"), "{\"byUuid\":{},\"byName\":{\"마법부여사\":\"enchanter\"}}");
        Path marker = root.resolve(GettData.MARKER);
        Files.write(marker, Files.readAllLines(marker).stream()
                .filter(l -> !l.equals(GettData.NPC_CHIEF_UPGRADE)).toList());
        assertTrue(Files.readAllLines(marker).contains("npcs.json"));
        return root;
    }

    @Test
    void chiefMappingIsAddedOnceToEarlierInstall(@TempDir Path dir) throws IOException {
        Path root = oldInstall(dir);
        GettData data = new GettData();
        GettData.LoadReport r = data.load(root);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertEquals("chief", data.npcMapping().byName().get("이장"));
        assertFalse(data.npcMapping().byName().containsKey("농부")); // deleted default not resurrected
        assertEquals("enchanter", data.npcMapping().byName().get("마법부여사"));
        assertTrue(Files.readAllLines(root.resolve(GettData.MARKER)).contains(GettData.NPC_CHIEF_UPGRADE));

        // One-shot: an operator who removes 이장 afterwards keeps it removed.
        Files.writeString(root.resolve("npcs.json"), "{\"byUuid\":{},\"byName\":{\"마법부여사\":\"enchanter\"}}");
        GettData again = new GettData();
        again.load(root);
        assertFalse(again.npcMapping().byName().containsKey("이장"));
    }

    @Test
    void chiefMappingKeepsOperatorValue(@TempDir Path dir) throws IOException {
        Path root = oldInstall(dir);
        Files.writeString(root.resolve("npcs.json"), "{\"byUuid\":{},\"byName\":{\"이장\":\"custom\"}}");
        GettData data = new GettData();
        data.load(root);
        assertEquals("custom", data.npcMapping().byName().get("이장"));
    }

    @Test
    void unmappedQuestNpcIsWarned(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("gettcore");
        GettData.LoadReport fresh = new GettData().load(root);
        assertTrue(fresh.warnings().stream().noneMatch(w -> w.contains("not mapped")), fresh.warnings().toString());
        Files.writeString(root.resolve("npcs.json"), "{\"byUuid\":{},\"byName\":{}}");
        GettData.LoadReport r = new GettData().load(root);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("'이장'") && w.contains("not mapped")),
                r.warnings().toString());
    }

    private static final String TWO_ITEMS = """
            {"id":"s","name":"S","tabs":[{"id":"t","name":"T","icon":"minecraft:carrot"}],
             "entries":[
              {"item":"minecraft:carrot","tab":"t","buyPrice":10,"sellPrice":3,"canBuy":true,"canSell":true,"currentBuyPrice":50},
              {"item":"othermod:thing","tab":"t","buyPrice":20,"sellPrice":5,"canBuy":true,"canSell":true,"currentBuyPrice":70}]}""";

    @Test
    void missingItemsArePrunedInMemoryButKeptInFile(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("shops"));
        Files.writeString(dir.resolve("shops/s.json"), TWO_ITEMS);
        GettData data = new GettData();
        data.setRegistryChecks(new RegistryChecks(rl -> rl.getNamespace().equals("minecraft"), rl -> true));
        GettData.LoadReport r = data.load(dir);
        assertEquals(1, data.shop("s").orElseThrow().entries().size());
        assertEquals(2, data.rawShop("s").orElseThrow().entries().size());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("othermod:thing")), r.warnings().toString());
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertTrue(Files.readString(dir.resolve("shops/s.json")).contains("othermod:thing"));
    }

    @Test
    void priceOnlyUpdateKeepsRawEntries(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("shops"));
        Files.writeString(dir.resolve("shops/s.json"), TWO_ITEMS);
        Files.writeString(dir.resolve("economy.json"), "{\"priceDynamics\":{\"enabled\":true}}");
        GettData data = new GettData();
        data.setRegistryChecks(new RegistryChecks(rl -> rl.getNamespace().equals("minecraft"), rl -> true));
        data.load(dir);
        var carrot = net.minecraft.resources.ResourceLocation.parse("minecraft:carrot");
        var updated = data.updateCurrentPrices("s",
                java.util.Map.of(carrot, new GettData.CurrentPrices(40, 2))).orElseThrow();
        assertEquals(1, updated.entries().size());
        assertEquals(40, data.shop("s").orElseThrow().entries().get(0).currentBuyPrice());
        assertEquals(2, data.rawShop("s").orElseThrow().entries().size());
        assertEquals(40, data.rawShop("s").orElseThrow().entries().get(0).currentBuyPrice());
        GettData again = new GettData();
        again.setRegistryChecks(RegistryChecks.ALL_EXIST);
        again.load(dir);
        var entries = again.shop("s").orElseThrow().entries();
        assertEquals(2, entries.size());
        assertEquals(40, entries.get(0).currentBuyPrice());
        assertEquals(2, entries.get(0).currentSellPrice());
        assertEquals(70, entries.get(1).currentBuyPrice());
    }

    @Test
    void normalizationDoesNotDropMissingItems(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("shops"));
        Files.writeString(dir.resolve("shops/s.json"), TWO_ITEMS);
        Files.writeString(dir.resolve("economy.json"), "{\"priceDynamics\":{\"enabled\":false}}");
        GettData data = new GettData();
        data.setRegistryChecks(new RegistryChecks(rl -> rl.getNamespace().equals("minecraft"), rl -> true));
        data.load(dir);
        GettData again = new GettData();
        again.setRegistryChecks(RegistryChecks.ALL_EXIST);
        again.load(dir);
        var entries = again.shop("s").orElseThrow().entries();
        assertEquals(2, entries.size());
        assertEquals(10, entries.get(0).currentBuyPrice());
        assertEquals(20, entries.get(1).currentBuyPrice());
    }

    @Test
    void disabledDynamicsNormalizesCurrentPrices(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("shops"));
        Files.writeString(dir.resolve("shops/s.json"), SHOP.replace("\"canSell\":true",
                "\"canSell\":true,\"currentSellPrice\":1,\"currentBuyPrice\":50"));
        Files.writeString(dir.resolve("economy.json"), "{\"priceDynamics\":{\"enabled\":false}}");
        GettData data = new GettData();
        data.load(dir);
        var e = data.shop("s").orElseThrow().entries().get(0);
        assertEquals(e.sellPrice(), e.currentSellPrice());
        assertEquals(e.buyPrice(), e.currentBuyPrice());
        GettData again = new GettData();
        again.load(dir);
        assertEquals(3, again.shop("s").orElseThrow().entries().get(0).currentSellPrice());
    }

    @Test
    void petsFileInvalidOnFirstLoadFallsBackToDefault(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pets.json"), "{\"species\":{\"a:w\":{\"kind\":\"bogus\",\"petEntity\":\"a:p\"}}}");
        GettData data = new GettData();
        GettData.LoadReport bad = data.load(dir);
        assertTrue(bad.errors().stream().anyMatch(e -> e.contains("pets.json")), bad.errors().toString());
        assertEquals(PetConfig.DEFAULT, data.pets());
    }

    @Test
    void petsFileInvalidKeepsThePreviousConfig(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pets.json"),
                "{\"species\":{\"a:w\":{\"kind\":\"mount\",\"petEntity\":\"a:p\"}}}");
        GettData data = new GettData();
        GettData.LoadReport ok = data.load(dir);
        assertTrue(ok.errors().stream().noneMatch(e -> e.contains("pets.json")), ok.errors().toString());
        assertEquals(PetSpecies.Kind.MOUNT, data.pets().species().get("a:w").kind());
        PetConfig before = data.pets();

        Files.writeString(dir.resolve("pets.json"), "{\"species\":{\"a:w\":{\"kind\":\"bogus\",\"petEntity\":\"a:p\"}}}");
        GettData.LoadReport bad = data.load(dir);
        assertTrue(bad.errors().stream().anyMatch(e -> e.contains("pets.json")), bad.errors().toString());
        assertEquals(before, data.pets());
    }

    @Test
    void bossPointsInstallEmptySaveAndReload(@TempDir Path dir) throws IOException {
        GettData data = new GettData();
        GettData.LoadReport r = data.load(dir);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertTrue(Files.isRegularFile(dir.resolve("boss_points.json")));
        assertTrue(data.bossPoints().isEmpty());

        BossPoint p = new BossPoint("hill", net.minecraft.world.level.Level.OVERWORLD,
                new net.minecraft.core.BlockPos(5, 70, -9), 20,
                net.minecraft.resources.ResourceLocation.parse("gettcore:boss_lion"), 2, 30);
        data.saveBossPoints(java.util.List.of(p));
        assertEquals(java.util.List.of(p), data.bossPoints());
        GettData again = new GettData();
        again.load(dir);
        assertEquals(java.util.List.of(p), again.bossPoints());
    }

    @Test
    void bossPointsDuplicateIdIsReportedAndBrokenFileKeepsLoaded(@TempDir Path dir) throws IOException {
        String one = "{\"id\":\"a\",\"dimension\":\"minecraft:overworld\",\"pos\":[0,64,0],"
                + "\"bossType\":\"gettcore:boss_bear\"}";
        Files.writeString(dir.resolve("boss_points.json"), "[" + one + "," + one + "]");
        GettData data = new GettData();
        GettData.LoadReport r = data.load(dir);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("duplicate boss point id a")), r.errors().toString());
        assertEquals(1, data.bossPoints().size());

        Files.writeString(dir.resolve("boss_points.json"), "[{\"id\":1}]");
        GettData.LoadReport bad = data.load(dir);
        assertTrue(bad.errors().stream().anyMatch(e -> e.contains("boss_points.json")), bad.errors().toString());
        assertEquals(1, data.bossPoints().size());
    }

    @Test
    void bossPointsOutOfRangeAreClampedWithWarnings(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("boss_points.json"), "[{\"id\":\"wild\",\"dimension\":\"minecraft:overworld\","
                + "\"pos\":[0,64,0],\"radius\":-1,\"bossType\":\"gettcore:boss_bear\",\"maxCount\":99,"
                + "\"respawnMinutes\":-5}]");
        GettData data = new GettData();
        GettData.LoadReport r = data.load(dir);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        BossPoint p = data.bossPoints().get(0);
        assertEquals(1, p.radius());
        assertEquals(10, p.maxCount());
        assertEquals(0, p.respawnMinutes());
        assertEquals(3, r.warnings().stream().filter(w -> w.contains("point wild")).count(), r.warnings().toString());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("wild radius -1")), r.warnings().toString());
    }

    @Test
    void protectInstallsDefaultSavesAndKeepsPreviousOnBrokenFile(@TempDir Path dir) throws IOException {
        GettData data = new GettData();
        GettData.LoadReport r = data.load(dir);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertTrue(Files.isRegularFile(dir.resolve("protect.json")));
        assertEquals(org.gettserver.gettcore.protect.ProtectConfig.DEFAULT, data.protect());

        var region = new org.gettserver.gettcore.protect.ProtectConfig.Region("town",
                net.minecraft.world.level.Level.OVERWORLD, new net.minecraft.core.BlockPos(0, 60, 0),
                new net.minecraft.core.BlockPos(20, 90, 20), org.gettserver.gettcore.protect.ProtectConfig.Flags.DEFAULT);
        var cfg = new org.gettserver.gettcore.protect.ProtectConfig(true, false, java.util.List.of(region));
        data.saveProtect(cfg);
        assertEquals(cfg, data.protect());
        GettData again = new GettData();
        again.load(dir);
        assertEquals(cfg, again.protect());

        Files.writeString(dir.resolve("protect.json"), "{\"regions\":[{\"id\":1}]}");
        GettData.LoadReport bad = again.load(dir);
        assertTrue(bad.errors().stream().anyMatch(e -> e.contains("protect.json")), bad.errors().toString());
        assertEquals(cfg, again.protect());
    }

    @Test
    void protectSanitizeWarningsReachLoadReport(@TempDir Path dir) throws IOException {
        String region = "{\"id\":\"a\",\"dimension\":\"minecraft:overworld\",\"min\":[5,5,5],\"max\":[0,0,0]}";
        Files.writeString(dir.resolve("protect.json"), "{\"regions\":[" + region + "," + region + "]}");
        GettData data = new GettData();
        GettData.LoadReport r = data.load(dir);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertEquals(1, data.protect().regions().size());
        assertEquals(new net.minecraft.core.BlockPos(0, 0, 0), data.protect().regions().get(0).min());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("protect.json")), r.warnings().toString());
    }

    @Test
    void reinforceInstallsDefaultSavesAndKeepsPreviousOnBrokenFile(@TempDir Path dir) throws IOException {
        GettData data = new GettData();
        GettData.LoadReport r = data.load(dir);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertTrue(r.warnings().isEmpty(), r.warnings().toString());
        assertTrue(Files.isRegularFile(dir.resolve("reinforce.json")));
        assertEquals(ReinforceConfig.DEFAULT, data.reinforce());

        var cfg = new ReinforceConfig(2, java.util.List.of(100.0, 50.0), java.util.List.of(1, 3), 5, 6, 7, 2, 0);
        Files.writeString(dir.resolve("reinforce.json"), ReinforceConfig.CODEC
                .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, cfg).getOrThrow().toString());
        GettData.LoadReport saved = data.load(dir);
        assertTrue(saved.errors().isEmpty(), saved.errors().toString());
        assertEquals(cfg, data.reinforce());
        GettData again = new GettData();
        again.load(dir);
        assertEquals(cfg, again.reinforce());

        Files.writeString(dir.resolve("reinforce.json"), "{\"maxLevel\":\"many\"}");
        GettData.LoadReport bad = again.load(dir);
        assertTrue(bad.errors().stream().anyMatch(e -> e.contains("reinforce.json")), bad.errors().toString());
        assertEquals(cfg, again.reinforce());

        Files.writeString(dir.resolve("reinforce.json"), "{\"maxLevel\":3}");
        GettData.LoadReport warn = again.load(dir);
        assertEquals(3, again.reinforce().successPercent().size());
        assertTrue(warn.warnings().stream().anyMatch(w -> w.contains("reinforce.json")), warn.warnings().toString());
    }

    @Test
    void huntingAndMobsInstallDefaultSaveAndKeepPreviousOnBrokenFile(@TempDir Path dir) throws IOException {
        GettData data = new GettData();
        GettData.LoadReport r = data.load(dir);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertTrue(Files.isRegularFile(dir.resolve("hunting.json")));
        assertTrue(Files.isRegularFile(dir.resolve("mobs.json")));
        assertEquals(org.gettserver.gettcore.hunting.HuntingConfig.DEFAULT, data.hunting());
        assertEquals(org.gettserver.gettcore.mobs.MobStatsConfig.DEFAULT, data.mobs());

        var zone = new org.gettserver.gettcore.hunting.HuntingConfig.Zone("숲", net.minecraft.world.level.Level.OVERWORLD,
                new net.minecraft.core.BlockPos(0, 60, 0), new net.minecraft.core.BlockPos(20, 90, 20), 5, 7,
                java.util.List.of(new org.gettserver.gettcore.hunting.HuntingConfig.Entry("큰 좀비",
                        net.minecraft.resources.ResourceLocation.parse("minecraft:zombie"), 80, 6, 0.3, 2, 1.4, 2, 30, 1.5)));
        var hunting = new org.gettserver.gettcore.hunting.HuntingConfig(java.util.List.of(zone));
        data.saveHunting(hunting);
        assertEquals(hunting, data.hunting());
        Files.writeString(dir.resolve("mobs.json"), "{\"overrides\":{\"minecraft:zombie\":{\"health\":40}}}");
        GettData again = new GettData();
        again.load(dir);
        assertEquals(hunting, again.hunting());
        assertEquals(java.util.Optional.of(40.0), again.mobs().overrides().get("minecraft:zombie").health());

        Files.writeString(dir.resolve("hunting.json"), "{\"zones\":[{\"id\":1}]}");
        Files.writeString(dir.resolve("mobs.json"), "{\"overrides\":3}");
        GettData.LoadReport bad = again.load(dir);
        assertTrue(bad.errors().stream().anyMatch(e -> e.contains("hunting.json")), bad.errors().toString());
        assertTrue(bad.errors().stream().anyMatch(e -> e.contains("mobs.json")), bad.errors().toString());
        assertEquals(hunting, again.hunting());
        assertEquals(java.util.Optional.of(40.0), again.mobs().overrides().get("minecraft:zombie").health());

        Files.writeString(dir.resolve("hunting.json"), "{\"zones\":[{\"id\":\"a\",\"dimension\":\"minecraft:overworld\","
                + "\"min\":[0,0,0],\"max\":[1,1,1],\"maxAlive\":-4}]}");
        GettData.LoadReport warn = again.load(dir);
        assertEquals(0, again.hunting().zones().get(0).maxAlive());
        assertTrue(warn.warnings().stream().anyMatch(w -> w.contains("hunting.json")), warn.warnings().toString());
    }

    @Test
    void equipmentInstallsDefaultLoadsAndKeepsPreviousOnBrokenFile(@TempDir Path dir) throws IOException {
        GettData data = new GettData();
        GettData.LoadReport r = data.load(dir);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertTrue(Files.isRegularFile(dir.resolve("equipment.json")));
        assertEquals(org.gettserver.gettcore.equipment.EquipmentConfig.DEFAULT, data.equipment());

        Files.writeString(dir.resolve("equipment.json"),
                "{\"items\":{\"minecraft:diamond_sword\":{\"attackDamage\":9,\"knockbackResistance\":3}}}");
        GettData.LoadReport warn = data.load(dir);
        var sword = data.equipment().items().get("minecraft:diamond_sword");
        assertEquals(java.util.Optional.of(9.0), sword.attackDamage());
        assertEquals(java.util.Optional.of(1.0), sword.knockbackResistance());
        assertTrue(warn.warnings().stream().anyMatch(w -> w.contains("equipment.json")), warn.warnings().toString());

        Files.writeString(dir.resolve("equipment.json"), "{\"items\":3}");
        GettData.LoadReport bad = data.load(dir);
        assertTrue(bad.errors().stream().anyMatch(e -> e.contains("equipment.json")), bad.errors().toString());
        assertEquals(java.util.Optional.of(9.0),
                data.equipment().items().get("minecraft:diamond_sword").attackDamage());
    }

    @Test
    void upgradeInstallsDefaultLoadsAndKeepsPreviousOnBrokenFile(@TempDir Path dir) throws IOException {
        GettData data = new GettData();
        GettData.LoadReport r = data.load(dir);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("upgrade.json")), r.warnings().toString());
        assertTrue(Files.isRegularFile(dir.resolve("upgrade.json")));
        assertEquals(136, data.upgrade().steps().size());

        Files.writeString(dir.resolve("upgrade.json"), "{\"steps\":[{\"from\":\"a:x\",\"to\":\"a:y\","
                + "\"material\":\"minecraft:diamond\",\"count\":0},{\"from\":\"a:x\",\"to\":\"a:z\",\"material\":\"m:m\"}]}");
        GettData.LoadReport warn = data.load(dir);
        assertEquals(1, data.upgrade().steps().size());
        assertEquals(1, data.upgrade().steps().get(0).count());
        assertEquals(2, warn.warnings().stream().filter(w -> w.contains("upgrade.json")).count(),
                warn.warnings().toString());

        Files.writeString(dir.resolve("upgrade.json"), "{\"steps\":3}");
        GettData.LoadReport bad = data.load(dir);
        assertTrue(bad.errors().stream().anyMatch(e -> e.contains("upgrade.json")), bad.errors().toString());
        assertEquals("a:y", data.upgrade().steps().get(0).to());
    }

    private static final String GIRAFFE_KEY = "gettcore:boss_giraffe";
    private static final String GIRAFFE_UPGRADE = "pets.json@boss_giraffe";
    private static final String PETS_WITHOUT_GIRAFFE = """
            {"species":{"gettcore:pet_lion":{"kind":"fighter","petEntity":"gettcore:pet_lion","attack":9,
            "speed":0.3,"attackSpeed":1.0,"flySpeed":0.6,"climbSpeed":0.3}}}""";

    private static Path petsInstall(Path dir, String petsJson, String... markerLines) throws IOException {
        Path root = dir.resolve("gettcore");
        Files.createDirectories(root);
        Files.writeString(root.resolve("pets.json"), petsJson);
        Files.write(root.resolve(GettData.MARKER), java.util.List.of(markerLines));
        return root;
    }

    @Test
    void existingPetsJsonGainsBossGiraffeOnce(@TempDir Path dir) throws IOException {
        Path root = petsInstall(dir, PETS_WITHOUT_GIRAFFE, "pets.json");
        GettData data = new GettData();
        GettData.LoadReport r = data.load(root);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        PetSpecies giraffe = data.pets().species().get(GIRAFFE_KEY);
        assertNotNull(giraffe);
        assertEquals(PetSpecies.Kind.MOUNT, giraffe.kind());
        assertEquals(1.6, giraffe.sizeScale());
        assertEquals(9, data.pets().species().get("gettcore:pet_lion").attack());
        assertEquals(2, data.pets().species().size());
        assertTrue(Files.readAllLines(root.resolve(GettData.MARKER)).contains(GIRAFFE_UPGRADE));
    }

    @Test
    void bossGiraffeUpgradeNeverOverwrites(@TempDir Path dir) throws IOException {
        Path root = petsInstall(dir, PETS_WITHOUT_GIRAFFE.replace("}}}", "},\"" + GIRAFFE_KEY + "\":"
                + "{\"kind\":\"mount\",\"petEntity\":\"gettcore:pet_giraffe\",\"attack\":6,\"speed\":0.3,"
                + "\"attackSpeed\":1.0,\"flySpeed\":0.6,\"climbSpeed\":0.3,\"boss\":true,\"sizeScale\":3}}}"),
                "pets.json");
        GettData data = new GettData();
        data.load(root);
        assertEquals(3.0, data.pets().species().get(GIRAFFE_KEY).sizeScale());
        assertTrue(Files.readAllLines(root.resolve(GettData.MARKER)).contains(GIRAFFE_UPGRADE));
    }

    @Test
    void bossGiraffeUpgradeRunsOnce(@TempDir Path dir) throws IOException {
        Path root = petsInstall(dir, PETS_WITHOUT_GIRAFFE, "pets.json", GIRAFFE_UPGRADE);
        GettData data = new GettData();
        data.load(root);
        assertFalse(data.pets().species().containsKey(GIRAFFE_KEY));
    }

    private static final String BEAR_UPGRADE = "bosses.json@bear_skills";

    private static Path bossesInstall(Path dir, String json, String... markerLines) throws IOException {
        Path root = dir.resolve("gettcore");
        Files.createDirectories(root);
        Files.writeString(root.resolve("bosses.json"), json);
        Files.write(root.resolve(GettData.MARKER), java.util.List.of(markerLines));
        return root;
    }

    private static com.google.gson.JsonObject bear(Path root) throws IOException {
        return com.google.gson.JsonParser.parseString(Files.readString(root.resolve("bosses.json")))
                .getAsJsonObject().getAsJsonObject("bosses").getAsJsonObject("gettcore:boss_bear");
    }

    @Test
    void bearSkillsUpgradeRunsOnceAndKeepsEdits(@TempDir Path dir) throws IOException {
        // (a) empty bear skills: filled, other fields and other bosses untouched, marker written
        Path a = bossesInstall(dir.resolve("a"), """
                {"bosses":{"gettcore:boss_bear":{"aggro":"after","searchRange":10.0,"skills":[]},
                "gettcore:boss_lion":{"aggro":"after","skills":[]}}}""", "bosses.json");
        GettData data = new GettData();
        GettData.LoadReport r = data.load(a);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        com.google.gson.JsonObject b = bear(a);
        assertEquals(2, b.getAsJsonArray("skills").size());
        assertEquals("after", b.get("aggro").getAsString());
        assertEquals(10.0, b.get("searchRange").getAsDouble());
        assertEquals(org.gettserver.gettcore.skill.BossConfig.BEAR_SPEED, b.get("speed").getAsDouble());
        assertEquals(0.243, b.get("speed").getAsDouble());
        for (var sk : b.getAsJsonArray("skills")) {
            assertEquals(2, sk.getAsJsonObject().getAsJsonObject("terrain").get("depth").getAsInt());
        }
        assertEquals(2, data.bosses().boss(net.minecraft.resources.ResourceLocation.parse("gettcore:boss_bear")).skills().size());
        assertEquals(0, com.google.gson.JsonParser.parseString(Files.readString(a.resolve("bosses.json")))
                .getAsJsonObject().getAsJsonObject("bosses").getAsJsonObject("gettcore:boss_lion")
                .getAsJsonArray("skills").size());
        assertTrue(Files.readAllLines(a.resolve(GettData.MARKER)).contains(BEAR_UPGRADE));

        // (b) a bear with a user skill: left exactly as is, marker written
        String custom = """
                {"bosses":{"gettcore:boss_bear":{"speed":0.2,"skills":[{"id":"mine","type":"stomp","shape":{"radius":4}}]}}}""";
        Path c = bossesInstall(dir.resolve("b"), custom, "bosses.json");
        new GettData().load(c);
        assertEquals(custom, Files.readString(c.resolve("bosses.json")));
        assertTrue(Files.readAllLines(c.resolve(GettData.MARKER)).contains(BEAR_UPGRADE));

        // (c) marker present: an emptied skill list is not refilled
        Path m = bossesInstall(dir.resolve("c"), "{\"bosses\":{\"gettcore:boss_bear\":{\"skills\":[]}}}",
                "bosses.json", BEAR_UPGRADE);
        new GettData().load(m);
        assertEquals(0, bear(m).getAsJsonArray("skills").size());
    }

    @Test
    void bearUpgradeAddsMissingEntryAndKeepsExistingSpeed(@TempDir Path dir) throws IOException {
        Path a = bossesInstall(dir.resolve("a"), "{\"bosses\":{}}", "bosses.json");
        new GettData().load(a);
        assertEquals(2, bear(a).getAsJsonArray("skills").size());

        Path b = bossesInstall(dir.resolve("b"), "{\"bosses\":{\"gettcore:boss_bear\":{\"speed\":0.2}}}", "bosses.json");
        new GettData().load(b);
        assertEquals(2, bear(b).getAsJsonArray("skills").size());
        assertEquals(0.2, bear(b).get("speed").getAsDouble());
    }

    @Test
    void freshInstallAlreadyHasBearSkillsAndMarker(@TempDir Path dir) throws IOException {
        GettData data = new GettData();
        data.load(dir);
        assertEquals(2, bear(dir).getAsJsonArray("skills").size());
        assertTrue(Files.readAllLines(dir.resolve(GettData.MARKER)).contains(BEAR_UPGRADE));
    }

    @Test
    void detectorInstallsDefaultLoadsAndKeepsPreviousOnBrokenFile(@TempDir Path dir) throws IOException {
        GettData data = new GettData();
        GettData.LoadReport r = data.load(dir);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("detector.json")), r.warnings().toString());
        assertTrue(Files.isRegularFile(dir.resolve("detector.json")));
        assertEquals(org.gettserver.gettcore.detector.DetectorConfig.DEFAULT, data.detector());

        Files.writeString(dir.resolve("detector.json"), "{\"range\":10,\"maxBlocks\":0}");
        GettData.LoadReport warn = data.load(dir);
        assertEquals(10, data.detector().range());
        assertEquals(4096, data.detector().maxBlocks());
        assertEquals(1, warn.warnings().stream().filter(w -> w.contains("detector.json")).count(),
                warn.warnings().toString());

        Files.writeString(dir.resolve("detector.json"), "{\"range\":\"far\"}");
        GettData.LoadReport bad = data.load(dir);
        assertTrue(bad.errors().stream().anyMatch(e -> e.contains("detector.json")), bad.errors().toString());
        assertEquals(10, data.detector().range());

        Files.delete(dir.resolve("detector.json"));
        data.load(dir);
        assertEquals(org.gettserver.gettcore.detector.DetectorConfig.DEFAULT, data.detector());
    }
}
