package org.gettserver.gettcore.balance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** 편집기 번들의 datapack 구역: 없으면 데이터팩을 건드리지 않음. */
class BalanceServiceTest {
    @Test
    void desiredIsTheDatapackSectionOrNull() {
        assertNull(BalanceService.desiredOf("{\"shops\":{}}"), "older editor / snapshot without the section");
        assertNull(BalanceService.desiredOf("{\"datapack\":null}"));
        assertNull(BalanceService.desiredOf("not json"));
        JsonObject d = BalanceService.desiredOf("{\"datapack\":{\"guns\":{}}}");
        assertNotNull(d);
        assertTrue(d.getAsJsonObject("guns").isEmpty());
    }

    @Test
    void validateChecksEachSection() {
        assertEquals(0, BalanceService.validate(JsonParser.parseString("{}").getAsJsonObject()).size());
        assertEquals(1, BalanceService.validate(JsonParser.parseString("{\"guns\":3}").getAsJsonObject()).size());
        assertEquals(1, BalanceService.validate(JsonParser.parseString(
                "{\"guns\":{\"a:weapons/x\":{\"rate\":0}}}").getAsJsonObject()).size());
        assertEquals(3, BalanceService.validate(JsonParser.parseString(
                "{\"spawnRules\":{\"a:b\":{\"disabled\":1}},\"spawnMobs\":{\"a:b\":-1},\"guns\":[]}").getAsJsonObject()).size());
        assertEquals(2, BalanceService.validate(JsonParser.parseString(
                "{\"epicfight\":{\"a:b\":{\"common\":{\"armor_negation\":-1,\"max_strikes\":0.5}}}}").getAsJsonObject()).size());
        assertEquals(1, BalanceService.validate(JsonParser.parseString("{\"epicfight\":[]}").getAsJsonObject()).size());
        assertEquals(0, BalanceService.validate(JsonParser.parseString(
                "{\"recipes\":{\"magistuarmory:armet\":true}}").getAsJsonObject()).size());
        assertEquals(1, BalanceService.validate(JsonParser.parseString(
                "{\"recipes\":{\"magistuarmory:armet\":\"yes\"}}").getAsJsonObject()).size());
        assertEquals(1, BalanceService.validate(JsonParser.parseString("{\"recipes\":[]}").getAsJsonObject()).size());
        assertEquals(1, BalanceService.validate(JsonParser.parseString(
                "{\"removeOrphans\":[{\"kind\":\"nope\",\"id\":\"a:b\"}]}").getAsJsonObject()).size());
    }

    @Test
    void sectionsAreAddedToTheSnapshot() {
        JsonObject root = JsonParser.parseString("{\"shops\":{}}").getAsJsonObject();
        String out = BalanceService.addSections(root, new JsonObject(), new JsonObject());
        JsonObject o = JsonParser.parseString(out).getAsJsonObject();
        assertTrue(o.has("shops"));
        assertTrue(o.has("datapack"));
        assertTrue(o.has("datapackCatalog"));
    }
}
