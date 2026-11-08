package org.gettserver.gettcore.pet.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.markusbordihn.easynpc.entity.easynpc.EasyNPC;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import org.gettserver.gettcore.pet.entity.PetBehavior.RecallReason;
import org.junit.jupiter.api.Test;

class PetBehaviorTest {
    @Test
    void canTargetRejectsPlayerAndPet() {
        assertFalse(PetBehavior.canTargetClass(Player.class, false, false));
        assertFalse(PetBehavior.canTargetClass(ServerPlayer.class, false, false));
        assertFalse(PetBehavior.canTargetClass(EasyNPC.class, false, false));
        assertFalse(PetBehavior.canTargetClass(Villager.class, false, false));
        assertFalse(PetBehavior.canTargetClass(WanderingTrader.class, false, false));
        assertFalse(PetBehavior.canTargetClass(GettPet.class, false, false));
        assertFalse(PetBehavior.canTargetClass(PetLion.class, false, true));
        // Someone's tamed animal is off limits unless it is a boss.
        assertFalse(PetBehavior.canTargetClass(Zombie.class, true, false));
        assertTrue(PetBehavior.canTargetClass(Zombie.class, true, true));
        assertTrue(PetBehavior.canTargetClass(Zombie.class, false, false));
    }

    @Test
    void recallWhenOwnerNullOrDead() {
        assertEquals(Optional.of(RecallReason.OWNER_MISSING), PetBehavior.recallReason(false, false, true, 0));
        assertEquals(Optional.of(RecallReason.OWNER_DEAD), PetBehavior.recallReason(true, true, true, 0));
        assertEquals(Optional.of(RecallReason.DIFFERENT_DIMENSION), PetBehavior.recallReason(true, false, false, 0));
        assertEquals(Optional.empty(), PetBehavior.recallReason(true, false, true, 3));
    }

    @Test
    void recallWhenFartherThan48() {
        assertEquals(Optional.of(RecallReason.TOO_FAR), PetBehavior.recallReason(true, false, true, 48.1));
        assertEquals(Optional.empty(), PetBehavior.recallReason(true, false, true, 47.9));
    }

    @Test
    void fallbackSpeciesAttackIs2() {
        var s = PetBehavior.fallbackSpecies("naturalist:lion", org.gettserver.gettcore.pet.PetSpecies.Kind.FIGHTER,
                net.minecraft.resources.ResourceLocation.parse("gettcore:pet_lion"));
        assertEquals(2.0, s.attack());
        assertEquals(1.0, s.attackSpeed());
    }

    @Test
    void attackCooldownMin5() {
        assertEquals(20, PetBehavior.attackCooldownTicks(1.0));
        assertEquals(10, PetBehavior.attackCooldownTicks(2.0));
        assertEquals(40, PetBehavior.attackCooldownTicks(0.5));
        assertEquals(5, PetBehavior.attackCooldownTicks(10.0));
        assertEquals(20, PetBehavior.attackCooldownTicks(0));
        assertEquals(20, PetBehavior.attackCooldownTicks(-1));
    }

    @Test
    void verticalSpeedBothKeysZero() {
        assertEquals(0.0, PetBehavior.verticalSpeed(true, true, 0.3));
        assertEquals(0.3, PetBehavior.verticalSpeed(true, false, 0.3));
        assertEquals(-0.3, PetBehavior.verticalSpeed(false, true, 0.3));
        assertEquals(0.0, PetBehavior.verticalSpeed(false, false, 0.3));
    }

    @Test
    void jumpVelocityIsStrengthTimesCharge() {
        assertEquals(0.6, PetBehavior.jumpVelocity(0.6, 1.0));
        assertEquals(0.2, PetBehavior.jumpVelocity(0.4, 0.5));
    }

    @Test
    void chargeScaleFollowsHorseFormula() {
        assertEquals(1.0, PetBehavior.chargeScale(90));
        assertEquals(1.0, PetBehavior.chargeScale(100));
        assertEquals(0.4, PetBehavior.chargeScale(0));
        assertEquals(0.4, PetBehavior.chargeScale(-5));
        assertEquals(0.6, PetBehavior.chargeScale(45), 1e-9);
    }

    @Test
    void followThresholds() {
        assertEquals(4.0, PetBehavior.FOLLOW_START);
        assertEquals(12.0, PetBehavior.FOLLOW_TELEPORT);
        assertTrue(PetBehavior.FOLLOW_STOP < PetBehavior.FOLLOW_START);
    }
}
