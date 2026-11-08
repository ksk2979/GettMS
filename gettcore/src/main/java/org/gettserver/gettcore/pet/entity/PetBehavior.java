package org.gettserver.gettcore.pet.entity;

import de.markusbordihn.easynpc.entity.easynpc.EasyNPC;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;
import org.gettserver.gettcore.pet.PetSpecies;
import org.gettserver.gettcore.pet.boss.GettBoss;
import org.jetbrains.annotations.Nullable;

/** Shared pet logic. The pet classes cannot share a superclass (each extends its Naturalist original). */
public final class PetBehavior {
    /** A pet farther than this from its owner is recalled. */
    public static final double RECALL_DISTANCE = 48.0;
    /** The pet (walking or flying) starts moving back when farther than this... */
    public static final double FOLLOW_START = 4.0;
    /** ...stops when this close, so pets don't crowd the owner... */
    public static final double FOLLOW_STOP = 2.0;
    /** ...and teleports beside the owner when farther than this. */
    public static final double FOLLOW_TELEPORT = 12.0;
    /** Rider jump strength (Y velocity at full charge) of the ground mounts. */
    static final double JUMP_STRENGTH_LIGHT = 0.6;
    static final double JUMP_STRENGTH_HEAVY = 0.4;
    static final double BOSS_SPEED_MULT = 1.3;
    static final double FOLLOW_SPEED = 1.0;
    static final double ATTACK_SPEED_MODIFIER = 1.2;

    public enum RecallReason { OWNER_MISSING, OWNER_DEAD, TOO_FAR, DIFFERENT_DIMENSION }

    private PetBehavior() {}

    /** Attack filter: never players, Easy NPCs, villagers, other Gett pets, or someone's tamed animal (unless boss). */
    public static boolean canTarget(Entity candidate) {
        boolean tamed = candidate instanceof OwnableEntity o && o.getOwnerUUID() != null;
        return canTargetClass(candidate.getClass(), tamed, candidate instanceof GettBoss);
    }

    /** Pure part of {@link #canTarget}: decided by the candidate's class and its tamed/boss flags. */
    public static boolean canTargetClass(Class<?> candidateClass, boolean tamedWithOwner, boolean isBoss) {
        if (Player.class.isAssignableFrom(candidateClass)
                || EasyNPC.class.isAssignableFrom(candidateClass)
                || AbstractVillager.class.isAssignableFrom(candidateClass)
                || GettPet.class.isAssignableFrom(candidateClass)) {
            return false;
        }
        return !(tamedWithOwner && !isBoss);
    }

    /** Why the pet must go back into its owner's collection, or empty when it may stay out. */
    public static Optional<RecallReason> recallReason(@Nullable Player owner, Mob pet) {
        if (owner == null || owner.isRemoved()) return recallReason(false, false, false, 0);
        boolean sameDimension = owner.level().dimension().equals(pet.level().dimension());
        return recallReason(true, owner.isDeadOrDying(), sameDimension, sameDimension ? owner.distanceTo(pet) : 0);
    }

    /** Pure part of {@link #recallReason(Player, Mob)}. */
    public static Optional<RecallReason> recallReason(boolean ownerPresent, boolean ownerDead, boolean sameDimension,
                                                      double distance) {
        if (!ownerPresent) return Optional.of(RecallReason.OWNER_MISSING);
        if (ownerDead) return Optional.of(RecallReason.OWNER_DEAD);
        if (!sameDimension) return Optional.of(RecallReason.DIFFERENT_DIMENSION);
        if (distance > RECALL_DISTANCE) return Optional.of(RecallReason.TOO_FAR);
        return Optional.empty();
    }

    /** MOVEMENT_SPEED = speed (×1.3 for a boss pet); ATTACK_DAMAGE = attack (×attackMult for a boss pet) if present. */
    public static void applyStats(Mob pet, PetSpecies s, boolean boss) {
        setBase(pet, Attributes.MOVEMENT_SPEED, boss ? s.speed() * BOSS_SPEED_MULT : s.speed());
        setBase(pet, Attributes.ATTACK_DAMAGE, boss ? s.attack() * s.attackMult() : s.attack());
    }

    private static void setBase(Mob pet, Holder<Attribute> attribute, double value) {
        AttributeInstance instance = pet.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(value);
    }

    /** Ticks between melee hits: round(20 / attackSpeed), at least 5; a non-positive speed means one hit a second. */
    public static int attackCooldownTicks(double attackSpeed) {
        if (attackSpeed <= 0) return 20;
        return (int) Math.max(5, Math.round(20.0 / attackSpeed));
    }

    /** Vertical flight speed for the rider's keys; both or neither held → 0. */
    public static double verticalSpeed(boolean up, boolean down, double climbSpeed) {
        if (up == down) return 0.0;
        return up ? climbSpeed : -climbSpeed;
    }

    /** Horse-style charge of the rider's jump bar (0..100): 90+ is a full jump, otherwise 0.4..0.8. */
    public static double chargeScale(int jumpPower) {
        if (jumpPower < 0) jumpPower = 0;
        return jumpPower >= 90 ? 1.0 : 0.4 + 0.4 * jumpPower / 90.0;
    }

    /** Upward velocity of a mount jump before the block jump factor. */
    public static double jumpVelocity(double strength, double chargeScale) {
        return strength * chargeScale;
    }

    /** Applies a rider jump: only from the ground and not while in water. */
    static void mountJump(LivingEntity mount, double strength, double scale, float blockJumpFactor) {
        if (!mount.onGround() || mount.isInWater()) return;
        Vec3 v = mount.getDeltaMovement();
        mount.setDeltaMovement(v.x, jumpVelocity(strength, scale) * blockJumpFactor, v.z);
        mount.hasImpulse = true;
    }

