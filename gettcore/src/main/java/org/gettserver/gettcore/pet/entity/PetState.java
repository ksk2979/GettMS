package org.gettserver.gettcore.pet.entity;

import com.crispytwig.naturalist.world.entity.variant.DataDrivenVariantAnimal;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.gettserver.gettcore.client.ClientPetConfig;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.pet.CapturedMobItem;
import org.gettserver.gettcore.pet.PetKey;
import org.gettserver.gettcore.pet.PetSpecies;
import org.jetbrains.annotations.Nullable;

/**
 * State and per-tick logic of one pet entity, shared by all pet classes. Persistent values live in one synched
 * {@link CompoundTag} (the accessor is defined by each pet class) so they reach the client and survive save/reload:
 * {@code Species}, {@code Variant}, {@code Boss}, {@code Scale}, and {@code Owner} for pets that are not
 * {@link TamableAnimal} (tamables keep the owner in vanilla's own field).
 */
public final class PetState {
    private static final String NBT_KEY = "GettPet";
    private static final String SPECIES = "Species";
    private static final String VARIANT = "Variant";
    private static final String BOSS = "Boss";
    private static final String SCALE = "Scale";
    private static final String OWNER = "Owner";

    private final Mob mob;
    private final EntityDataAccessor<CompoundTag> data;
    private final PetSpecies.Kind kind;
    private boolean flightUp;
    private boolean flightDown;
    private boolean wasRidden;
    /** True while the ridden mount runs its original {@code travel}, which must not see the rider (see #travel). */
    private boolean inRiddenTravel;
    /** Ground mounts: charge of the rider's released jump bar, applied on the next ridden tick (0 = none). */
    private double pendingJumpScale;
    /** Boss pets only, server side, created when the owner starts tracking the pet. */
    @Nullable
    private ServerBossEvent bossBar;

    public PetState(Mob mob, EntityDataAccessor<CompoundTag> data, PetSpecies.Kind kind) {
        this.mob = mob;
        this.data = data;
        this.kind = kind;
    }

    /** Constructor-time setup: never despawn, never pick up items. */
    public void init() {
        mob.setPersistenceRequired();
        mob.setCanPickUpLoot(false);
    }

    public PetSpecies.Kind kind() {
        return kind;
    }

    private CompoundTag tag() {
        return mob.getEntityData().get(data);
    }

    @Nullable
    public UUID ownerId() {
        if (mob instanceof TamableAnimal tamable) return tamable.getOwnerUUID();
        CompoundTag tag = tag();
        return tag.hasUUID(OWNER) ? tag.getUUID(OWNER) : null;
    }

    @Nullable
    public PetKey key() {
        CompoundTag tag = tag();
        return tag.contains(SPECIES, Tag.TAG_STRING) ? new PetKey(tag.getString(SPECIES), tag.getString(VARIANT)) : null;
    }

    public boolean boss() {
        return tag().getBoolean(BOSS);
    }

    /** Model and hitbox scale: the species' sizeScale for a boss pet, else 1. */
    public float scale() {
        CompoundTag tag = tag();
        return tag.contains(SCALE, Tag.TAG_FLOAT) ? tag.getFloat(SCALE) : 1.0F;
    }

    /** Server side, right after spawning. */
    public void setOwner(UUID id, PetKey key, boolean boss) {
        if (mob instanceof TamableAnimal tamable) {
            tamable.setOwnerUUID(id);
            tamable.setTame(true, false);
        }
        PetSpecies species = species(key);
        CompoundTag tag = new CompoundTag();
        tag.putString(SPECIES, key.speciesId());
        tag.putString(VARIANT, key.variant());
        tag.putBoolean(BOSS, boss);
        tag.putFloat(SCALE, boss ? (float) species.sizeScale() : 1.0F);
        if (!(mob instanceof TamableAnimal)) tag.putUUID(OWNER, id);
        mob.getEntityData().set(data, tag);
        // The stored variant is the full key (e.g. naturalist:white_tiger), exactly what setVariantString expects.
        if (mob instanceof DataDrivenVariantAnimal animal && PetKey.hasVariant(key.variant())) {
            animal.setVariantString(key.variant());
        }
        PetBehavior.applyStats(mob, species, boss);
        // The hitbox follows the new Scale through each pet's onSyncedDataUpdated (fires on both sides).
    }

    /** Re-applies the current pets.json stats in place (config reload while the pet cannot be recalled). */
    public void reapplyStats() {
        if (key() != null) PetBehavior.applyStats(mob, species(), boss());
    }

    /**
     * Spec 4.5: a summoned boss pet shows a boss bar to its owner only, always full (pets cannot be hurt). Called
     * from each pet class's {@code startSeenByPlayer}.
     */
    public void startSeenBy(ServerPlayer player) {
        PetKey key = key();
        if (!boss() || key == null || !player.getUUID().equals(ownerId())) return;
        if (bossBar == null) {
            bossBar = new ServerBossEvent(CapturedMobItem.displayName(key), BossEvent.BossBarColor.PURPLE,
                    BossEvent.BossBarOverlay.PROGRESS);
            bossBar.setProgress(1.0F);
        }
        bossBar.addPlayer(player);
    }

