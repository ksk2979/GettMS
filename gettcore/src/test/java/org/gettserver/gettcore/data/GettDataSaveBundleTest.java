package org.gettserver.gettcore.data;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.editor.EditorBundle;
import org.gettserver.gettcore.pet.PetConfig;
import org.gettserver.gettcore.pet.boss.BossPoint;
import org.gettserver.gettcore.protect.ProtectConfig;
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

        GettData.SaveResult r = data.saveBundle(new EditorBundle(shops, raw.dialogs(), raw.npcs(), raw.quests(), raw.bossPoints(), raw.pets(), raw.protect()),
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
        assertTrue(data.saveBundle(new EditorBundle(shops, raw.dialogs(), raw.npcs(), raw.quests(), raw.bossPoints(), raw.pets(), raw.protect()),
                RegistryChecks.ALL_EXIST).saved());
        assertTrue(Files.isRegularFile(dir.resolve("shops/old.json")));

        shops.remove("old");
        shops.put("newshop", withId(shops.get("farmer"), "newshop"));
        GettData.SaveResult r = data.saveBundle(new EditorBundle(shops, raw.dialogs(), raw.npcs(), raw.quests(), raw.bossPoints(), raw.pets(), raw.protect()),
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
        GettData.SaveResult r = data.saveBundle(new EditorBundle(raw.shops(), dialogs, npcs, Map.of(), raw.bossPoints(), raw.pets(), raw.protect()),
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
        GettData.SaveResult r = data.saveBundle(new EditorBundle(shops, raw.dialogs(), raw.npcs(), raw.quests(), raw.bossPoints(), raw.pets(), raw.protect()),
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
                List.of(point), pets, raw.protect()), RegistryChecks.ALL_EXIST);

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
                raw.quests(), List.of(den, cave), raw.pets(), raw.protect()), RegistryChecks.ALL_EXIST);
        assertTrue(first.saved(), first.errors().toString());
        assertEquals(List.of(), first.removedBossPoints());

        GettData.SaveResult second = data.saveBundle(new EditorBundle(raw.shops(), raw.dialogs(), raw.npcs(),
                raw.quests(), List.of(cave), raw.pets(), raw.protect()), RegistryChecks.ALL_EXIST);
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
                raw.bossPoints(), raw.pets(), protect), RegistryChecks.ALL_EXIST);

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
}
