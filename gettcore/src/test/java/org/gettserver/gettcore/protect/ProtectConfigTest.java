package org.gettserver.gettcore.protect;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.gettserver.gettcore.protect.ProtectConfig.Flags;
import org.gettserver.gettcore.protect.ProtectConfig.Region;
import org.junit.jupiter.api.Test;

class ProtectConfigTest {
    private static ProtectConfig parse(String json) {
        return ProtectConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    @Test
    void emptyObjectGivesDefaults() {
        ProtectConfig c = parse("{}");
        assertEquals(ProtectConfig.DEFAULT, c);
        assertFalse(c.explosionsBreakBlocks());
        assertFalse(c.endermanPickup());
        assertTrue(c.regions().isEmpty());
        Region r = parse("{\"regions\":[{\"id\":\"town\",\"dimension\":\"minecraft:overworld\","
                + "\"min\":[0,0,0],\"max\":[1,1,1]}]}").regions().get(0);
        assertEquals(Flags.DEFAULT, r.flags());
        assertFalse(r.flags().fireSpread());
        assertFalse(r.flags().bossGrief());
        assertFalse(r.flags().explosions());
    }

    @Test
    void codecRoundTrip() {
        ProtectConfig c = new ProtectConfig(true, true, List.of(
                new Region("town", Level.NETHER, new BlockPos(-5, -64, 3), new BlockPos(10, 320, 30),
                        new Flags(true, false, true))));
        JsonElement json = ProtectConfig.CODEC.encodeStart(JsonOps.INSTANCE, c).getOrThrow();
        assertTrue(json.toString().contains("minecraft:the_nether"), json.toString());
        assertEquals(c, ProtectConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
    }

    @Test
    void sanitizeSwapsMinMaxAndDropsDuplicateIds() {
        ProtectConfig c = new ProtectConfig(false, false, List.of(
                new Region("a", Level.OVERWORLD, new BlockPos(10, 5, -1), new BlockPos(0, 70, -9), Flags.DEFAULT),
                new Region("a", Level.OVERWORLD, new BlockPos(0, 0, 0), new BlockPos(1, 1, 1), Flags.DEFAULT),
                new Region("b", Level.OVERWORLD, new BlockPos(0, 0, 0), new BlockPos(1, 1, 1), Flags.DEFAULT)));
        List<String> warnings = new ArrayList<>();
        ProtectConfig s = c.sanitized(warnings);
        assertEquals(2, s.regions().size());
        Region a = s.regions().get(0);
        assertEquals(new BlockPos(0, 5, -9), a.min());
        assertEquals(new BlockPos(10, 70, -1), a.max());
        assertEquals("b", s.regions().get(1).id());
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("duplicate") && w.contains("a")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("min/max")), warnings.toString());

        List<String> none = new ArrayList<>();
        ProtectConfig clean = s.sanitized(none);
        assertEquals(s, clean);
        assertTrue(none.isEmpty());
    }

    @Test
    void containsIsInclusiveAndPerDimension() {
        Region r = new Region("t", Level.OVERWORLD, new BlockPos(0, 60, 0), new BlockPos(10, 80, 10), Flags.DEFAULT);
        assertTrue(r.contains(Level.OVERWORLD, new BlockPos(0, 60, 0)));
        assertTrue(r.contains(Level.OVERWORLD, new BlockPos(10, 80, 10)));
        assertTrue(r.contains(Level.OVERWORLD, new BlockPos(5, 70, 5)));
        assertFalse(r.contains(Level.OVERWORLD, new BlockPos(11, 70, 5)));
        assertFalse(r.contains(Level.OVERWORLD, new BlockPos(5, 59, 5)));
        assertFalse(r.contains(Level.OVERWORLD, new BlockPos(5, 70, -1)));
        assertFalse(r.contains(Level.NETHER, new BlockPos(5, 70, 5)));

        ProtectConfig c = new ProtectConfig(false, false, List.of(r));
        assertEquals("t", c.regionAt(Level.OVERWORLD, new BlockPos(1, 61, 1)).orElseThrow().id());
        assertTrue(c.regionAt(Level.OVERWORLD, new BlockPos(100, 61, 1)).isEmpty());
    }
}
