package org.gettserver.gettcore.reinforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReinforceConfigTest {

    private static ReinforceConfig parse(String json) {
        return ReinforceConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    @Test
    void parsesDefaultsFile() throws Exception {
        try (InputStream in = ReinforceConfigTest.class.getResourceAsStream("/gettcore_defaults/reinforce.json")) {
            assertNotNull(in, "bundled reinforce.json");
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            ReinforceConfig parsed = ReinforceConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            assertEquals(ReinforceConfig.DEFAULT, parsed);
            List<String> warnings = new ArrayList<>();
            assertEquals(ReinforceConfig.DEFAULT, parsed.sanitized(warnings));
            assertTrue(warnings.isEmpty(), warnings.toString());
        }
    }

    @Test
    void defaultValues() {
        ReinforceConfig d = ReinforceConfig.DEFAULT;
        assertEquals(20, d.maxLevel());
        assertEquals(List.of(100.0, 100.0, 100.0, 90.0, 85.0, 80.0, 75.0, 70.0, 65.0, 60.0,
                55.0, 50.0, 45.0, 40.0, 35.0, 30.0, 25.0, 20.0, 15.0, 10.0), d.successPercent());
        assertEquals(Collections.nCopies(20, 1), d.stoneCost());
        assertEquals(10, d.gunDamagePercentPerLevel());
        assertEquals(10, d.meleeDamagePercentPerLevel());
        assertEquals(2, d.shieldSpeedPercentPerLevel());
        assertEquals(1.5, d.armorHealthPerLevel());
        assertEquals(60, d.armorHealthMax());
    }

    @Test
    void emptyObjectIsDefault() {
        assertEquals(ReinforceConfig.DEFAULT, parse("{}"));
    }

    @Test
    void defaultRoundTripsThroughCodecAndStreamCodec() {
        var encoded = ReinforceConfig.CODEC.encodeStart(JsonOps.INSTANCE, ReinforceConfig.DEFAULT).getOrThrow();
        assertEquals(ReinforceConfig.DEFAULT, ReinforceConfig.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        var buf = Unpooled.buffer();
        ReinforceConfig.STREAM_CODEC.encode(buf, ReinforceConfig.DEFAULT);
        assertEquals(ReinforceConfig.DEFAULT, ReinforceConfig.STREAM_CODEC.decode(buf));
    }

    @Test
    void shortListsArePaddedWithLastValue() {
        ReinforceConfig c = parse("{\"maxLevel\":4,\"successPercent\":[100,50],\"stoneCost\":[1,2,3]}");
        List<String> warnings = new ArrayList<>();
        ReinforceConfig s = c.sanitized(warnings);
        assertEquals(List.of(100.0, 50.0, 50.0, 50.0), s.successPercent());
        assertEquals(List.of(1, 2, 3, 3), s.stoneCost());
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("successPercent"));
        assertTrue(warnings.get(1).contains("stoneCost"));
    }

    @Test
    void longListsAreCut() {
        ReinforceConfig c = parse("{\"maxLevel\":2}");
        List<String> warnings = new ArrayList<>();
        ReinforceConfig s = c.sanitized(warnings);
        assertEquals(List.of(100.0, 100.0), s.successPercent());
        assertEquals(List.of(1, 1), s.stoneCost());
        assertEquals(2, warnings.size(), warnings.toString());
    }

    @Test
    void emptyListsTakeDefaultsAndDefaultLastPastTheEnd() {
        ReinforceConfig c = parse("{\"maxLevel\":22,\"successPercent\":[],\"stoneCost\":[]}");
        List<String> warnings = new ArrayList<>();
        ReinforceConfig s = c.sanitized(warnings);
        assertEquals(22, s.successPercent().size());
        assertEquals(100.0, s.successPercent().get(0));
        assertEquals(10.0, s.successPercent().get(19));
        assertEquals(10.0, s.successPercent().get(21));
        assertEquals(Collections.nCopies(22, 1), s.stoneCost());
        assertEquals(2, warnings.size(), warnings.toString());
    }

    @Test
    void maxLevelOutOfRangeFallsBackTo20() {
        for (String bad : List.of("0", "51", "-3")) {
            List<String> warnings = new ArrayList<>();
            ReinforceConfig s = parse("{\"maxLevel\":" + bad + "}").sanitized(warnings);
            assertEquals(20, s.maxLevel());
            assertEquals(ReinforceConfig.DEFAULT, s);
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains("maxLevel"));
        }
    }

    @Test
    void maxLevelBoundsAccepted() {
        List<String> warnings = new ArrayList<>();
        ReinforceConfig s = parse("{\"maxLevel\":1,\"successPercent\":[100],\"stoneCost\":[1]}").sanitized(warnings);
        assertEquals(1, s.maxLevel());
        assertTrue(warnings.isEmpty(), warnings.toString());
        ReinforceConfig fifty = parse("{\"maxLevel\":50}").sanitized(warnings);
        assertEquals(50, fifty.maxLevel());
        assertEquals(50, fifty.successPercent().size());
        assertEquals(10.0, fifty.successPercent().get(49));
    }

    @Test
    void valuesAreClamped() {
        ReinforceConfig c = parse("{\"maxLevel\":3,\"successPercent\":[150,-5,40],\"stoneCost\":[0,100,5]}");
        List<String> warnings = new ArrayList<>();
        ReinforceConfig s = c.sanitized(warnings);
        assertEquals(List.of(100.0, 0.0, 40.0), s.successPercent());
        assertEquals(List.of(1, 64, 5), s.stoneCost());
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("successPercent"));
        assertTrue(warnings.get(1).contains("stoneCost"));
    }

    @Test
    void negativeScalarsTakeDefaults() {
        ReinforceConfig c = parse("{\"gunDamagePercentPerLevel\":-1,\"meleeDamagePercentPerLevel\":-2,"
                + "\"shieldSpeedPercentPerLevel\":-3,\"armorHealthPerLevel\":-4,\"armorHealthMax\":-5}");
        List<String> warnings = new ArrayList<>();
        ReinforceConfig s = c.sanitized(warnings);
        assertEquals(ReinforceConfig.DEFAULT, s);
        assertEquals(5, warnings.size(), warnings.toString());
    }

    @Test
    void zeroScalarsAreKept() {
        ReinforceConfig c = parse("{\"gunDamagePercentPerLevel\":0,\"armorHealthMax\":0}");
        List<String> warnings = new ArrayList<>();
        ReinforceConfig s = c.sanitized(warnings);
        assertEquals(0, s.gunDamagePercentPerLevel());
        assertEquals(0, s.armorHealthMax());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }
}
