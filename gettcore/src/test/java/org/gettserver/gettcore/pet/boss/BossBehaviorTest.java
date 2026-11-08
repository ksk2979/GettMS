package org.gettserver.gettcore.pet.boss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.pet.PetSpecies;
import org.junit.jupiter.api.Test;

class BossBehaviorTest {
    @Test
    void leashAllowsTwiceTheRadius() {
        assertFalse(BossBehavior.outsideLeash(32, 0, 16));
        assertFalse(BossBehavior.outsideLeash(20, 20, 16));
        assertTrue(BossBehavior.outsideLeash(32.1, 0, 16));
        assertTrue(BossBehavior.outsideLeash(-25, 25, 16));
    }

    @Test
    void fallbackSpeciesUsesBossDefaults() {
        PetSpecies ground = BossBehavior.fallbackSpecies(ResourceLocation.parse("gettcore:boss_lion"), false);
        assertEquals(5.0, ground.healthMult());
        assertEquals(2.0, ground.attackMult());
        assertEquals(1.6, ground.sizeScale());
        assertTrue(ground.boss());
        assertEquals(2.5, BossBehavior.fallbackSpecies(ResourceLocation.parse("gettcore:boss_vulture"), true).sizeScale());
    }

    @Test
    void lootIsRolledThreeTimes() {
        int[] rolls = {0};
        BossBehavior.dropLoot(() -> rolls[0]++);
        assertEquals(3, rolls[0]);
    }
}
