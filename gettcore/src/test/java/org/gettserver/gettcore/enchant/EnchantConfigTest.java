package org.gettserver.gettcore.enchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.gettserver.gettcore.enchant.SpecialEnchants.LevelWeights;
import org.junit.jupiter.api.Test;

class EnchantConfigTest {

    private static EnchantConfig parse(String json) {
        return EnchantConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    @Test
    void parsesDefaultsFile() throws Exception {
        try (InputStream in = EnchantConfigTest.class.getResourceAsStream("/gettcore_defaults/enchant.json")) {
            assertNotNull(in, "bundled enchant.json");
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            EnchantConfig parsed = EnchantConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            assertEquals(EnchantConfig.DEFAULT, parsed);
            List<String> errors = new ArrayList<>();
            assertEquals(EnchantConfig.DEFAULT, parsed.sanitized(errors));
            assertTrue(errors.isEmpty(), errors.toString());
        }
    }

    @Test
    void invalidFieldFallsBackAlone() {
        EnchantConfig c = parse("{\"harvest\":{\"radius\":[1,2]},\"vein\":{\"maxBlocks\":[8,16,32]}}");
        List<String> errors = new ArrayList<>();
        EnchantConfig s = c.sanitized(errors);
        assertEquals(EnchantConfig.DEFAULT.harvest(), s.harvest());
        assertEquals(32, s.vein().three());
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("harvest"));
    }

    @Test
    void zeroWeightsReported() {
        EnchantConfig c = parse("{\"levelWeights\":{\"1\":0,\"2\":0,\"3\":0}}");
        List<String> errors = new ArrayList<>();
        EnchantConfig s = c.sanitized(errors);
        assertEquals(LevelWeights.DEFAULT, s.levelWeights());
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("levelWeights"));
    }

    @Test
    void negativeScalarFallsBack() {
        EnchantConfig c = parse("{\"sweep\":{\"damagePercent\":-5},\"homing\":{\"range\":12}}");
        List<String> errors = new ArrayList<>();
        EnchantConfig s = c.sanitized(errors);
        assertEquals(EnchantConfig.DEFAULT.sweepDamagePercent(), s.sweepDamagePercent());
        assertEquals(12, s.homingRange());
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("sweep.damagePercent"));
    }

    @Test
    void valueReturnsPrimaryTieredNumber() {
        EnchantConfig d = EnchantConfig.DEFAULT;
        assertEquals(2, d.value(SpecialEnchants.Ability.HARVEST, 2));
        assertEquals(100, d.value(SpecialEnchants.Ability.EXECUTE, 3));
        assertEquals(10, d.value(SpecialEnchants.Ability.VOLLEY, 1));
        assertEquals(8, d.value(SpecialEnchants.Ability.HOMING, 1));
        assertEquals(0, d.value(SpecialEnchants.Ability.SMELT, 1));
    }
}
