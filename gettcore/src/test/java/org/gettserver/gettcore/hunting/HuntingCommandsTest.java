package org.gettserver.gettcore.hunting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class HuntingCommandsTest {
    @Test
    void newZoneUsesSelectionAsIsWithDefaults() {
        HuntingConfig.Zone z = HuntingCommands.newZone("숲", Level.OVERWORLD, new BlockPos(10, 70, -5),
                new BlockPos(-10, 60, 5));
        assertEquals("숲", z.id());
        // No Y padding (unlike protect regions), only min/max ordered.
        assertEquals(new BlockPos(-10, 60, -5), z.min());
        assertEquals(new BlockPos(10, 70, 5), z.max());
        assertEquals(HuntingConfig.DEFAULT_MAX_ALIVE, z.maxAlive());
        assertEquals(HuntingConfig.DEFAULT_REFILL_SECONDS, z.refillSeconds());
        assertTrue(z.entries().isEmpty());
    }

    @Test
    void entryNamesForSuggestions() {
        HuntingConfig.Entry a = new HuntingConfig.Entry("큰 좀비", ResourceLocation.parse("minecraft:zombie"), 80, 6,
                0.3, 2, 1.4, 2, 30, 1.5);
        HuntingConfig cfg = new HuntingConfig(List.of(new HuntingConfig.Zone("숲", Level.OVERWORLD, BlockPos.ZERO,
                BlockPos.ZERO, 10, 10, List.of(a))));
        assertEquals(List.of("큰 좀비"), HuntingCommands.entryNames(cfg, "숲"));
        assertEquals(List.of(), HuntingCommands.entryNames(cfg, "없음"));
    }
}
