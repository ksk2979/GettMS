package org.gettserver.gettcore.data;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CodecTest {
    private static final String SHOP = """
            {"id":"s","name":"S","tabs":[{"id":"t","name":"T","icon":"minecraft:carrot"}],
             "entries":[{"item":"minecraft:carrot","tab":"t","buyPrice":10,"sellPrice":3,"canBuy":true,"canSell":true}]}""";

    @Test
    void shopRoundTrip() {
        ShopDefinition shop = ShopDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(SHOP)).getOrThrow();
        ShopEntry e = shop.entries().get(0);
        assertEquals(10, e.currentBuyPrice());
        assertEquals(3, e.currentSellPrice());
        JsonElement out = ShopDefinition.CODEC.encodeStart(JsonOps.INSTANCE, shop).getOrThrow();
        assertEquals(shop, ShopDefinition.CODEC.parse(JsonOps.INSTANCE, out).getOrThrow());
    }

    @Test
    void dialogParsesChoiceActions() throws IOException {
        String json;
        try (var in = CodecTest.class.getResourceAsStream("/gettcore_defaults/dialogs/farmer.json")) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        DialogDefinition d = DialogDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
        var c = d.nodes().get(d.start()).choices();
        assertEquals(3, c.size());
        assertEquals(ChoiceAction.Type.OPEN_SHOP_BUY, c.get(0).action().type());
        assertEquals(ChoiceAction.Type.OPEN_SHOP_SELL, c.get(1).action().type());
        assertEquals(ChoiceAction.Type.NEXT, c.get(2).action().type());
    }

    @Test
    void shopRejectsNegativePrice() {
        String bad = SHOP.replace("\"buyPrice\":10", "\"buyPrice\":-1");
        DataResult<ShopDefinition> r = ShopDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(bad));
        assertTrue(r.error().isPresent());
    }
}
