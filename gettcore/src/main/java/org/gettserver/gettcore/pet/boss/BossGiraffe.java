package org.gettserver.gettcore.pet.boss;

import com.crispytwig.naturalist.registry.NaturalistEntityTypes;
import com.crispytwig.naturalist.registry.NaturalistMobVariants;
import com.crispytwig.naturalist.world.entity.animal.giraffe.Giraffe;
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
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootTable;

/** Boss giraffe: a scaled-up wild giraffe that hunts players around its boss point. Not tamable. */
public class BossGiraffe extends Giraffe implements GettBoss {
    /** Entity event: play the stomp animation (sent at the start of a stomp charge, and by the debug command). */
    public static final byte EVENT_STOMP = 70;
    /** How long the stomp animation runs (0.8 s charge + 0.3 s slam) before its state is stopped. */
    static final int STOMP_TICKS = 22;
    /** Entity event: play the 람각 foot animation (sent at the start of a wind line charge, and by the debug command). */
    public static final byte EVENT_RAMGAK = 71;
    /** How long the 람각 animation runs (1.0 s charge + kick, settled by 1.4 s) before its state is stopped. */
    static final int RAMGAK_TICKS = 28;
    private static final EntityDataAccessor<CompoundTag> BOSS_DATA =
            SynchedEntityData.defineId(BossGiraffe.class, EntityDataSerializers.COMPOUND_TAG);
    /** Created lazily: the Mob constructor already needs it (registerGoals) before field initializers run. */
    private BossState boss;
    /** Client: started by {@link #EVENT_STOMP}, played by the boss giraffe model. */
    public final AnimationState stompAnimationState = new AnimationState();
    private int stompStartTick;
    /** Client: started by {@link #EVENT_RAMGAK}, played by the boss giraffe model. */
    public final AnimationState ramgakAnimationState = new AnimationState();
    private int ramgakStartTick;

    public BossGiraffe(EntityType<? extends BossGiraffe> type, Level level) {
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
        return NaturalistMobVariants.registryFor("giraffe");
    }

    @Override
    public ResourceKey<MobVariant> getDefaultVariant() {
        return NaturalistMobVariants.defaultFor(NaturalistEntityTypes.GIRAFFE.get());
    }

    // Vanilla ticks the swing only for Monster/Player; without it Naturalist loops the attack animation.
    @Override
    public void aiStep() {
        super.aiStep();
        updateSwingTime();
    }

    /** Like {@link BossBehavior#registerGroundGoals}, but keeps its distance and casts skills instead of melee chase. */
    @Override
    protected void registerGoals() {
        goalSelector.removeAllGoals(g -> true);
        targetSelector.removeAllGoals(g -> true);
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new KeepDistanceGoal(this, BossBehavior.ATTACK_SPEED_MODIFIER));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 1.0));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        BossAggro.registerTargets(this, targetSelector);
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EVENT_STOMP) {
            stompAnimationState.start(tickCount);
            stompStartTick = tickCount;
        } else if (id == EVENT_RAMGAK) {
            ramgakAnimationState.start(tickCount);
            ramgakStartTick = tickCount;
        } else {
            super.handleEntityEvent(id);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            if (stompAnimationState.isStarted() && tickCount - stompStartTick > STOMP_TICKS) stompAnimationState.stop();
            if (ramgakAnimationState.isStarted() && tickCount - ramgakStartTick > RAMGAK_TICKS) {
                ramgakAnimationState.stop();
            }
        }
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
        return NaturalistEntityTypes.GIRAFFE.get().getDefaultLootTable();
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
