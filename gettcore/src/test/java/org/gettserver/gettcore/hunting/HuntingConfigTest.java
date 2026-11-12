package org.gettserver.gettcore.hunting;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.gettserver.gettcore.hunting.HuntingConfig.Entry;
import org.gettserver.gettcore.hunting.HuntingConfig.Zone;
import org.junit.jupiter.api.Test;

class HuntingConfigTest {
    private static HuntingConfig parse(String json) {
        return HuntingConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    static Entry entry(String name, int weight) {
        return new Entry(name, ResourceLocation.parse("minecraft:zombie"), 100, 8, 0.3, 4, 1.5, weight, 25, 2.0);
    }

    static Zone zone(String id, List<Entry> entries) {
        return new Zone(id, Level.OVERWORLD, new BlockPos(0, 60, 0), new BlockPos(20, 80, 20), 10, 10, entries);
    }

    @Test
    void parsesDefaultsFile() throws Exception {
        try (InputStream in = HuntingConfigTest.class.getResourceAsStream("/gettcore_defaults/hunting.json")) {
            assertNotNull(in, "bundled hunting.json");
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            HuntingConfig parsed = HuntingConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            assertEquals(HuntingConfig.DEFAULT, parsed);
            List<String> warnings = new ArrayList<>();
            assertEquals(HuntingConfig.DEFAULT, parsed.sanitized(warnings));
            assertTrue(warnings.isEmpty(), warnings.toString());
        }
    }

    @Test
    void emptyObjectAndFieldDefaults() {
        assertEquals(HuntingConfig.DEFAULT, parse("{}"));
        Zone z = parse("{\"zones\":[{\"id\":\"숲\",\"dimension\":\"minecraft:overworld\",\"min\":[0,0,0],"
                + "\"max\":[1,1,1],\"entries\":[{\"name\":\"큰 좀비\",\"entity\":\"minecraft:zombie\"}]}]}")
                .zones().get(0);
        assertEquals("숲", z.id());
        assertEquals(10, z.maxAlive());
        assertEquals(10, z.refillSeconds());
        Entry e = z.entries().get(0);
        assertEquals(1.0, e.scale());
        assertEquals(1, e.weight());
        assertEquals(0L, e.gold());
        assertEquals(1.0, e.xpMultiplier());
    }

    @Test
    void codecRoundTrip() {
        HuntingConfig c = new HuntingConfig(List.of(zone("숲", List.of(entry("큰 좀비", 3), entry("빠른 좀비", 1)))));
        JsonElement json = HuntingConfig.CODEC.encodeStart(JsonOps.INSTANCE, c).getOrThrow();
        var z = json.getAsJsonObject().getAsJsonArray("zones").get(0).getAsJsonObject();
        assertEquals("[0,60,0]", z.get("min").toString());
        assertEquals("minecraft:overworld", z.get("dimension").getAsString());
        assertEquals(c, HuntingConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
    }

    @Test
    void sanitizeFixesRangesAndDuplicates() {
        Entry bad = new Entry("나쁜", ResourceLocation.parse("minecraft:zombie"), -5, -1, -0.2, 99, 100, -3, -10, -1);
        Zone flipped = new Zone("a", Level.OVERWORLD, new BlockPos(10, 80, 10), new BlockPos(0, 60, 0), -1, 0,
                List.of(bad, entry("나쁜", 1), entry(" ", 1)));
        HuntingConfig c = new HuntingConfig(List.of(flipped, zone("a", List.of()), zone("b", List.of())));
        List<String> warnings = new ArrayList<>();
        HuntingConfig s = c.sanitized(warnings);
        assertEquals(2, s.zones().size());
        Zone a = s.zones().get(0);
        assertEquals(new BlockPos(0, 60, 0), a.min());
        assertEquals(new BlockPos(10, 80, 10), a.max());
        assertEquals(0, a.maxAlive());
        assertEquals(1, a.refillSeconds());
        assertEquals(1, a.entries().size(), "duplicate and blank names dropped");
        Entry e = a.entries().get(0);
        assertEquals(HuntingConfig.MIN_HEALTH, e.health());
        assertEquals(0, e.attack());
        assertEquals(0, e.speed());
        assertEquals(HuntingConfig.MAX_ARMOR, e.armor());
        assertEquals(HuntingConfig.MAX_SCALE, e.scale());
        assertEquals(0, e.weight());
        assertEquals(0, e.gold());
        assertEquals(0, e.xpMultiplier());
        assertTrue(warnings.stream().allMatch(w -> w.startsWith("hunting.json: ")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("duplicate zone")), warnings.toString());

        List<String> none = new ArrayList<>();
        assertEquals(s, s.sanitized(none));
        assertTrue(none.isEmpty(), none.toString());
    }

    @Test
    void zoneLookupAndReplace() {
        HuntingConfig c = new HuntingConfig(List.of(zone("a", List.of(entry("x", 1)))));
        assertTrue(c.zone("a").isPresent());
        assertTrue(c.zone("b").isEmpty());
        assertEquals("x", c.zone("a").orElseThrow().entry("x").orElseThrow().name());
        assertTrue(c.zone("a").orElseThrow().entry("y").isEmpty());
        HuntingConfig added = c.withZone(zone("b", List.of()));
        assertEquals(2, added.zones().size());
        assertEquals(1, added.without("a").zones().size());
        assertEquals("b", added.without("a").zones().get(0).id());
    }

    private static org.gettserver.gettcore.skill.SkillSpec skill(String id, String type) {
        return new org.gettserver.gettcore.skill.SkillSpec(id, type, true, false, "", 10, 1, 0, 20, 1, 10,
                new org.gettserver.gettcore.skill.SkillSpec.Shape(10, 2, 6),
                new org.gettserver.gettcore.skill.SkillSpec.Aftershock(0, 0, 0),
                new org.gettserver.gettcore.skill.SkillSpec.Terrain(false, 0, 0));
    }

    private static Entry withSkills(String name, List<org.gettserver.gettcore.skill.SkillSpec> skills) {
        Entry e = entry(name, 1);
        return new Entry(e.name(), e.entity(), e.health(), e.attack(), e.speed(), e.armor(), e.scale(), e.weight(),
                e.gold(), e.xpMultiplier(), skills);
    }

    @Test
    void entryWithoutSkillsDecodesToEmptyList() {
        Zone z = parse("{\"zones\":[{\"id\":\"숲\",\"dimension\":\"minecraft:overworld\",\"min\":[0,0,0],"
                + "\"max\":[5,5,5],\"entries\":[{\"name\":\"a\",\"entity\":\"minecraft:zombie\"}]}]}").zones().get(0);
        assertEquals(List.of(), z.entries().get(0).skills());
    }

    @Test
    void entrySkillsRoundTrip() {
        HuntingConfig c = new HuntingConfig(List.of(zone("숲", List.of(withSkills("a", List.of(skill("s1", "stomp")))))));
        JsonElement json = HuntingConfig.CODEC.encodeStart(JsonOps.INSTANCE, c).getOrThrow();
        assertEquals(c, HuntingConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
    }

    @Test
    void entrySkillsAreSanitized() {
        HuntingConfig c = new HuntingConfig(List.of(zone("숲", List.of(
                withSkills("a", List.of(skill("s1", "stomp"), skill("s2", "nope"), skill("s1", "wind_line")))))));
        List<String> warnings = new ArrayList<>();
        Entry e = c.sanitized(warnings).zones().get(0).entries().get(0);
        assertEquals(1, e.skills().size());
        assertEquals("s1", e.skills().get(0).id());
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("hunting.json: ") && w.contains("zone 숲 entry a")
                && w.contains("s2") && w.contains("unknown type")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("duplicate id")), warnings.toString());
    }
}
