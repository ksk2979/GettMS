package org.gettserver.gettcore.pet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class PetConfigTest {

    private static PetConfig parse(String json) {
        return PetConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    private static PetConfig defaultFile() throws Exception {
        try (InputStream in = PetConfigTest.class.getResourceAsStream("/gettcore_defaults/pets.json")) {
            assertNotNull(in, "bundled pets.json");
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return PetConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    @Test
    void parsesDefaultFile() throws Exception {
        PetConfig cfg = defaultFile();
        assertEquals(15, cfg.species().size());
        PetSpecies lion = cfg.species(ResourceLocation.parse("naturalist:lion")).orElseThrow();
        assertEquals(PetSpecies.Kind.FIGHTER, lion.kind());
        assertEquals("naturalist:lion", lion.id());
        assertTrue(lion.obtainChance().isEmpty());
        assertTrue(lion.ballChance().isEmpty());
        PetSpecies vulture = cfg.species().get("gettcore:boss_vulture");
        assertEquals(PetSpecies.Kind.FLYING_MOUNT, vulture.kind());
        assertTrue(vulture.boss());
        assertEquals(2.5, vulture.sizeScale());
        // Bundled defaults are neutral: no species overrides the global tables.
        for (PetSpecies s : cfg.species().values()) {
            assertTrue(s.ballChance().isEmpty() && s.obtainChance().isEmpty(), s.id());
        }
        assertEquals(3.0, cfg.species().get("naturalist:ostrich").jumpHeight());
        assertEquals(2.0, cfg.species().get("naturalist:giraffe").jumpHeight());
        assertEquals(1.0, cfg.species().get("naturalist:elephant").jumpHeight());
        assertEquals(1.0, cfg.species().get("naturalist:mammoth").jumpHeight());
        assertEquals(1.0, lion.jumpHeight());
        assertEquals(0xFF55FF55, cfg.healthbar().captureColor());
        assertEquals(new PetConfig.HealthBar(32, 200, 20, 0xFF55FF55), cfg.healthbar());
        assertEquals(PetConfig.DEFAULT.healthbar(), cfg.healthbar());
        assertEquals(PetConfig.DEFAULT.capture(), cfg.capture());
        List<String> warnings = new ArrayList<>();
        assertEquals(cfg, cfg.sanitized(warnings));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void missingFieldsFallToDefault() {
        assertEquals(PetConfig.DEFAULT, parse("{}"));
        PetConfig partial = parse("{\"capture\":{\"ballChance\":{\"gold\":50}}}");
        assertEquals(new PetConfig.ChanceRange(50, 50), partial.capture().ballChance().get(CaptureTier.GOLD));
        assertEquals(new PetConfig.ChanceRange(5, 25), partial.capture().ballChance().get(CaptureTier.IRON));
    }

    @Test
    void defaultChanceRanges() {
        var c = PetConfig.DEFAULT.capture();
        assertEquals(new PetConfig.ChanceRange(5, 25), c.ballChance().get(CaptureTier.IRON));
        assertEquals(new PetConfig.ChanceRange(8, 35), c.ballChance().get(CaptureTier.GOLD));
        assertEquals(new PetConfig.ChanceRange(12, 50), c.ballChance().get(CaptureTier.DIAMOND));
        assertEquals(new PetConfig.ChanceRange(20, 70), c.ballChance().get(CaptureTier.NETHERITE));
        assertEquals(new PetConfig.ChanceRange(3, 12), c.bossBallChance().get(CaptureTier.IRON));
        assertEquals(new PetConfig.ChanceRange(5, 18), c.bossBallChance().get(CaptureTier.GOLD));
        assertEquals(new PetConfig.ChanceRange(8, 25), c.bossBallChance().get(CaptureTier.DIAMOND));
        assertEquals(new PetConfig.ChanceRange(12, 40), c.bossBallChance().get(CaptureTier.NETHERITE));
    }

    @Test
    void chanceRangeObjectFormParsesAndEncodesAsObject() {
        PetConfig cfg = parse("{\"capture\":{\"bossBallChance\":{\"gold\":{\"base\":4,\"max\":30}}}}");
        assertEquals(new PetConfig.ChanceRange(4, 30), cfg.capture().bossBallChance().get(CaptureTier.GOLD));
        var json = PetConfig.CODEC.encodeStart(JsonOps.INSTANCE, cfg).getOrThrow().getAsJsonObject();
        var gold = json.getAsJsonObject("capture").getAsJsonObject("bossBallChance").getAsJsonObject("gold");
        assertEquals(4, gold.get("base").getAsDouble());
        assertEquals(30, gold.get("max").getAsDouble());
        assertEquals(cfg, PetConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
    }

    @Test
    void sanitizeFixesChanceRanges() {
        PetConfig cfg = parse("{\"capture\":{\"ballChance\":{\"iron\":{\"base\":30,\"max\":10},"
                + "\"gold\":{\"base\":-1,\"max\":40},\"diamond\":{\"base\":10,\"max\":150}}}}");
        List<String> warnings = new ArrayList<>();
        var ball = cfg.sanitized(warnings).capture().ballChance();
        assertEquals(new PetConfig.ChanceRange(30, 30), ball.get(CaptureTier.IRON));
        assertEquals(new PetConfig.ChanceRange(8, 40), ball.get(CaptureTier.GOLD));
        assertEquals(new PetConfig.ChanceRange(10, 50), ball.get(CaptureTier.DIAMOND));
        assertEquals(new PetConfig.ChanceRange(20, 70), ball.get(CaptureTier.NETHERITE));
        assertEquals(3, warnings.size(), warnings.toString());
    }

    @Test
    void speciesWithoutOverridesHasEmptyMaps() {
        PetConfig cfg = parse("{\"species\":{\"a:w\":{\"kind\":\"fighter\",\"petEntity\":\"a:p\"},"
                + "\"a:b\":{\"kind\":\"mount\",\"petEntity\":\"a:q\",\"boss\":true}}}");
        assertTrue(cfg.species().get("a:w").obtainChance().isEmpty());
        assertTrue(cfg.species().get("a:b").obtainChance().isEmpty(), "boss default now comes from the global table");
        assertTrue(cfg.species().get("a:b").ballChance().isEmpty());
    }

    @Test
    void defaultObtainTables() {
        var c = PetConfig.DEFAULT.capture();
        assertEquals(Map.of(CaptureTier.IRON, 20.0, CaptureTier.GOLD, 25.0, CaptureTier.DIAMOND, 30.0,
                CaptureTier.NETHERITE, 40.0), c.obtainChance());
        assertEquals(Map.of(CaptureTier.IRON, 15.0, CaptureTier.GOLD, 20.0, CaptureTier.DIAMOND, 25.0,
                CaptureTier.NETHERITE, 35.0), c.bossObtainChance());
        assertEquals(c, parse("{\"capture\":{}}").capture());
    }

    @Test
    void legacyGlobalObtainPercentFillsBothTables() {
        var c = parse("{\"capture\":{\"obtainPercent\":33}}").capture();
        assertEquals(PetConfig.Capture.allTiers(33), c.obtainChance());
        assertEquals(PetConfig.Capture.allTiers(33), c.bossObtainChance());
        // A present table wins over the legacy value; the absent one still takes it.
        var mixed = parse("{\"capture\":{\"obtainPercent\":33,\"obtainChance\":{\"gold\":50}}}").capture();
        assertEquals(50.0, mixed.obtainChance().get(CaptureTier.GOLD));
        assertEquals(20.0, mixed.obtainChance().get(CaptureTier.IRON), "missing tier takes the default");
        assertEquals(PetConfig.Capture.allTiers(33), mixed.bossObtainChance());
        // 30 is an explicit choice: every tier of both tables.
        var thirty = parse("{\"capture\":{\"obtainPercent\":30}}").capture();
        assertEquals(PetConfig.Capture.allTiers(30), thirty.obtainChance());
        assertEquals(PetConfig.Capture.allTiers(30), thirty.bossObtainChance());
    }

    @Test
    void legacyGlobalObtainPercentAtOldDefaultIsIgnored() {
        // The shipped 1.3.0 pets.json carried obtainPercent: 20; it must not flatten the new tier tables.
        var c = parse("{\"capture\":{\"obtainPercent\":20}}").capture();
        assertEquals(PetConfig.DEFAULT.capture().obtainChance(), c.obtainChance());
        assertEquals(PetConfig.DEFAULT.capture().bossObtainChance(), c.bossObtainChance());
        assertEquals(20.0, c.obtainChance().get(CaptureTier.IRON));
        assertEquals(40.0, c.obtainChance().get(CaptureTier.NETHERITE));
        assertEquals(15.0, c.bossObtainChance().get(CaptureTier.IRON));
    }

    @Test
    void partialGlobalObtainTableFallsBackToDefaults() {
        var c = parse("{\"capture\":{\"bossObtainChance\":{\"netherite\":90}}}").capture();
        assertEquals(90.0, c.bossObtainChance().get(CaptureTier.NETHERITE));
        assertEquals(15.0, c.bossObtainChance().get(CaptureTier.IRON));
        assertEquals(PetConfig.DEFAULT.capture().obtainChance(), c.obtainChance());
    }

    @Test
    void legacySpeciesObtainPercentFillsAllTiers() {
        PetConfig cfg = parse("{\"species\":{\"a:w\":{\"kind\":\"fighter\",\"petEntity\":\"a:p\",\"obtainPercent\":45},"
                + "\"a:z\":{\"kind\":\"fighter\",\"petEntity\":\"a:p\",\"obtainPercent\":0},"
                + "\"a:x\":{\"kind\":\"fighter\",\"petEntity\":\"a:p\",\"obtainPercent\":45,\"obtainChance\":{\"iron\":10}}}}");
        assertEquals(PetConfig.Capture.allTiers(45), cfg.species().get("a:w").obtainChance());
        assertTrue(cfg.species().get("a:z").obtainChance().isEmpty(), "0 meant 'use global'");
        assertEquals(Map.of(CaptureTier.IRON, 10.0), cfg.species().get("a:x").obtainChance(), "new key wins");
    }

    @Test
    void legacySpeciesObtainPercentTwentyOnBossMeansNoOverride() {
        PetConfig cfg = parse("{\"species\":{"
                + "\"a:b20\":{\"kind\":\"fighter\",\"petEntity\":\"a:p\",\"boss\":true,\"obtainPercent\":20},"
                + "\"a:b35\":{\"kind\":\"fighter\",\"petEntity\":\"a:p\",\"boss\":true,\"obtainPercent\":35},"
                + "\"a:n20\":{\"kind\":\"fighter\",\"petEntity\":\"a:p\",\"obtainPercent\":20}}}");
        assertTrue(cfg.species().get("a:b20").obtainChance().isEmpty(), "1.3.0 implicit boss default");
        assertEquals(PetConfig.Capture.allTiers(35), cfg.species().get("a:b35").obtainChance());
        assertEquals(PetConfig.Capture.allTiers(20), cfg.species().get("a:n20").obtainChance());
    }

    @Test
    void speciesOverridesParseAndRoundTrip() {
        PetConfig cfg = parse("{\"species\":{\"a:w\":{\"kind\":\"fighter\",\"petEntity\":\"a:p\","
                + "\"ballChance\":{\"gold\":{\"base\":30,\"max\":60},\"iron\":7},\"obtainChance\":{\"netherite\":50}}}}");
        PetSpecies s = cfg.species().get("a:w");
        assertEquals(Map.of(CaptureTier.GOLD, new PetConfig.ChanceRange(30, 60),
                CaptureTier.IRON, new PetConfig.ChanceRange(7, 7)), s.ballChance());
        assertEquals(Map.of(CaptureTier.NETHERITE, 50.0), s.obtainChance());
        var json = PetConfig.CODEC.encodeStart(JsonOps.INSTANCE, cfg).getOrThrow().getAsJsonObject();
        var sj = json.getAsJsonObject("species").getAsJsonObject("a:w");
        assertEquals(30, sj.getAsJsonObject("ballChance").getAsJsonObject("gold").get("base").getAsDouble());
        assertEquals(50, sj.getAsJsonObject("obtainChance").get("netherite").getAsDouble());
        assertEquals(cfg, PetConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
    }

    @Test
    void encodeWritesOnlyNewKeys() throws Exception {
        PetConfig legacy = parse("{\"capture\":{\"obtainPercent\":33},\"species\":{"
                + "\"a:w\":{\"kind\":\"fighter\",\"petEntity\":\"a:p\",\"obtainPercent\":45},"
                + "\"a:e\":{\"kind\":\"fighter\",\"petEntity\":\"a:p\"}}}");
        var json = PetConfig.CODEC.encodeStart(JsonOps.INSTANCE, legacy).getOrThrow().getAsJsonObject();
        var capture = json.getAsJsonObject("capture");
        assertFalse(capture.has("obtainPercent"), capture.toString());
        assertEquals(33, capture.getAsJsonObject("obtainChance").get("diamond").getAsDouble());
        assertEquals(33, capture.getAsJsonObject("bossObtainChance").get("iron").getAsDouble());
        var w = json.getAsJsonObject("species").getAsJsonObject("a:w");
        assertFalse(w.has("obtainPercent"), w.toString());
        assertEquals(45, w.getAsJsonObject("obtainChance").get("gold").getAsDouble());
        var e = json.getAsJsonObject("species").getAsJsonObject("a:e");
        assertFalse(e.has("obtainChance"), "empty override not encoded: " + e);
        assertFalse(e.has("ballChance"), "empty override not encoded: " + e);
        assertFalse(e.has("obtainPercent"), e.toString());
        assertEquals(legacy, PetConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        String all = PetConfig.CODEC.encodeStart(JsonOps.INSTANCE, defaultFile()).getOrThrow().toString();
        assertFalse(all.contains("obtainPercent"), all);
    }

    @Test
    void sanitizeObtainTablesUsesTierDefault() {
        PetConfig cfg = parse("{\"capture\":{\"obtainChance\":{\"gold\":150},"
                + "\"bossObtainChance\":{\"iron\":-1,\"netherite\":100}}}");
        List<String> warnings = new ArrayList<>();
        var c = cfg.sanitized(warnings).capture();
        assertEquals(25.0, c.obtainChance().get(CaptureTier.GOLD));
        assertEquals(15.0, c.bossObtainChance().get(CaptureTier.IRON));
        assertEquals(100.0, c.bossObtainChance().get(CaptureTier.NETHERITE));
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("capture.obtainChance.gold 150.0")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("capture.bossObtainChance.iron -1.0")),
                warnings.toString());
    }

    @Test
    void sanitizeSpeciesOverridesDropsBadTiers() {
        PetConfig cfg = parse("{\"species\":{\"a:w\":{\"kind\":\"fighter\",\"petEntity\":\"a:p\","
                + "\"ballChance\":{\"iron\":{\"base\":-1,\"max\":20},\"gold\":{\"base\":30,\"max\":10},"
                + "\"diamond\":{\"base\":10,\"max\":20}},\"obtainChance\":{\"gold\":101,\"diamond\":60}}}}");
        List<String> warnings = new ArrayList<>();
        PetSpecies s = cfg.sanitized(warnings).species().get("a:w");
        assertEquals(Map.of(CaptureTier.GOLD, new PetConfig.ChanceRange(30, 30),
                CaptureTier.DIAMOND, new PetConfig.ChanceRange(10, 20)), s.ballChance());
        assertEquals(Map.of(CaptureTier.DIAMOND, 60.0), s.obtainChance());
        assertEquals(3, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("a:w ballChance.iron")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("a:w ballChance.gold.max 10.0")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("a:w obtainChance.gold 101.0")), warnings.toString());
    }

    @Test
    void sanitizeClampsPercent() {
        PetConfig cfg = parse("{\"capture\":{\"maxPercent\":150}}");
        assertEquals(150, cfg.capture().maxPercent());
        List<String> warnings = new ArrayList<>();
        PetConfig fixed = cfg.sanitized(warnings);
        assertEquals(20, fixed.capture().maxPercent());
        assertEquals(1, warnings.size(), warnings.toString());
    }

    @Test
    void roundTripsStreamCodec() throws Exception {
        PetConfig cfg = defaultFile();
        ByteBuf buf = Unpooled.buffer();
        PetConfig.STREAM_CODEC.encode(buf, cfg);
        assertEquals(cfg, PetConfig.STREAM_CODEC.decode(buf));
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void petKeyRoundTrips() {
        PetKey key = new PetKey("naturalist:lion", "albino");
        ByteBuf buf = Unpooled.buffer();
        PetKey.STREAM_CODEC.encode(buf, key);
        assertEquals(key, PetKey.STREAM_CODEC.decode(buf));
        var json = PetKey.CODEC.encodeStart(JsonOps.INSTANCE, key).getOrThrow();
        assertEquals(key, PetKey.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
    }

    @Test
    void invalidKindIsCodecErrorNotException() {
        var json = JsonParser.parseString(
                "{\"species\":{\"a:w\":{\"kind\":\"fliying_mount\",\"petEntity\":\"a:p\"}}}");
        var result = PetConfig.CODEC.parse(JsonOps.INSTANCE, json);
        assertTrue(result.error().isPresent());
        assertTrue(result.error().get().message().contains("fliying_mount"), result.error().get().message());
    }

    @Test
    void sanitizeFixesSpeciesMultipliersAndNegatives() {
        PetConfig cfg = parse("{\"species\":{\"a:w\":{\"kind\":\"fighter\",\"petEntity\":\"a:p\","
                + "\"sizeScale\":0,\"healthMult\":-1,\"attack\":-3,\"killGold\":-5}}}");
        List<String> warnings = new ArrayList<>();
        PetSpecies s = cfg.sanitized(warnings).species().get("a:w");
        assertEquals(1.0, s.sizeScale());
        assertEquals(1.0, s.healthMult());
        assertEquals(2.0, s.attack());
        assertEquals(0, s.killGold());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("a:w sizeScale 0.0 must be > 0")), warnings.toString());
        assertEquals(4, warnings.size(), warnings.toString());
    }

    @Test
    void sanitizeResetsOutOfRangeJumpHeight() {
        PetConfig cfg = parse("{\"species\":{\"a:w\":{\"kind\":\"mount\",\"petEntity\":\"a:p\",\"jumpHeight\":11},"
                + "\"a:x\":{\"kind\":\"mount\",\"petEntity\":\"a:p\",\"jumpHeight\":-1},"
                + "\"a:y\":{\"kind\":\"mount\",\"petEntity\":\"a:p\",\"jumpHeight\":10}}}");
        List<String> warnings = new ArrayList<>();
        PetConfig fixed = cfg.sanitized(warnings);
        assertEquals(1.0, fixed.species().get("a:w").jumpHeight());
        assertEquals(1.0, fixed.species().get("a:x").jumpHeight());
        assertEquals(10.0, fixed.species().get("a:y").jumpHeight());
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("a:w jumpHeight 11.0")), warnings.toString());
        assertEquals(1.0, parse("{\"species\":{\"a:w\":{\"kind\":\"mount\",\"petEntity\":\"a:p\"}}}")
                .species().get("a:w").jumpHeight());
    }

    @Test
    void sanitizeClampsHealthBar() {
        PetConfig cfg = parse("{\"healthbar\":{\"showDistance\":0,\"fadeDelayTicks\":5000,\"fadeTicks\":0}}");
        List<String> warnings = new ArrayList<>();
        PetConfig.HealthBar h = cfg.sanitized(warnings).healthbar();
        assertEquals(1, h.showDistance());
        assertEquals(1200, h.fadeDelayTicks());
        assertEquals(1, h.fadeTicks());
        assertEquals(3, warnings.size(), warnings.toString());
    }

    @Test
    void bundledFileNeedsNoSanitizing() throws Exception {
        List<String> warnings = new ArrayList<>();
        PetConfig cfg = defaultFile();
        assertEquals(cfg, cfg.sanitized(warnings));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void throwCooldownDefaultsTo20AndParses() {
        assertEquals(20, PetConfig.DEFAULT.capture().throwCooldownTicks());
        assertEquals(20, parse("{\"capture\":{}}").capture().throwCooldownTicks());
        assertEquals(40, parse("{\"capture\":{\"throwCooldownTicks\":40}}").capture().throwCooldownTicks());
    }

    @Test
    void sanitizeThrowCooldownOutOfRangeUsesDefault() {
        for (int bad : new int[] {-5, 201, 500}) {
            List<String> warnings = new ArrayList<>();
            PetConfig fixed = parse("{\"capture\":{\"throwCooldownTicks\":" + bad + "}}").sanitized(warnings);
            assertEquals(20, fixed.capture().throwCooldownTicks(), "value " + bad);
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains("capture.throwCooldownTicks"), warnings.toString());
        }
        for (int ok : new int[] {0, 200}) {
            List<String> warnings = new ArrayList<>();
            PetConfig fixed = parse("{\"capture\":{\"throwCooldownTicks\":" + ok + "}}").sanitized(warnings);
            assertEquals(ok, fixed.capture().throwCooldownTicks());
            assertTrue(warnings.isEmpty(), warnings.toString());
        }
    }
}
