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
import org.gettserver.gettcore.editor.EditorBundle;
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

        GettData.SaveResult r = data.saveBundle(new EditorBundle(shops, raw.dialogs(), raw.npcs(), raw.quests()),
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
        assertTrue(data.saveBundle(new EditorBundle(shops, raw.dialogs(), raw.npcs(), raw.quests()),
                RegistryChecks.ALL_EXIST).saved());
        assertTrue(Files.isRegularFile(dir.resolve("shops/old.json")));

        shops.remove("old");
        shops.put("newshop", withId(shops.get("farmer"), "newshop"));
        GettData.SaveResult r = data.saveBundle(new EditorBundle(shops, raw.dialogs(), raw.npcs(), raw.quests()),
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
        GettData.SaveResult r = data.saveBundle(new EditorBundle(raw.shops(), dialogs, npcs, Map.of()),
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
        for (int n = 0; n < 21; n++) {
            GettData.SaveResult r = data.saveBundle(data.rawBundle(), RegistryChecks.ALL_EXIST);
            assertTrue(r.saved(), r.errors().toString());
        }
        try (Stream<Path> s = Files.list(dir.resolve(".backup"))) {
            assertEquals(20, s.count());
        }
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
}
