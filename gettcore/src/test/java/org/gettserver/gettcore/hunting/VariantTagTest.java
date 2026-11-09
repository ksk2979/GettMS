package org.gettserver.gettcore.hunting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class VariantTagTest {
    @Test
    void roundTripThroughPersistentData() {
        CompoundTag data = new CompoundTag();
        assertTrue(VariantTag.read(data).isEmpty());
        HuntingConfig.Entry e = new HuntingConfig.Entry("큰 좀비", ResourceLocation.parse("minecraft:zombie"), 80, 6,
                0.3, 2, 1.4, 2, 30, 1.5);
        VariantTag tag = VariantTag.of("숲", e, true);
        tag.write(data);
        assertEquals(Optional.of(new VariantTag("숲", "큰 좀비", 30, 1.5, true)), VariantTag.read(data));
        // Reward values are frozen at spawn: later config changes do not touch existing variants.
        assertEquals(30, VariantTag.read(data).orElseThrow().gold());
    }

    @Test
    void keepOnLoad() {
        assertFalse(HuntingRules.keepOnLoad(false, false, 0, 10), "zone removed");
        assertFalse(HuntingRules.keepOnLoad(false, true, 0, 10), "zone removed (test spawn too)");
        assertTrue(HuntingRules.keepOnLoad(true, false, 9, 10));
        assertFalse(HuntingRules.keepOnLoad(true, false, 10, 10), "zone already full");
        assertTrue(HuntingRules.keepOnLoad(true, true, 50, 10), "test spawns are not counted");
    }
}
