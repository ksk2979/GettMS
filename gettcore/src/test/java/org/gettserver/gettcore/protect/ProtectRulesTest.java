package org.gettserver.gettcore.protect;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import org.gettserver.gettcore.protect.ProtectConfig.Flags;
import org.junit.jupiter.api.Test;

class ProtectRulesTest {
    private static final BlockPos IN = new BlockPos(1, 1, 1);
    private static final BlockPos OUT = new BlockPos(100, 1, 1);

    private static Function<BlockPos, Optional<Flags>> region(Flags flags) {
        return p -> p.equals(IN) ? Optional.of(flags) : Optional.empty();
    }

    @Test
    void outsideWithGlobalOffIsRemoved() {
        assertEquals(List.of(), ProtectRules.filterBlocks(List.of(OUT), false, false, region(Flags.DEFAULT)));
        assertEquals(List.of(OUT), ProtectRules.filterBlocks(List.of(OUT), false, true, region(Flags.DEFAULT)));
    }

    @Test
    void outsideBossSourceIsKept() {
        assertEquals(List.of(OUT), ProtectRules.filterBlocks(List.of(OUT), true, false, region(Flags.DEFAULT)));
    }

    @Test
    void insideBlockedExplosionsAreRemovedEvenIfGlobalOn() {
        assertEquals(List.of(OUT), ProtectRules.filterBlocks(List.of(IN, OUT), false, true, region(Flags.DEFAULT)));
        // a boss blast inside a region with bossGrief off is removed too, explosions flag does not matter
        assertEquals(List.of(OUT), ProtectRules.filterBlocks(List.of(IN, OUT), true, false,
                region(new Flags(false, false, true))));
    }

    @Test
    void insideBossGriefAllowedKeepsBossBlocks() {
        assertEquals(List.of(IN), ProtectRules.filterBlocks(List.of(IN), true, false,
                region(new Flags(false, true, false))));
        // a normal explosion inside a region that only allows boss grief stays blocked
        assertEquals(List.of(), ProtectRules.filterBlocks(List.of(IN), false, true,
                region(new Flags(false, true, false))));
    }

    @Test
    void shouldExtinguish() {
        assertTrue(ProtectRules.shouldExtinguish(Optional.of(Flags.DEFAULT), false));
        assertFalse(ProtectRules.shouldExtinguish(Optional.of(Flags.DEFAULT), true));
        assertFalse(ProtectRules.shouldExtinguish(Optional.of(new Flags(true, false, false)), false));
        assertFalse(ProtectRules.shouldExtinguish(Optional.empty(), false));
    }
}
