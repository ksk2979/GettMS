package org.gettserver.gettcore.mobs;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.gettserver.gettcore.mobs.MobStatsConfig.Stats;
import org.junit.jupiter.api.Test;

class MobStatsTest {
    private static MobStatsConfig parse(String json) {
        return MobStatsConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    @Test
    void parsesDefaultsFile() throws Exception {
        try (InputStream in = MobStatsTest.class.getResourceAsStream("/gettcore_defaults/mobs.json")) {
            assertNotNull(in, "bundled mobs.json");
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            MobStatsConfig parsed = MobStatsConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            assertEquals(MobStatsConfig.DEFAULT, parsed);
            List<String> warnings = new ArrayList<>();
            assertEquals(MobStatsConfig.DEFAULT, parsed.sanitized(warnings));
            assertTrue(warnings.isEmpty(), warnings.toString());
        }
    }

    @Test
    void missingValuesStayAbsentAndRoundTrip() {
        MobStatsConfig c = parse("{\"overrides\":{\"minecraft:zombie\":{\"health\":40,\"speed\":0.3}}}");
        Stats s = c.overrides().get("minecraft:zombie");
        assertEquals(Optional.of(40.0), s.health());
        assertEquals(Optional.empty(), s.attack());
        assertEquals(Optional.of(0.3), s.speed());
        assertEquals(Optional.empty(), s.armor());
        var json = MobStatsConfig.CODEC.encodeStart(JsonOps.INSTANCE, c).getOrThrow();
        assertEquals("{\"overrides\":{\"minecraft:zombie\":{\"health\":40.0,\"speed\":0.3}}}", json.toString());
        assertEquals(c, MobStatsConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        assertEquals(MobStatsConfig.DEFAULT, parse("{}"));
    }

    @Test
    void sanitizeClampsDropsBadKeysAndEmptyRows() {
        MobStatsConfig c = new MobStatsConfig(Map.of(
                "minecraft:zombie", new Stats(Optional.of(-3.0), Optional.of(-1.0), Optional.empty(), Optional.of(99.0)),
                "Not An Id", new Stats(Optional.of(10.0), Optional.empty(), Optional.empty(), Optional.empty()),
                "minecraft:pig", new Stats(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));
        List<String> warnings = new ArrayList<>();
        MobStatsConfig s = c.sanitized(warnings);
        assertEquals(1, s.overrides().size(), s.toString());
        Stats z = s.overrides().get("minecraft:zombie");
        assertEquals(Optional.of(MobStatsConfig.MIN_HEALTH), z.health());
        assertEquals(Optional.of(0.0), z.attack());
        assertEquals(Optional.of(MobStatsConfig.MAX_ARMOR), z.armor());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Not An Id")), warnings.toString());
        assertTrue(warnings.stream().allMatch(w -> w.startsWith("mobs.json: ")), warnings.toString());
        List<String> none = new ArrayList<>();
        assertEquals(s, s.sanitized(none));
        assertTrue(none.isEmpty(), none.toString());
    }

    @Test
    void overrideReplacesOnlyGivenValues() {
        assertEquals(40.0, MobStatsRules.base(20.0, Optional.of(40.0)));
        assertEquals(20.0, MobStatsRules.base(20.0, Optional.empty()));
    }

    @Test
    void bossMultiplierAppliesOnceOnTopOfOverride() {
        // Override first (spawn), then the boss multiplier once (first tick / BossSpawner).
        double afterOverride = MobStatsRules.base(30.0, Optional.of(100.0));
        double afterBoss = MobStatsRules.bossBase(afterOverride, 5.0);
        assertEquals(500.0, afterBoss);
        // Restart: both steps are flagged, so a reloaded boss keeps 500 (no second override, no second multiply).
        assertFalse(MobStatsRules.shouldApply(true, false, false, false, false));
        assertFalse(MobStatsRules.shouldApply(false, true, false, false, false));
        assertEquals(500.0, MobStatsRules.bossBase(afterOverride, 5.0));
        assertEquals(100.0, MobStatsRules.bossBase(100.0, 0), "non-positive multiplier is ignored");
    }

    @Test
    void appliesOnlyToNewNonPlayerNonPetNonVariant() {
        assertTrue(MobStatsRules.shouldApply(false, false, false, false, false));
        assertFalse(MobStatsRules.shouldApply(true, false, false, false, false), "already applied (flag)");
        assertFalse(MobStatsRules.shouldApply(false, true, false, false, false), "loaded from disk = existing");
        assertFalse(MobStatsRules.shouldApply(false, false, true, false, false), "player");
        assertFalse(MobStatsRules.shouldApply(false, false, false, true, false), "gettcore pet");
        assertFalse(MobStatsRules.shouldApply(false, false, false, false, true), "hunting variant");
    }
}