    /** Fallback when the species is missing from pets.json (same numbers as the JSON defaults). */
    static PetSpecies fallbackSpecies(String id, PetSpecies.Kind kind, ResourceLocation petEntity) {
        return new PetSpecies(id, kind, petEntity, 2, 0.3, 1, 0.6, 0.3, false, 1, 1, 1, 0, 0);
    }

    /** Pets ignore all damage except sources that bypass invulnerability ({@code /kill}, the void). */
    public static boolean blocksDamage(DamageSource source) {
        return !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
    }

    /** Replaces the Naturalist AI: float, (fighter: melee + defend owner), follow owner, look at players. */
    public static void registerGoals(PathfinderMob pet, GoalSelector goals, GoalSelector targets) {
        goals.removeAllGoals(g -> true);
        targets.removeAllGoals(g -> true);
        goals.addGoal(0, new FloatGoal(pet));
        if (((GettPet) pet).kind() == PetSpecies.Kind.FIGHTER) {
            goals.addGoal(1, new PetMeleeAttackGoal(pet, ATTACK_SPEED_MODIFIER,
                    () -> ((GettPet) pet).petState().attackCooldownTicks()));
            if (pet instanceof TamableAnimal tamable) {
                targets.addGoal(1, new OwnerHurtByTargetGoal(tamable));
                targets.addGoal(2, new OwnerHurtTargetGoal(tamable));
            }
        }
        goals.addGoal(2, new PetFollowGoal(pet, FOLLOW_SPEED));
        goals.addGoal(3, new LookAtPlayerGoal(pet, Player.class, 8.0F));
    }

    /** Owner's empty main hand on a mount → ride it. Everything else (feeding, taming, saddling) is ignored. */
    public static InteractionResult interact(Mob pet, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !(pet instanceof GettPet gettPet)
                || gettPet.kind() == PetSpecies.Kind.FIGHTER
                || !player.getUUID().equals(gettPet.ownerId())
                || !player.getMainHandItem().isEmpty()
                || player.isPassenger() || pet.isVehicle()) {
            return InteractionResult.PASS;
        }
        if (!pet.level().isClientSide) {
            player.setYRot(pet.getYRot());
            player.setXRot(pet.getXRot());
            player.startRiding(pet);
        }
        return InteractionResult.sidedSuccess(pet.level().isClientSide);
    }

    /** The owner when online in the pet's level, else null. */
    @Nullable
    public static Player owner(Mob pet) {
        UUID id = pet instanceof GettPet g ? g.ownerId() : null;
        return id == null ? null : pet.level().getPlayerByUUID(id);
    }

    /** The first passenger if it is the owner (only the owner may steer). */
    @Nullable
    static Player ownerRider(Entity pet, @Nullable UUID ownerId) {
        return pet.getFirstPassenger() instanceof Player p && p.getUUID().equals(ownerId) ? p : null;
    }

    /** Horse-style input: half strafe, full forward, half backwards. */
    public static Vec3 riddenInput(Player rider) {
        float forward = rider.zza <= 0 ? rider.zza * 0.5F : rider.zza;
        return new Vec3(rider.xxa * 0.5F, 0, forward);
    }

    /** Horse-style steering: the mount faces where the rider looks. */
    static void steer(LivingEntity mount, Player rider) {
        mount.setYRot(rider.getYRot());
        mount.setXRot(rider.getXRot() * 0.5F);
        mount.yRotO = mount.getYRot();
        mount.yBodyRot = mount.getYRot();
        mount.yHeadRot = mount.getYRot();
    }

    /** One tick of ridden flight: horizontal speed eases toward {@code flySpeed} along the input, vertical is set. */
    static void flyRidden(LivingEntity mount, Vec3 input, double flySpeed, double verticalSpeed) {
        Vec3 old = mount.getDeltaMovement();
        mount.setDeltaMovement(Vec3.ZERO);
        mount.moveRelative((float) flySpeed, new Vec3(input.x, 0, input.z));
        Vec3 target = mount.getDeltaMovement();
        Vec3 motion = new Vec3(Mth.lerp(0.25, old.x, target.x), verticalSpeed, Mth.lerp(0.25, old.z, target.z));
        mount.setDeltaMovement(motion);
        mount.move(MoverType.SELF, motion);
        mount.resetFallDistance();
        mount.calculateEntityAnimation(true);
    }

    /** Vanilla-style teleport to a free walkable spot 2–3 blocks around {@code target}; false if none found. */
    static boolean teleportNear(Mob pet, Entity target) {
        BlockPos base = target.blockPosition();
        RandomSource random = pet.getRandom();
        for (int i = 0; i < 10; i++) {
            int dx = random.nextIntBetweenInclusive(-3, 3);
            int dz = random.nextIntBetweenInclusive(-3, 3);
            if (Math.abs(dx) < 2 && Math.abs(dz) < 2) continue;
            BlockPos pos = base.offset(dx, random.nextIntBetweenInclusive(-1, 1), dz);
            if (WalkNodeEvaluator.getPathTypeStatic(pet, pos) == PathType.WALKABLE
                    && pet.level().noCollision(pet, pet.getBoundingBox().move(pos.subtract(pet.blockPosition())))) {
                pet.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, pet.getYRot(), pet.getXRot());
                pet.getNavigation().stop();
                return true;
            }
        }
        return false;
    }
}
