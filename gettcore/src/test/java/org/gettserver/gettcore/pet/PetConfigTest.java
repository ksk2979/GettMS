package org.gettserver.gettcore.pet;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(0, lion.obtainPercent());
        PetSpecies vulture = cfg.species().get("gettcore:boss_vulture");
        assertEquals(PetSpecies.Kind.FLYING_MOUNT, vulture.kind());
        assertTrue(vulture.boss());
        assertEquals(2.5, vulture.sizeScale());
        assertEquals(20, vulture.obtainPercent());
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
    void bossObtainPercentDefaultsTo20() {
        PetConfig cfg = parse("{\"species\":{\"a:w\":{\"kind\":\"fighter\",\"petEntity\":\"a:p\"},"
                + "\"a:b\":{\"kind\":\"mount\",\"petEntity\":\"a:q\",\"boss\":true}}}");
        assertEquals(0, cfg.species().get("a:w").obtainPercent());
        assertEquals(20, cfg.species().get("a:b").obtainPercent());
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
