package org.gettserver.gettcore.pet.entity;

import com.crispytwig.naturalist.registry.NaturalistEntityTypes;
import com.crispytwig.naturalist.registry.NaturalistMobVariants;
import com.crispytwig.naturalist.world.entity.animal.vulture.Vulture;
import com.crispytwig.naturalist.world.entity.variant.MobVariant;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.gettserver.gettcore.pet.PetSpecies;
import org.jetbrains.annotations.Nullable;

/** Flying mount pet: follows its owner; ridden, jump/sprint climb/descend (see PetFlightInput). */
public class PetVulture extends Vulture implements GettPet {
    private static final EntityDataAccessor<CompoundTag> PET_DATA =
            SynchedEntityData.defineId(PetVulture.class, EntityDataSerializers.COMPOUND_TAG);
    /** Created lazily: the Mob constructor already needs it (registerGoals) before field initializers run. */
    private PetState pet;

    public PetVulture(EntityType<? extends PetVulture> type, Level level) {
        super(type, level);
        petState().init();
    }

    @Override
    public PetState petState() {
        if (pet == null) pet = new PetState(this, PET_DATA, PetSpecies.Kind.FLYING_MOUNT);
        return pet;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(PET_DATA, new CompoundTag());
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        petState().save(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        petState().load(tag);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (PET_DATA.equals(key)) refreshDimensions();
    }

    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        return super.getDefaultDimensions(pose).scale(petState().scale());
    }

    // Naturalist derives the variant registry from the entity id path; point it at the wild species.
    @Override
    public ResourceKey<Registry<MobVariant>> getVariantRegistryKey() {
        return NaturalistMobVariants.registryFor("vulture");
    }

    @Override
    public ResourceKey<MobVariant> getDefaultVariant() {
        return NaturalistMobVariants.defaultFor(NaturalistEntityTypes.VULTURE.get());
    }

    @Override
    protected void registerGoals() {
        PetBehavior.registerGoals(this, goalSelector, targetSelector);
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return PetBehavior.blocksDamage(source) || super.isInvulnerableTo(source);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return !isInvulnerableTo(source) && super.hurt(source, amount);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    public boolean isBaby() {
        return false;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        return PetBehavior.interact(this, player, hand);
    }

    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        return petState().controllingRider();
    }

    @Override
    public boolean isControlledByLocalInstance() {
        return petState().inRiddenTravel() || super.isControlledByLocalInstance();
    }

    @Override
    public void travel(Vec3 input) {
        petState().travel(input, super::travel);
    }

    @Override
    protected void tickRidden(Player rider, Vec3 input) {
        super.tickRidden(rider, input);
        petState().tickRidden(rider);
    }

    @Override
    protected Vec3 getRiddenInput(Player rider, Vec3 input) {
        return PetBehavior.riddenInput(rider);
    }

    @Override
    protected float getRiddenSpeed(Player rider) {
        return (float) getAttributeValue(Attributes.MOVEMENT_SPEED);
    }

    @Override
    public boolean shouldRiderFaceForward(Player player) {
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        petState().tick();
    }
}
