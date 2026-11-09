package org.gettserver.gettcore.upgrade;

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
import org.gettserver.gettcore.upgrade.UpgradeConfig.Step;
import org.junit.jupiter.api.Test;

/** upgrade.json 설정(단계 찾기·검증·정리)과 업그레이드 순수 규칙. */
class UpgradeTest {
    private static Step step(String from, String to) {
        return new Step(from, to, "minecraft:iron_ingot", 3, 0);
    }

    private static UpgradeConfig config(Step... steps) {
        return new UpgradeConfig(List.of(steps));
    }

    @Test
    void codecDefaultsCountAndGold() {
        UpgradeConfig c = UpgradeConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"steps\":[{\"from\":\"a:x\",\"to\":\"a:y\",\"material\":\"minecraft:diamond\"}]}")).getOrThrow();
        assertEquals(new Step("a:x", "a:y", "minecraft:diamond", 1, 0), c.steps().get(0));
        assertEquals(UpgradeConfig.DEFAULT, UpgradeConfig.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{}")).getOrThrow());
        assertEquals(c, UpgradeConfig.fromJsonString(UpgradeConfig.toJsonString(c)));
    }

    @Test
    void findsStepByFrom() {
        UpgradeConfig c = config(step("a:wood", "a:stone"), step("a:stone", "a:iron"));
        assertEquals("a:iron", c.step("a:stone").orElseThrow().to());
        assertTrue(c.step("a:iron").isEmpty());
        assertTrue(c.involves("a:iron"));
        assertTrue(c.involves("a:wood"));
        assertFalse(c.involves("minecraft:stick"));
    }

    @Test
    void validChainHasNoErrors() {
        UpgradeConfig c = config(step("a:wood", "a:stone"), step("a:stone", "a:iron"), step("b:wood", "a:iron"));
        assertEquals(List.of(), c.errors());
        List<String> w = new ArrayList<>();
        assertSame(c, c.sanitized(w));
        assertEquals(List.of(), w);
    }

    @Test
    void rejectsBadIdsSameItemAndDuplicateFrom() {
        UpgradeConfig c = config(new Step("nocolon", "a:y", "minecraft:diamond", 1, 0),
                step("a:x", "a:x"), step("a:y", "a:z"), step("a:y", "a:w"));
        List<String> e = c.errors();
        assertEquals(3, e.size(), e.toString());
        assertTrue(e.get(0).contains("from 'nocolon' is not an item id"));
        assertTrue(e.get(1).contains("same item"));
        assertTrue(e.get(2).contains("steps[3]") && e.get(2).contains("already starts"));
        List<String> w = new ArrayList<>();
        assertEquals(config(step("a:y", "a:z")), c.sanitized(w));
        assertEquals(3, w.size());
        assertTrue(w.get(0).startsWith("upgrade.json: "));
    }

    @Test
    void rejectsCycles() {
        UpgradeConfig c = config(step("a:1", "a:2"), step("a:2", "a:3"), step("a:3", "a:1"), step("a:4", "a:1"));
        List<String> e = c.errors();
        assertEquals(1, e.size(), e.toString());
        assertTrue(e.get(0).contains("steps[2]") && e.get(0).contains("cycle"));
        assertEquals(config(step("a:1", "a:2"), step("a:2", "a:3"), step("a:4", "a:1")),
                c.sanitized(new ArrayList<>()));
    }

    @Test
    void clampsCountAndGold() {
        UpgradeConfig c = config(new Step("a:x", "a:y", "minecraft:diamond", 0, -5));
        assertEquals(2, c.errors().size());
        assertEquals(config(new Step("a:x", "a:y", "minecraft:diamond", 1, 0)), c.sanitized(new ArrayList<>()));
    }

    @Test
    void reachesFollowsChainAndStopsOnLoops() {
        Map<String, String> next = Map.of("a", "b", "b", "c", "x", "y", "y", "x");
        assertTrue(UpgradeRules.reaches(next, "a", "c"));
        assertFalse(UpgradeRules.reaches(next, "c", "a"));
        assertFalse(UpgradeRules.reaches(next, "x", "a"));
    }

    @Test
    void costCheck() {
        Optional<Step> s = Optional.of(new Step("a:x", "a:y", "minecraft:diamond", 3, 100));
        assertTrue(UpgradeRules.canUpgrade(s, 3, 100));
        assertFalse(UpgradeRules.canUpgrade(s, 2, 100));
        assertFalse(UpgradeRules.canUpgrade(s, 3, 99));
        assertFalse(UpgradeRules.canUpgrade(Optional.empty(), 99, 99));
        assertTrue(UpgradeRules.canUpgrade(Optional.of(new Step("a:x", "a:y", "minecraft:diamond", 1, 0)), 1, 0));
    }

    @Test
    void damageKeepsRatio() {
        assertEquals(0, UpgradeRules.damageAfter(0, 250, 1561));
        assertEquals(781, UpgradeRules.damageAfter(125, 250, 1561)); // half worn stays half worn
        assertEquals(1560, UpgradeRules.damageAfter(250, 250, 1561)); // never breaks on upgrade
        assertEquals(1, UpgradeRules.damageAfter(1, 1561, 2031));
        assertEquals(0, UpgradeRules.damageAfter(10, 0, 100)); // old had no durability
        assertEquals(0, UpgradeRules.damageAfter(10, 100, 0)); // new has none
    }

    @Test
    void bundledDefaultIsClean() throws Exception {
        try (InputStream in = UpgradeTest.class.getResourceAsStream("/gettcore_defaults/upgrade.json")) {
            assertNotNull(in);
            UpgradeConfig c = UpgradeConfig.CODEC.parse(JsonOps.INSTANCE,
                    JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))).getOrThrow();
            List<String> w = new ArrayList<>();
            assertSame(c, c.sanitized(w));
            assertEquals(List.of(), w);
            assertEquals(136, c.steps().size()); // 29 Epic Knights + 5 Epic Fight families x 4 steps
            Step last = c.step("magistuarmory:diamond_buckler").orElseThrow();
            assertEquals(new Step("magistuarmory:diamond_buckler", "magistuarmory:netherite_buckler",
                    "minecraft:netherite_ingot", 1, 0), last);
            assertTrue(c.step("magistuarmory:netherite_buckler").isEmpty());
            assertEquals("minecraft:cobblestone", c.step("magistuarmory:wood_claymore").orElseThrow().material());
        }
    }
}
