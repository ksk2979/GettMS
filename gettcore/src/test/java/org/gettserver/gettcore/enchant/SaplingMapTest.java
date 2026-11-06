package org.gettserver.gettcore.enchant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.enchant.effects.AxeEffects.SaplingMap;
import org.junit.jupiter.api.Test;

class SaplingMapTest {
    private static Optional<ResourceLocation> of(String id) {
        return SaplingMap.saplingFor(ResourceLocation.parse(id));
    }

    @Test
    void vanillaLogsMapToSaplings() {
        for (String wood : new String[] {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "cherry"}) {
            assertEquals(Optional.of(ResourceLocation.withDefaultNamespace(wood + "_sapling")),
                    of("minecraft:" + wood + "_log"));
        }
    }

    @Test
    void mangroveMapsToPropagule() {
        assertEquals(Optional.of(ResourceLocation.withDefaultNamespace("mangrove_propagule")),
                of("minecraft:mangrove_log"));
    }

    @Test
    void othersAreEmpty() {
        assertEquals(Optional.empty(), of("minecraft:stripped_oak_log"));
        assertEquals(Optional.empty(), of("minecraft:bamboo_block"));
        assertEquals(Optional.empty(), of("minecraft:oak_wood"));
        assertEquals(Optional.empty(), of("biomesoplenty:fir_log"));
    }
}
