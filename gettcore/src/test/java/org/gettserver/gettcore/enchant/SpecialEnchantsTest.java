package org.gettserver.gettcore.enchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.util.RandomSource;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;
import org.gettserver.gettcore.enchant.SpecialEnchants.LevelWeights;
import org.gettserver.gettcore.enchant.SpecialEnchants.Rolled;
import org.gettserver.gettcore.enchant.SpecialEnchants.Tool;
import org.junit.jupiter.api.Test;

class SpecialEnchantsTest {

    @Test
    void poolSizesMatchSpec() {
        assertEquals(3, SpecialEnchants.pool(Tool.HOE).size());
        assertEquals(3, SpecialEnchants.pool(Tool.PICKAXE).size());
        assertEquals(3, SpecialEnchants.pool(Tool.AXE).size());
        assertEquals(5, SpecialEnchants.pool(Tool.SWORD).size());
        assertEquals(3, SpecialEnchants.pool(Tool.BOW).size());
        assertEquals(3, SpecialEnchants.pool(Tool.FISHING_ROD).size());

        Map<Ability, Integer> memberships = new EnumMap<>(Ability.class);
        for (Tool tool : Tool.values()) {
            for (Ability a : SpecialEnchants.pool(tool)) {
                memberships.merge(a, 1, Integer::sum);
            }
        }
        assertEquals(20, Ability.values().length);
        for (Ability a : Ability.values()) {
            assertEquals(1, memberships.getOrDefault(a, 0), a + " must be in exactly one pool");
        }
    }

    @Test
    void rollRespectsMaxLevel() {
        RandomSource r = RandomSource.create(1);
        for (int i = 0; i < 1000; i++) {
            Rolled rolled = SpecialEnchants.roll(Tool.HOE, LevelWeights.DEFAULT, r);
            assertEquals(Tool.HOE, rolled.ability().tool());
            assertTrue(rolled.level() >= 1 && rolled.level() <= rolled.ability().maxLevel(), rolled.toString());
            if (rolled.ability() == Ability.REPLANT) {
                assertEquals(1, rolled.level());
            }
        }
    }

    @Test
    void rollWeightsRoughlyMatch() {
        RandomSource r = RandomSource.create(42);
        int threeLevel = 0;
        int levelThree = 0;
        for (int i = 0; i < 10000; i++) {
            Rolled rolled = SpecialEnchants.roll(Tool.SWORD, LevelWeights.DEFAULT, r);
            if (rolled.ability().maxLevel() == 3) {
                threeLevel++;
                if (rolled.level() == 3) {
                    levelThree++;
                }
            }
        }
        double ratio = (double) levelThree / threeLevel;
        assertTrue(ratio >= 0.03 && ratio <= 0.07, "level III ratio " + ratio);
    }

    @Test
    void zeroWeightsFallBackToLevelOne() {
        RandomSource r = RandomSource.create(7);
        for (int i = 0; i < 100; i++) {
            assertEquals(1, SpecialEnchants.roll(Tool.HOE, new LevelWeights(0, 0, 0), r).level());
        }
    }
}