    public void stopSeenBy(ServerPlayer player) {
        if (bossBar != null) bossBar.removePlayer(player);
    }

    /** Entity removed (recalled, unloaded, discarded): nobody keeps seeing its bar. */
    public void onRemoved() {
        if (bossBar != null) bossBar.removeAllPlayers();
    }

    public void save(CompoundTag out) {
        out.put(NBT_KEY, tag().copy());
    }

    /** Restores the synched tag and re-applies the current pets.json stats (server side). */
    public void load(CompoundTag in) {
        if (!in.contains(NBT_KEY, Tag.TAG_COMPOUND)) return;
        mob.getEntityData().set(data, in.getCompound(NBT_KEY));
        PetKey key = key();
        if (key != null && !mob.level().isClientSide) PetBehavior.applyStats(mob, species(key), boss());
    }

    /** This pet's species from pets.json (the client's synced copy on the client), or the JSON defaults. */
    PetSpecies species() {
        PetKey key = key();
        return key != null ? species(key) : fallback("");
    }

    private PetSpecies species(PetKey key) {
        ResourceLocation id = ResourceLocation.tryParse(key.speciesId());
        Optional<PetSpecies> found = id == null ? Optional.empty()
                : mob.level().isClientSide ? ClientPetConfig.species(id) : GettData.get().pets().species(id);
        return found.orElseGet(() -> fallback(key.speciesId()));
    }

    private PetSpecies fallback(String id) {
        return PetBehavior.fallbackSpecies(id, kind, BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()));
    }

    int attackCooldownTicks() {
        return PetBehavior.attackCooldownTicks(species().attackSpeed());
    }

    public void setFlightInput(boolean up, boolean down) {
        if (kind != PetSpecies.Kind.FLYING_MOUNT) return;
        flightUp = up;
        flightDown = down;
    }

    /** The owner while riding; null during {@link #travel}'s original-travel call so that code takes its AI path. */
    @Nullable
    public Player controllingRider() {
        return inRiddenTravel ? null : PetBehavior.ownerRider(mob, ownerId());
    }

    public boolean inRiddenTravel() {
        return inRiddenTravel;
    }

    public void tickRidden(Player rider) {
        PetBehavior.steer(mob, rider);
    }

    /** {@code PlayerRideableJumping.onPlayerJump}: called on the rider's client when the jump bar is released. */
    public void onPlayerJump(int jumpPower) {
        pendingJumpScale = PetBehavior.chargeScale(jumpPower);
    }

    /**
     * Ground mount ridden tick, after {@link #tickRidden}. Like {@code AbstractHorse.tickRidden} the pending jump is
     * applied where the movement is simulated (the rider's client, which then syncs the vehicle position).
     */
    public void tickRiddenJump(double strength, float blockJumpFactor) {
        if (!mob.isControlledByLocalInstance() || pendingJumpScale <= 0) return;
        if (mob.onGround() || mob.isInWater()) {
            PetBehavior.mountJump(mob, strength, pendingJumpScale, blockJumpFactor);
            pendingJumpScale = 0;
        }
    }

    /**
     * Ridden movement. {@code superTravel} is the pet class's {@code super::travel}. By the time this runs vanilla
     * has already set the speed to {@code getRiddenSpeed} and the input to {@code getRiddenInput}; the Naturalist
     * mounts' own {@code travel} would redo both (with its own speed factor), so while it runs the rider is hidden
     * and the entity reports itself locally controlled, which sends it straight to {@code LivingEntity.travel}.
     * A ridden flying mount does its own flight step instead.
     */
    public void travel(Vec3 input, Consumer<Vec3> superTravel) {
        if (controllingRider() == null) {
            superTravel.accept(input);
        } else if (kind == PetSpecies.Kind.FLYING_MOUNT) {
            PetSpecies species = species();
            PetBehavior.flyRidden(mob, input, species.flySpeed(),
                    PetBehavior.verticalSpeed(flightUp, flightDown, species.climbSpeed()));
        } else {
            inRiddenTravel = true;
            try {
                superTravel.accept(input);
            } finally {
                inRiddenTravel = false;
            }
        }
    }

    /** Flying mount: no gravity while the owner rides; gravity back (and keys released) once they get off. */
    public void tick() {
        if (kind != PetSpecies.Kind.FLYING_MOUNT) return;
        boolean ridden = controllingRider() != null;
        if (ridden) {
            mob.setNoGravity(true);
        } else if (wasRidden) {
            mob.setNoGravity(false);
            flightUp = false;
            flightDown = false;
        }
        wasRidden = ridden;
    }
}
