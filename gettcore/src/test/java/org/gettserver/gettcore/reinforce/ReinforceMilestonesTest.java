package org.gettserver.gettcore.reinforce;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.reinforce.ReinforceConfig.Milestone;
import org.gettserver.gettcore.reinforce.ReinforceMilestones.Held;
import org.gettserver.gettcore.reinforce.ReinforceRules.Target;
import org.junit.jupiter.api.Test;

class ReinforceMilestonesTest {
    private static final Held NONE = Held.NONE;

    private static Held armor(int level) {
        return new Held(Target.ARMOR, level);
    }

    private static Milestone ms(Target t, int level) {
        return new Milestone(t, level, ResourceLocation.parse("minecraft:speed"), 0);
    }

    @Test
    void mainHandGivesGunOrMelee() {
        Map<Target, Integer> l = ReinforceMilestones.levels(new Held(Target.MELEE, 7), NONE, false, List.of());
        assertEquals(7, l.get(Target.MELEE));
        assertEquals(0, l.get(Target.GUN));
        l = ReinforceMilestones.levels(new Held(Target.GUN, 4), new Held(Target.MELEE, 9), false, List.of());
        assertEquals(4, l.get(Target.GUN));
        assertEquals(0, l.get(Target.MELEE), "melee in the off hand does not count");
    }

    @Test
    void shieldCountsInEitherHandTakingTheHigher() {
        assertEquals(5, ReinforceMilestones.levels(NONE, new Held(Target.SHIELD, 5), false, List.of()).get(Target.SHIELD));
        assertEquals(6, ReinforceMilestones.levels(new Held(Target.SHIELD, 6), NONE, false, List.of()).get(Target.SHIELD));
        assertEquals(8, ReinforceMilestones.levels(new Held(Target.SHIELD, 3), new Held(Target.SHIELD, 8), false, List.of())
                .get(Target.SHIELD));
    }

    @Test
    void shieldIsZeroWhenMainHandBlocksIt() {
        assertEquals(0, ReinforceMilestones.levels(new Held(Target.GUN, 4), new Held(Target.SHIELD, 9), true, List.of())
                .get(Target.SHIELD), "gun in main hand");
        assertEquals(0, ReinforceMilestones.levels(NONE, new Held(Target.SHIELD, 9), true, List.of())
                .get(Target.SHIELD), "bow/crossbow/trident (not a reinforce target) in main hand");
        assertEquals(4, ReinforceMilestones.levels(new Held(Target.GUN, 4), new Held(Target.SHIELD, 9), true, List.of())
                .get(Target.GUN), "the gun itself still counts");
    }

    @Test
    void shieldLevelSelection() {
        assertEquals(0, ReinforceMilestones.shieldLevel(NONE, NONE, false));
        assertEquals(5, ReinforceMilestones.shieldLevel(NONE, new Held(Target.SHIELD, 5), false));
        assertEquals(6, ReinforceMilestones.shieldLevel(new Held(Target.SHIELD, 6), NONE, false));
        assertEquals(8, ReinforceMilestones.shieldLevel(new Held(Target.SHIELD, 8), new Held(Target.SHIELD, 3), false));
        assertEquals(7, ReinforceMilestones.shieldLevel(new Held(Target.MELEE, 20), new Held(Target.SHIELD, 7), false));
        assertEquals(2, ReinforceMilestones.shieldLevel(new Held(Target.SHIELD, 2), new Held(Target.MELEE, 20), false));
        assertEquals(0, ReinforceMilestones.shieldLevel(new Held(Target.GUN, 3), new Held(Target.SHIELD, 7), true));
    }

    @Test
    void armorIsTheMinimumOfAllFourPieces() {
        assertEquals(3, ReinforceMilestones.levels(NONE, NONE, false, List.of(armor(10), armor(3), armor(12), armor(5)))
                .get(Target.ARMOR));
        assertEquals(0, ReinforceMilestones.levels(NONE, NONE, false, List.of(armor(10), NONE, armor(12), armor(5)))
                .get(Target.ARMOR), "an empty slot");
        assertEquals(0, ReinforceMilestones.levels(NONE, NONE, false,
                List.of(armor(10), new Held(Target.MELEE, 20), armor(12), armor(5))).get(Target.ARMOR));
        assertEquals(0, ReinforceMilestones.levels(NONE, NONE, false, List.of(armor(10), armor(10))).get(Target.ARMOR));
    }

    @Test
    void activeIsInclusiveAndPerTarget() {
        List<Milestone> all = List.of(ms(Target.ARMOR, 10), ms(Target.SHIELD, 5), ms(Target.MELEE, 11),
                ms(Target.GUN, 1));
        Map<Target, Integer> levels = ReinforceMilestones.levels(new Held(Target.MELEE, 10), new Held(Target.SHIELD, 5), false,
                List.of(armor(10), armor(10), armor(10), armor(10)));
        assertEquals(List.of(ms(Target.ARMOR, 10), ms(Target.SHIELD, 5)), ReinforceMilestones.active(all, levels));
    }

    @Test
    void instantEffectsAreNeverApplied() {
        assertEquals(false, ReinforceMilestones.applicable(true));
        assertEquals(true, ReinforceMilestones.applicable(false));
    }

    @Test
    void armorWithAnEmptySlotIsInactive() {
        Map<Target, Integer> levels = ReinforceMilestones.levels(NONE, NONE, false,
                List.of(armor(30), armor(30), NONE, armor(30)));
        assertEquals(List.of(), ReinforceMilestones.active(List.of(ms(Target.ARMOR, 1)), levels));
    }
}
