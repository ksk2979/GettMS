package org.gettserver.gettcore.pet.entity;

import com.crispytwig.naturalist.registry.NaturalistEntityTypes;
import com.crispytwig.naturalist.registry.NaturalistMobVariants;
import com.crispytwig.naturalist.world.entity.animal.tiger.Tiger;
import com.crispytwig.naturalist.world.entity.variant.MobVariant;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.gettserver.gettcore.pet.PetSpecies;

/** Fighter pet: follows its owner and fights what the owner fights. */
public class PetTiger extends Tiger implements GettPet {
    private static final EntityDataAccessor<CompoundTag> PET_DATA =
            SynchedEntityData.defineId(PetTiger.class, EntityDataSerializers.COMPOUND_TAG);
    /** Created lazily: the Mob constructor already needs it (registerGoals) before field initializers run. */
    private PetState pet;

    public PetTiger(EntityType<? extends PetTiger> type, Level level) {
        super(type, level);
        petState().init();
    }

    @Override
    public PetState petState() {
        if (pet == null) pet = new PetState(this, PET_DATA, PetSpecies.Kind.FIGHTER);
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

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        petState().startSeenBy(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        petState().stopSeenBy(player);
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        petState().onRemoved();
    }

    // Naturalist derives the variant registry from the entity id path; point it at the wild species.
    @Override
    public ResourceKey<Registry<MobVariant>> getVariantRegistryKey() {
        return NaturalistMobVariants.registryFor("tiger");
    }

    @Override
    public ResourceKey<MobVariant> getDefaultVariant() {
        return NaturalistMobVariants.defaultFor(NaturalistEntityTypes.TIGER.get());
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
    public boolean isFood(ItemStack stack) {
        return false;
    }

    @Override
    public boolean canFallInLove() {
        return false;
    }

    @Override
    public boolean canMate(Animal other) {
        return false;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        return PetBehavior.interact(this, player, hand);
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        return PetBehavior.canTarget(target) && super.canAttack(target);
    }
}
