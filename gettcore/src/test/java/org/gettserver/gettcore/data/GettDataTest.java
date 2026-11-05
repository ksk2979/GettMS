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
        assertEquals(1, report.shops());
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
        assertEquals(1, r.dialogs());
        assertTrue(r.errors().isEmpty());
        assertEquals("farmer", data.dialogForNpc(UUID.randomUUID(), "농부").orElseThrow());
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
