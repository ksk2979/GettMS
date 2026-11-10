package org.gettserver.gettcore.fieldgold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.MobSpawnType;
import org.junit.jupiter.api.Test;

class FieldGoldTest {
    private static final ResourceLocation ZOMBIE = ResourceLocation.withDefaultNamespace("zombie");
    private static final ResourceLocation ENDERMAN = ResourceLocation.withDefaultNamespace("enderman");

    @Test
    void autoPriceUsesStatsAndMinimum() {
        FieldGoldConfig c = FieldGoldConfig.DEFAULT;
        // zombie: 20 hp, 3 attack, 2 armor -> 10 + 3 + 2
        assertEquals(15, c.price(ZOMBIE, 20, 3, 2));
        // weak mob still pays the minimum
        assertEquals(10, c.price(ZOMBIE, 8, 1, 0));
        // strong mod mob scales with its stats
        assertEquals(170, c.price(ResourceLocation.fromNamespaceAndPath("x", "brute"), 300, 15, 5));
    }

    @Test
    void autoPriceCappedAtMax() {
        assertEquals(1000, FieldGoldConfig.DEFAULT.price(ZOMBIE, 100000, 0, 0));
    }

    @Test
    void overrideWinsAndZeroDisables() {
        assertEquals(40, FieldGoldConfig.DEFAULT.price(ENDERMAN, 40, 7, 0));
        FieldGoldConfig c = new FieldGoldConfig(true, 10, 1000, 0.5, 1, 1, Map.of("minecraft:zombie", 0L));
        assertEquals(0, c.price(ZOMBIE, 20, 3, 2));
    }

    @Test
    void disabledPaysNothing() {
        FieldGoldConfig c = new FieldGoldConfig(false, 10, 1000, 0.5, 1, 1, Map.of());
        assertEquals(0, c.price(ZOMBIE, 20, 3, 2));
    }

    @Test
    void badStatsCountAsZero() {
        assertEquals(10, FieldGoldConfig.DEFAULT.price(ZOMBIE, Double.NaN, -5, Double.POSITIVE_INFINITY));
    }

    @Test
    void sanitizeClampsAndDropsBadKeys() {
        List<String> warnings = new ArrayList<>();
        FieldGoldConfig c = new FieldGoldConfig(true, 50, 20, -1, Double.NaN, 2,
                Map.of("Bad Key!", 5L, "minecraft:zombie", -3L)).sanitized(warnings);
        assertEquals(50, c.min());
        assertEquals(50, c.max());
        assertEquals(0, c.healthRate());
        assertEquals(0, c.attackRate());
        assertEquals(Map.of("minecraft:zombie", 0L), c.overrides());
        assertEquals(5, warnings.size());
    }

    @Test
    void sanitizeKeepsCleanConfig() {
        List<String> warnings = new ArrayList<>();
        assertSame(FieldGoldConfig.DEFAULT, FieldGoldConfig.DEFAULT.sanitized(warnings));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void codecFillsDefaults() {
        FieldGoldConfig c = FieldGoldConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"min\": 20}"))
                .getOrThrow();
        assertEquals(20, c.min());
        assertEquals(1000, c.max());
        assertTrue(c.enabled());
        assertTrue(c.overrides().isEmpty());
    }

    @Test
    void farmSpawnsDoNotPay() {
        assertTrue(FieldGoldRules.paysFor(MobSpawnType.NATURAL));
        assertTrue(FieldGoldRules.paysFor(MobSpawnType.CHUNK_GENERATION));
        assertTrue(FieldGoldRules.paysFor(MobSpawnType.STRUCTURE));
        assertTrue(FieldGoldRules.paysFor(MobSpawnType.PATROL));
        assertFalse(FieldGoldRules.paysFor(MobSpawnType.SPAWNER));
        assertFalse(FieldGoldRules.paysFor(MobSpawnType.TRIAL_SPAWNER));
        assertFalse(FieldGoldRules.paysFor(MobSpawnType.SPAWN_EGG));
        assertFalse(FieldGoldRules.paysFor(MobSpawnType.COMMAND));
        assertFalse(FieldGoldRules.paysFor(MobSpawnType.EVENT));
    }
}
