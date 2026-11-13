package org.gettserver.gettcore.pet.boss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.pet.PetSpecies;
import org.junit.jupiter.api.Test;

class BossBehaviorTest {
    @Test
    void bossBarFollowsDistanceAndLife() {
        assertEquals(48.0, BossBehavior.barRange(40));
        assertEquals(32.0, BossBehavior.barRange(0));
        assertTrue(BossBehavior.showsBar(true, true, 48, 48));
        assertFalse(BossBehavior.showsBar(true, true, 48.1, 48));
        assertFalse(BossBehavior.showsBar(false, true, 5, 48), "a dead player loses the bar at once");
        assertFalse(BossBehavior.showsBar(true, false, 5, 48));
    }

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
        assertEquals(6.0, ground.attack(), "bosses keep base attack 6 (times attackMult)");
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

    @Test
    void bossOfARemovedPointIsOrphaned() {
        BossPoint den = new BossPoint("den", net.minecraft.world.level.Level.OVERWORLD,
                new net.minecraft.core.BlockPos(0, 64, 0), 16, ResourceLocation.parse("gettcore:boss_lion"), 1, 30);
        assertFalse(BossBehavior.isOrphan("", java.util.List.of()), "no point (e.g. /summon) is never orphaned");
        assertFalse(BossBehavior.isOrphan("den", java.util.List.of(den)));
        assertTrue(BossBehavior.isOrphan("den", java.util.List.of()));
        assertTrue(BossBehavior.isOrphan("cave", java.util.List.of(den)));
    }
}
