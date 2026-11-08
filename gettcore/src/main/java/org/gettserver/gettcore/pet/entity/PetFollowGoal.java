package org.gettserver.gettcore.pet.entity;

import java.util.EnumSet;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Walks (or flies, with a flying navigation) back to the owner when farther than
 * {@link PetBehavior#FOLLOW_START}; teleports beside the owner beyond {@link PetBehavior#FOLLOW_TELEPORT}.
 */
final class PetFollowGoal extends Goal {
    private final PathfinderMob pet;
    private final double speedModifier;
    @Nullable
    private Player owner;
    private int timeToRecalcPath;

    PetFollowGoal(PathfinderMob pet, double speedModifier) {
        this.pet = pet;
        this.speedModifier = speedModifier;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Player candidate = PetBehavior.owner(pet);
        if (candidate == null || candidate.isSpectator() || pet.isVehicle() || pet.isPassenger()) return false;
        if (pet.distanceToSqr(candidate) < sq(PetBehavior.FOLLOW_START)) return false;
        owner = candidate;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return owner != null && owner.isAlive() && !pet.isVehicle() && !pet.getNavigation().isDone()
                && pet.distanceToSqr(owner) > sq(PetBehavior.FOLLOW_STOP);
    }

    @Override
    public void start() {
        timeToRecalcPath = 0;
    }

    @Override
    public void stop() {
        owner = null;
        pet.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (owner == null) return;
        pet.getLookControl().setLookAt(owner, 10.0F, pet.getMaxHeadXRot());
        if (--timeToRecalcPath > 0) return;
        timeToRecalcPath = adjustedTickDelay(10);
        boolean teleported = pet.distanceToSqr(owner) > sq(PetBehavior.FOLLOW_TELEPORT)
                && PetBehavior.teleportNear(pet, owner);
        if (!teleported) pet.getNavigation().moveTo(owner, speedModifier);
    }

    private static double sq(double d) {
        return d * d;
    }
}
