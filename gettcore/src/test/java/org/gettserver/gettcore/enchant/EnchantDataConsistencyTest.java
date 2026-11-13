package org.gettserver.gettcore.enchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;
import org.junit.jupiter.api.Test;

/** 데이터팩 JSON·태그·언어 파일이 {@link Ability} 정의와 맞는지. */
class EnchantDataConsistencyTest {

    private static JsonObject read(String path) throws Exception {
        try (InputStream in = EnchantDataConsistencyTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void enchantmentJsonMatchesEnum() throws Exception {
        for (Ability a : Ability.values()) {
            JsonObject json = read("/data/gettcore/enchantment/" + a.id() + ".json");
            assertEquals(a.maxLevel(), json.get("max_level").getAsInt(), a.id() + " max_level");
        }
    }

    @Test
    void specialTagListsAllAbilities() throws Exception {
        Set<String> values = new HashSet<>();
        for (JsonElement e : read("/data/gettcore/tags/enchantment/special.json").getAsJsonArray("values")) {
            values.add(e.getAsString());
        }
        Set<String> expected = new HashSet<>();
        for (Ability a : Ability.values()) expected.add("gettcore:" + a.id());
        assertEquals(34, expected.size());
        assertEquals(expected, values);
    }

    @Test
    void langFilesHaveNameAndDescription() throws Exception {
        for (String lang : new String[] {"en_us", "ko_kr"}) {
            JsonObject json = read("/assets/gettcore/lang/" + lang + ".json");
            for (Ability a : Ability.values()) {
                String key = "enchantment.gettcore." + a.id();
                assertTrue(json.has(key), lang + ": " + key);
                assertTrue(json.has(key + ".desc"), lang + ": " + key + ".desc");
            }
        }
    }
}
