package org.gettserver.gettcore.pet.entity;

import java.util.function.IntSupplier;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

/** Vanilla melee chase with the hit interval taken from the species' attackSpeed instead of a fixed 20 ticks. */
final class PetMeleeAttackGoal extends MeleeAttackGoal {
    private final IntSupplier cooldownTicks;
    private int ticksUntilNextAttack;

    PetMeleeAttackGoal(PathfinderMob pet, double speedModifier, IntSupplier cooldownTicks) {
        super(pet, speedModifier, true);
        this.cooldownTicks = cooldownTicks;
    }

    @Override
    public void start() {
        super.start();
        ticksUntilNextAttack = 0;
    }

    @Override
    public void tick() {
        // Count down before super.tick(), which may attack and reset the cooldown.
        ticksUntilNextAttack = Math.max(ticksUntilNextAttack - 1, 0);
        super.tick();
    }

    @Override
    protected void resetAttackCooldown() {
        ticksUntilNextAttack = getAttackInterval();
    }

    @Override
    protected boolean isTimeToAttack() {
        return ticksUntilNextAttack <= 0;
    }

    @Override
    protected int getTicksUntilNextAttack() {
        return ticksUntilNextAttack;
    }

    @Override
    protected int getAttackInterval() {
        return adjustedTickDelay(cooldownTicks.getAsInt());
    }
}
