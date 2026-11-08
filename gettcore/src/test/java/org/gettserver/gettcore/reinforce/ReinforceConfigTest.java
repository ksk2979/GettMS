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
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.reinforce.ReinforceConfig.Milestone;
import org.gettserver.gettcore.reinforce.ReinforceRules.Target;
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
        assertEquals(30, d.maxLevel());
        assertEquals(List.of(100.0, 100.0, 100.0, 90.0, 85.0, 80.0, 75.0, 70.0, 65.0, 60.0,
                55.0, 50.0, 45.0, 40.0, 35.0, 30.0, 25.0, 20.0, 15.0, 10.0,
                10.0, 9.0, 9.0, 8.0, 8.0, 7.0, 7.0, 6.0, 6.0, 5.0), d.successPercent());
        assertEquals(Collections.nCopies(30, 1), d.stoneCost());
        assertEquals(10, d.gunDamagePercentPerLevel());
        assertEquals(10, d.meleeDamagePercentPerLevel());
        assertEquals(1.7, d.shieldSpeedPercentPerLevel());
        assertTrue(d.milestones().isEmpty());
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
        ReinforceConfig c = parse("{\"maxLevel\":32,\"successPercent\":[],\"stoneCost\":[]}");
        List<String> warnings = new ArrayList<>();
        ReinforceConfig s = c.sanitized(warnings);
        assertEquals(32, s.successPercent().size());
        assertEquals(100.0, s.successPercent().get(0));
        assertEquals(9.0, s.successPercent().get(21));
        assertEquals(5.0, s.successPercent().get(29));
        assertEquals(5.0, s.successPercent().get(31));
        assertEquals(Collections.nCopies(32, 1), s.stoneCost());
        assertEquals(2, warnings.size(), warnings.toString());
    }

    @Test
    void maxLevelOutOfRangeFallsBackTo30() {
        for (String bad : List.of("0", "51", "-3")) {
            List<String> warnings = new ArrayList<>();
            ReinforceConfig s = parse("{\"maxLevel\":" + bad + "}").sanitized(warnings);
            assertEquals(30, s.maxLevel());
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
        assertEquals(5.0, fifty.successPercent().get(49));
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
    void thirtyLevelTableParsesToDefault() {
        ReinforceConfig c = parse("{\"maxLevel\":30,\"successPercent\":[100,100,100,90,85,80,75,70,65,60,55,50,45,40,"
                + "35,30,25,20,15,10,10,9,9,8,8,7,7,6,6,5],\"stoneCost\":[1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,"
                + "1,1,1,1,1,1],\"shieldSpeedPercentPerLevel\":1.7,\"milestones\":[]}");
        assertEquals(ReinforceConfig.DEFAULT, c);
        assertEquals(30, c.successPercent().size());
        assertEquals(5.0, c.successPercent().get(29));
    }

    @Test
    void milestonesParseAndRoundTrip() {
        ReinforceConfig c = parse("{\"milestones\":[{\"target\":\"armor\",\"level\":10,"
                + "\"effect\":\"minecraft:speed\",\"amplifier\":1}]}");
        Milestone m = new Milestone(Target.ARMOR, 10, ResourceLocation.parse("minecraft:speed"), 1);
        assertEquals(List.of(m), c.milestones());
        List<String> warnings = new ArrayList<>();
        assertEquals(c, c.sanitized(warnings));
        assertTrue(warnings.isEmpty(), warnings.toString());
        var json = ReinforceConfig.CODEC.encodeStart(JsonOps.INSTANCE, c).getOrThrow();
        assertEquals("[{\"target\":\"armor\",\"level\":10,\"effect\":\"minecraft:speed\",\"amplifier\":1}]",
                json.getAsJsonObject().get("milestones").toString());
        assertEquals(c, ReinforceConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        var buf = Unpooled.buffer();
        ReinforceConfig.STREAM_CODEC.encode(buf, c);
        assertEquals(c, ReinforceConfig.STREAM_CODEC.decode(buf));
    }

    @Test
    void badMilestonesAreDroppedWithWarnings() {
        ReinforceConfig c = parse("{\"maxLevel\":10,\"milestones\":["
                + "{\"target\":\"gun\",\"level\":10,\"effect\":\"minecraft:speed\",\"amplifier\":9},"
                + "{\"target\":\"bow\",\"level\":5,\"effect\":\"minecraft:speed\"},"
                + "{\"target\":\"melee\",\"level\":0,\"effect\":\"minecraft:speed\"},"
                + "{\"target\":\"melee\",\"level\":11,\"effect\":\"minecraft:speed\"},"
                + "{\"target\":\"shield\",\"level\":1,\"effect\":\"minecraft:speed\",\"amplifier\":10},"
                + "{\"target\":\"shield\",\"level\":1,\"effect\":\"minecraft:speed\",\"amplifier\":-1},"
                + "{\"target\":\"armor\",\"level\":1,\"effect\":\"Not An Id\"},"
                + "{\"target\":\"armor\",\"level\":1,\"effect\":\"minecraft:nope\"},"
                + "{\"target\":\"armor\",\"level\":1,\"effect\":\"minecraft:strength\"}]}");
        var saved = ReinforceConfig.effectUsable;
        ReinforceConfig.effectUsable = id -> !id.getPath().equals("nope");
        try {
            List<String> all = new ArrayList<>();
            ReinforceConfig s = c.sanitized(all);
            // the two other warnings are the 30-entry default lists being cut to maxLevel 10
            List<String> warnings = all.stream().filter(w -> w.contains("milestones")).toList();
            assertEquals(9, all.size(), all.toString());
            assertEquals(List.of(
                    new Milestone(Target.GUN, 10, ResourceLocation.parse("minecraft:speed"), 9),
                    new Milestone(Target.ARMOR, 1, ResourceLocation.parse("minecraft:strength"), 0)), s.milestones());
            assertEquals(7, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains("milestones[1] target"), warnings.toString());
            assertTrue(warnings.get(1).contains("milestones[2] level 0"), warnings.toString());
            assertTrue(warnings.get(2).contains("milestones[3] level 11 must be within [1, 10]"), warnings.toString());
            assertTrue(warnings.get(3).contains("milestones[4] amplifier 10"), warnings.toString());
            assertTrue(warnings.get(4).contains("milestones[5] amplifier -1"), warnings.toString());
            assertTrue(warnings.get(5).contains("milestones[6] effect id is invalid"), warnings.toString());
            assertTrue(warnings.get(6).contains("milestones[7] effect minecraft:nope is unknown or instant"), warnings.toString());
            assertTrue(warnings.stream().allMatch(w -> w.startsWith("reinforce.json: ") && w.endsWith(", removed")));
        } finally {
            ReinforceConfig.effectUsable = saved;
        }
    }

    @Test
    void instantEffectsAreDroppedViaTheUsableHook() {
        ReinforceConfig c = parse("{\"milestones\":["
                + "{\"target\":\"armor\",\"level\":5,\"effect\":\"minecraft:instant_health\"},"
                + "{\"target\":\"armor\",\"level\":5,\"effect\":\"minecraft:regeneration\"}]}");
        var saved = ReinforceConfig.effectUsable;
        ReinforceConfig.effectUsable = id -> !id.getPath().startsWith("instant_") && !id.getPath().equals("saturation");
        try {
            List<String> warnings = new ArrayList<>();
            ReinforceConfig s = c.sanitized(warnings);
            assertEquals(List.of(new Milestone(Target.ARMOR, 5, ResourceLocation.parse("minecraft:regeneration"), 0)),
                    s.milestones());
            assertEquals(List.of("reinforce.json: milestones[0] effect minecraft:instant_health is unknown or instant "
                    + "(즉시 효과는 쓸 수 없습니다), removed"), warnings);
        } finally {
            ReinforceConfig.effectUsable = saved;
        }
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
