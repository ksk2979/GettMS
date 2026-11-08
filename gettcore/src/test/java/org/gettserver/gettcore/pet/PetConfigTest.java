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
        assertEquals(50.0, partial.capture().ballChance().get(CaptureTier.GOLD));
        assertEquals(10.0, partial.capture().ballChance().get(CaptureTier.IRON));
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
        assertEquals(6.0, s.attack());
        assertEquals(0, s.killGold());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("a:w sizeScale 0.0 must be > 0")), warnings.toString());
        assertEquals(4, warnings.size(), warnings.toString());
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
}
