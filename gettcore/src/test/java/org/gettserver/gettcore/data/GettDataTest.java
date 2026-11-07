package org.gettserver.gettcore.data;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
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
    void emptyDirGetsDefaults(@TempDir Path dir) {
        GettData data = new GettData();
        GettData.LoadReport r = data.load(dir.resolve("gettcore"));
        assertEquals(1, r.shops());
        assertEquals(2, r.dialogs());
        assertTrue(r.errors().isEmpty());
        assertEquals("farmer", data.dialogForNpc(UUID.randomUUID(), "농부").orElseThrow());
        assertEquals("enchanter", data.dialogForNpc(UUID.randomUUID(), "마법부여사").orElseThrow());
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
}
