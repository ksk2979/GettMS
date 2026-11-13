package org.gettserver.gettcore.detector;

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
import org.junit.jupiter.api.Test;

class DetectorConfigTest {

    private static DetectorConfig parse(String json) {
        return DetectorConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    private static DetectorConfig.Target target(String id, List<String> items, List<String> blocks) {
        return new DetectorConfig.Target(id, items, blocks, true);
    }

    private static DetectorConfig withTargets(DetectorConfig.Target... targets) {
        DetectorConfig d = DetectorConfig.DEFAULT;
        return new DetectorConfig(d.range(), d.durationSeconds(), d.cooldownSeconds(), d.maxBlocks(),
                d.selfBlockTags(), List.of(targets));
    }

    @Test
    void defaultsMatchSpec() {
        DetectorConfig d = DetectorConfig.DEFAULT;
        assertEquals(24, d.range());
        assertEquals(60, d.durationSeconds());
        assertEquals(300, d.cooldownSeconds());
        assertEquals(4096, d.maxBlocks());
        assertEquals(List.of("minecraft:small_flowers", "minecraft:tall_flowers"), d.selfBlockTags());
        assertEquals(11, d.targets().size());
        assertEquals(List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore"), d.targets().get(0).blocks());
        assertTrue(d.targets().stream().allMatch(DetectorConfig.Target::ore));
        assertTrue(d.errors().isEmpty(), d.errors().toString());
        assertEquals(d, d.sanitized(new ArrayList<>()));
    }

    @Test
    void emptyJsonGivesDefaults() {
        assertEquals(DetectorConfig.DEFAULT, parse("{}"));
    }

    @Test
    void bundledFileEqualsDefault() throws Exception {
        try (InputStream in = DetectorConfigTest.class.getResourceAsStream("/gettcore_defaults/detector.json")) {
            assertNotNull(in, "bundled detector.json");
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertEquals(DetectorConfig.DEFAULT, DetectorConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
            assertEquals(json, DetectorConfig.CODEC.encodeStart(JsonOps.INSTANCE, DetectorConfig.DEFAULT).getOrThrow());
        }
    }

    @Test
    void sanitizeReplacesOutOfRange() {
        List<String> warnings = new ArrayList<>();
        DetectorConfig s = parse("{\"range\":500,\"durationSeconds\":0,\"maxBlocks\":-1}").sanitized(warnings);
        assertEquals(24, s.range());
        assertEquals(60, s.durationSeconds());
        assertEquals(4096, s.maxBlocks());
        assertEquals(300, s.cooldownSeconds());
        assertEquals(3, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().allMatch(w -> w.startsWith("detector.json: ")), warnings.toString());

        List<String> edge = new ArrayList<>();
        DetectorConfig ok = parse("{\"range\":64,\"durationSeconds\":3600,\"cooldownSeconds\":0,\"maxBlocks\":16384}")
                .sanitized(edge);
        assertEquals(64, ok.range());
        assertEquals(0, ok.cooldownSeconds());
        assertTrue(edge.isEmpty(), edge.toString());
    }

    @Test
    void errorsFlagDuplicatesAndEmpties() {
        var coal = target("coal", List.of("minecraft:coal"), List.of("minecraft:coal_ore"));
        var sameId = target("coal", List.of("minecraft:coal_ore"), List.of("minecraft:coal_ore"));
        List<String> dup = withTargets(coal, sameId).errors();
        assertEquals(1, dup.size(), dup.toString());
        assertTrue(dup.get(0).startsWith("targets[1] coal: "), dup.toString());

        List<String> noBlocks = withTargets(target("x", List.of("minecraft:coal"), List.of())).errors();
        assertEquals(1, noBlocks.size(), noBlocks.toString());
        assertTrue(noBlocks.get(0).startsWith("targets[0] x: "), noBlocks.toString());

        var other = target("other", List.of("minecraft:coal"), List.of("minecraft:coal_ore"));
        List<String> sharedItem = withTargets(coal, other).errors();
        assertEquals(1, sharedItem.size(), sharedItem.toString());
        assertTrue(sharedItem.get(0).startsWith("targets[1] other: "), sharedItem.toString());
    }

    @Test
    void errorsFlagEmptyIdAndBadResourceId() {
        assertEquals(1, withTargets(target("", List.of("minecraft:coal"), List.of("minecraft:coal_ore")))
                .errors().size());
        List<String> bad = withTargets(target("x", List.of("Not Valid"), List.of("minecraft:coal_ore"))).errors();
        assertEquals(1, bad.size(), bad.toString());
    }

    @Test
    void streamCodecRoundTrips() {
        DetectorConfig custom = new DetectorConfig(10, 30, 0, 100, List.of("minecraft:flowers"),
                List.of(new DetectorConfig.Target("a", List.of("a:x", "a:y"), List.of("a:z"), false)));
        for (DetectorConfig c : List.of(DetectorConfig.DEFAULT, custom)) {
            ByteBuf buf = Unpooled.buffer();
            DetectorConfig.STREAM_CODEC.encode(buf, c);
            assertEquals(c, DetectorConfig.STREAM_CODEC.decode(buf));
        }
    }
}
