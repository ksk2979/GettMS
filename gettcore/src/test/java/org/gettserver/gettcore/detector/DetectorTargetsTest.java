package org.gettserver.gettcore.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.gettserver.gettcore.detector.DetectorTargets.Resolved;
import org.junit.jupiter.api.Test;

class DetectorTargetsTest {
    private static final DetectorConfig C = DetectorConfig.DEFAULT;

    @Test
    void itemInListResolves() {
        Resolved r = DetectorTargets.forItem(C, "minecraft:raw_iron", null, id -> false).orElseThrow();
        assertEquals("iron", r.key());
        assertEquals(Set.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"), r.blockIds());
        assertTrue(r.ore());
    }

    @Test
    void blockItemOfOreResolves() {
        Resolved r = DetectorTargets.forItem(C, "minecraft:deepslate_iron_ore", "minecraft:deepslate_iron_ore",
                id -> false).orElseThrow();
        assertEquals("iron", r.key());
    }

    @Test
    void flowerResolvesToItself() {
        Resolved r = DetectorTargets.forItem(C, "minecraft:poppy", "minecraft:poppy", id -> true).orElseThrow();
        assertEquals("minecraft:poppy", r.key());
        assertEquals(Set.of("minecraft:poppy"), r.blockIds());
        assertFalse(r.ore());
    }

    @Test
    void unknownItemIsEmpty() {
        assertEquals(Optional.empty(), DetectorTargets.forItem(C, "minecraft:chest", "minecraft:chest", id -> false));
        assertEquals(Optional.empty(),
                DetectorTargets.forItem(C, "minecraft:spawner", "minecraft:spawner", id -> false));
        assertEquals(Optional.empty(), DetectorTargets.forItem(C, "minecraft:stick", null, id -> false));
    }

    @Test
    void oreFallback() {
        Resolved r = DetectorTargets.forOreBlock(C, "create:zinc_ore", true).orElseThrow();
        assertEquals("create:zinc_ore", r.key());
        assertEquals(Set.of("create:zinc_ore"), r.blockIds());
        assertTrue(r.ore());
        assertEquals(Optional.empty(), DetectorTargets.forOreBlock(C, "minecraft:stone", false));
        assertEquals("iron", DetectorTargets.forOreBlock(C, "minecraft:iron_ore", false).orElseThrow().key());
    }

    @Test
    void flowerIsNotOreTarget() {
        assertEquals(Optional.empty(), DetectorTargets.forOreBlock(C, "minecraft:poppy", false));
    }
}
