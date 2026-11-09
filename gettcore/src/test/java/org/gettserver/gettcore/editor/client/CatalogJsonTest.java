package org.gettserver.gettcore.editor.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogJsonTest {
    @Test
    void buildsExactShapeSorted() {
        String json = CatalogJson.build(
                List.of(new CatalogJson.Entry("minecraft:stone", "돌", "Minecraft"),
                        new CatalogJson.Entry("minecraft:apple", "사과", "Minecraft")),
                List.of(new CatalogJson.Entry("minecraft:zombie", "좀비", "Minecraft")),
                List.of("minecraft:logs", "c:ingots"),
                List.of(),
                List.of("gettcore:boss_tiger", "gettcore:boss_lion"),
                List.of(new CatalogJson.Entry("minecraft:strength", "힘", "Minecraft"),
                        new CatalogJson.Entry("minecraft:speed", "신속", "Minecraft")),
                List.of(new CatalogJson.MobStat("minecraft:zombie", "좀비", "Minecraft", "monster", 20.0, 3.0, 0.23, 2.0),
                        new CatalogJson.MobStat("minecraft:creeper", "크리퍼", "Minecraft", "monster", 20.0, null, 0.25, 0.0)));
        assertEquals("{\"items\":[{\"id\":\"minecraft:apple\",\"name\":\"사과\",\"mod\":\"Minecraft\"},"
                + "{\"id\":\"minecraft:stone\",\"name\":\"돌\",\"mod\":\"Minecraft\"}],"
                + "\"entities\":[{\"id\":\"minecraft:zombie\",\"name\":\"좀비\",\"mod\":\"Minecraft\"}],"
                + "\"itemTags\":[\"c:ingots\",\"minecraft:logs\"],\"entityTags\":[],"
                + "\"bossTypes\":[\"gettcore:boss_lion\",\"gettcore:boss_tiger\"],"
                + "\"petKinds\":[\"fighter\",\"mount\",\"flying_mount\"],"
                + "\"mobEffects\":[{\"id\":\"minecraft:speed\",\"name\":\"신속\",\"mod\":\"Minecraft\"},"
                + "{\"id\":\"minecraft:strength\",\"name\":\"힘\",\"mod\":\"Minecraft\"}],"
                + "\"mobs\":[{\"id\":\"minecraft:creeper\",\"name\":\"크리퍼\",\"mod\":\"Minecraft\",\"category\":\"monster\","
                + "\"health\":20.0,\"speed\":0.25,\"armor\":0.0},"
                + "{\"id\":\"minecraft:zombie\",\"name\":\"좀비\",\"mod\":\"Minecraft\",\"category\":\"monster\","
                + "\"health\":20.0,\"attack\":3.0,\"speed\":0.23,\"armor\":2.0}],\"gear\":[]}", json);
    }

    @Test
    void gearShape() {
        String json = CatalogJson.build(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new CatalogJson.Gear("minecraft:shield", "방패", "Minecraft", "shield", "offhand",
                                null, null, null, null, null),
                        new CatalogJson.Gear("minecraft:diamond_sword", "다이아몬드 검", "Minecraft", "melee", "mainhand",
                                7.0, 1.6, null, null, null)));
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("[{\"id\":\"minecraft:diamond_sword\",\"name\":\"다이아몬드 검\",\"mod\":\"Minecraft\","
                + "\"kind\":\"melee\",\"slot\":\"mainhand\",\"attackDamage\":7.0,\"attackSpeed\":1.6},"
                + "{\"id\":\"minecraft:shield\",\"name\":\"방패\",\"mod\":\"Minecraft\",\"kind\":\"shield\","
                + "\"slot\":\"offhand\"}]", o.getAsJsonArray("gear").toString());
    }

    @Test
    void escapesNames() {
        String json = CatalogJson.build(List.of(new CatalogJson.Entry("a:b", "say \"hi\"\n", "m")), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("say \"hi\"\n", o.getAsJsonArray("items").get(0).getAsJsonObject().get("name").getAsString());
    }
}
