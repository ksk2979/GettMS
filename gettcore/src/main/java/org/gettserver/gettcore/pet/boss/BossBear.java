package org.gettserver.gettcore.pet.boss;

import com.crispytwig.naturalist.registry.NaturalistEntityTypes;
import com.crispytwig.naturalist.registry.NaturalistMobVariants;
import com.crispytwig.naturalist.world.entity.animal.bear.Bear;
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
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootTable;

/** Boss bear: a scaled-up wild bear that hunts players around its boss point. Not tamable. */
public class BossBear extends Bear implements GettBoss {
    /** Entity event: the bear starts gathering for the roar (client: raises its head). Bear itself uses 6 and 7. */
    public static final byte EVENT_ROAR = 72;
    /** Entity event: the bear takes its charge stance (client: optional pose). */
    public static final byte EVENT_RUSH = 73;

    private static final EntityDataAccessor<CompoundTag> BOSS_DATA =
            SynchedEntityData.defineId(BossBear.class, EntityDataSerializers.COMPOUND_TAG);
    /** Created lazily: the Mob constructor already needs it (registerGoals) before field initializers run. */
    private BossState boss;

    public BossBear(EntityType<? extends BossBear> type, Level level) {
        super(type, level);
        bossState().init();
    }

    @Override
    public BossState bossState() {
        if (boss == null) boss = new BossState(this, BOSS_DATA);
        return boss;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(BOSS_DATA, new CompoundTag());
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        bossState().save(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        bossState().load(tag);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (BOSS_DATA.equals(key)) refreshDimensions();
    }

    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        return super.getDefaultDimensions(pose).scale(bossState().scale());
    }

    // Naturalist derives the variant registry from the entity id path; point it at the wild species.
    @Override
    public ResourceKey<Registry<MobVariant>> getVariantRegistryKey() {
        return NaturalistMobVariants.registryFor("bear");
    }

    @Override
    public ResourceKey<MobVariant> getDefaultVariant() {
        return NaturalistMobVariants.defaultFor(NaturalistEntityTypes.BEAR.get());
    }

    // Vanilla ticks the swing only for Monster/Player; without it Naturalist loops the attack animation.
    @Override
    public void aiStep() {
        super.aiStep();
        updateSwingTime();
    }

    @Override
    protected void registerGoals() {
        BossBehavior.registerGroundGoals(this, goalSelector, targetSelector);
    }

    @Override
    public void tick() {
        super.tick();
        bossState().tick();
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        bossState().startSeenBy(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        bossState().stopSeenBy(player);
    }

    // The wild species' loot table (gettcore:entities/boss_* does not exist), rolled LOOT_ROLLS times.
    @Override
    protected ResourceKey<LootTable> getDefaultLootTable() {
        return NaturalistEntityTypes.BEAR.get().getDefaultLootTable();
    }

    @Override
    protected void dropFromLootTable(DamageSource source, boolean hitByPlayer) {
        BossBehavior.dropLoot(() -> super.dropFromLootTable(source, hitByPlayer));
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean isPersistenceRequired() {
        return true;
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
        return InteractionResult.PASS;
    }
}
