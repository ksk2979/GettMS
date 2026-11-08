package org.gettserver.gettcore.pet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.economy.RateLimiter;
import org.gettserver.gettcore.network.PetDexActionPayload;
import org.gettserver.gettcore.network.PetDexSyncPayload;
import org.gettserver.gettcore.pet.entity.GettPet;
import org.gettserver.gettcore.pet.entity.PetBehavior;
import org.jetbrains.annotations.Nullable;

/** Summoning, recalling and auto-returning pets; each player's active pet entity is tracked in memory. */
public final class PetService {
    public enum SummonResult { SUMMONED, RECALLED, BLOCKED_ACTIVE, INVALID_INDEX }

    public enum ReleaseResult { RELEASED, INVALID_INDEX }

    /** What a config reload does to one summoned pet (see {@link #reloadAction}). */
    public enum ReloadAction { KEEP, REAPPLY, RECALL }

    /** Pets appear this many blocks in front of the owner (horizontally). */
    static final double SUMMON_DISTANCE = 2.0;
    /** From the owner's feet level, how many blocks up the summon spot may be lifted to find a free 2-block space. */
    static final int SUMMON_SEARCH_UP = 4;

    /** One summoned pet: its entity, its key, and the species config it was summoned (or last re-applied) with. */
    private record Active(UUID petId, PetKey key, @Nullable PetSpecies species) {}

    /** Owner UUID to their summoned pet. Server thread only. */
    private static final Map<UUID, Active> ACTIVE = new HashMap<>();
    /** One dex click per 250 ms per player. */
    private static final RateLimiter ACTION_LIMITER = new RateLimiter(250);

    private PetService() {}

    /** What a click on dex entry {@code index} does. */
    public static SummonResult decide(List<PetKey> owned, Optional<PetKey> active, int index) {
        if (index < 0 || index >= owned.size()) return SummonResult.INVALID_INDEX;
        if (active.isPresent() && active.get().equals(owned.get(index))) return SummonResult.RECALLED;
        if (active.isPresent()) return SummonResult.BLOCKED_ACTIVE;
        return SummonResult.SUMMONED;
    }

    /** What a release of dex entry {@code index} does. */
    public static ReleaseResult decideRelease(List<PetKey> owned, int index) {
        return index < 0 || index >= owned.size() ? ReleaseResult.INVALID_INDEX : ReleaseResult.RELEASED;
    }

    public static void handleAction(ServerPlayer player, int index, PetDexActionPayload.Action action) {
        if (!ACTION_LIMITER.tryAcquire(player.getUUID(), System.currentTimeMillis())) return;
        switch (action) {
            case SUMMON -> summonOrRecall(player, index);
            case RELEASE -> release(player, index);
        }
    }

    /**
     * Releases dex entry {@code index} for good: recalls it first if it is summoned, then removes it from the dex.
     * Stale or forged indices are ignored. Returns whether a pet was released.
     */
    public static boolean release(ServerPlayer player, int index) {
        PetData data = PetData.get(player.server);
        List<PetKey> owned = data.owned(player.getUUID());
        if (decideRelease(owned, index) != ReleaseResult.RELEASED) return false;
        PetKey key = owned.get(index);
        removeKey(player, key);
        player.sendSystemMessage(Component.translatable("gettcore.msg.pet.released",
                CapturedMobItem.displayName(key)).withStyle(ChatFormatting.YELLOW));
        return true;
    }

    /** Recalls {@code key} silently if it is the active pet, removes it from the player's dex and syncs. */
    static boolean removeKey(ServerPlayer player, PetKey key) {
        PetData data = PetData.get(player.server);
        if (data.active(player.getUUID()).filter(key::equals).isPresent()) {
            recall(player, true);
        }
        boolean removed = data.remove(player.getUUID(), key);
        sync(player);
        return removed;
    }

    private static void summonOrRecall(ServerPlayer player, int index) {
        PetData data = PetData.get(player.server);
        List<PetKey> owned = data.owned(player.getUUID());
        switch (decide(owned, data.active(player.getUUID()), index)) {
            case SUMMONED -> summon(player, owned.get(index));
            case RECALLED -> recall(player, false);
            case BLOCKED_ACTIVE -> player.sendSystemMessage(
                    Component.translatable("gettcore.msg.pet.blocked_active").withStyle(ChatFormatting.YELLOW));
            case INVALID_INDEX -> {
                // stale or forged index: ignore
            }
        }
    }

    private static void summon(ServerPlayer player, PetKey key) {
        Optional<PetSpecies> species = species(key);
        Optional<EntityType<?>> type = species.flatMap(s -> BuiltInRegistries.ENTITY_TYPE.getOptional(s.petEntity()));
        ServerLevel level = player.serverLevel();
        Entity created = type.map(t -> (Entity) t.create(level)).orElse(null);
        if (!(created instanceof GettPet pet) || !(created instanceof Mob)) {
            if (created != null) created.discard();
            GettCore.LOGGER.warn("gettcore: cannot summon pet {} for {}: species {}, petEntity {}", key,
                    player.getGameProfile().getName(), species.isPresent() ? "found" : "missing",
                    species.map(s -> s.petEntity().toString()).orElse("-"));
            unknown(player);
            return;
        }
        boolean boss = species.get().boss();
        Vec3 pos = summonPos(player, level);
        created.moveTo(pos.x, pos.y, pos.z, player.getYRot() + 180.0F, 0.0F);
        pet.setOwner(player.getUUID(), key, boss);
        // Track before adding so the join-level check in PetEvents accepts the entity.
        ACTIVE.put(player.getUUID(), new Active(created.getUUID(), key, species.get()));
        if (!level.addFreshEntity(created)) {
            ACTIVE.remove(player.getUUID());
            GettCore.LOGGER.warn("gettcore: level refused pet entity {} for {}", key, player.getGameProfile().getName());
            unknown(player);
            return;
        }
        PetData.get(player.server).setActive(player.getUUID(), key);
        whistle(player);
        player.sendSystemMessage(Component.translatable("gettcore.msg.pet.summoned",
                CapturedMobItem.displayName(key)).withStyle(ChatFormatting.GREEN));
        sync(player);
    }

    private static void unknown(ServerPlayer player) {
        player.sendSystemMessage(Component.translatable("gettcore.msg.pet.unknown").withStyle(ChatFormatting.RED));
    }

    /**
     * Two blocks in front of the owner (horizontal look direction), starting at the owner's feet level and lifted
     * by up to {@value #SUMMON_SEARCH_UP} blocks to the first free 2-block-high space; the owner's own position when
     * there is none. Never the world heightmap (that would put pets on the roof of a cave or a house).
     */
    private static Vec3 summonPos(ServerPlayer player, ServerLevel level) {
        Vec3 dir = Vec3.directionFromRotation(0.0F, player.getYRot());
        Vec3 ahead = player.position().add(dir.scale(SUMMON_DISTANCE));
        BlockPos base = BlockPos.containing(ahead.x, player.getY(), ahead.z);
        for (int dy = 0; dy <= SUMMON_SEARCH_UP; dy++) {
            BlockPos feet = base.above(dy);
            if (isFree(level, feet) && isFree(level, feet.above())) {
                return new Vec3(ahead.x, dy == 0 ? player.getY() : feet.getY(), ahead.z);
            }
        }
        return player.position();
    }

    private static boolean isFree(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** Puts the active pet back: discards its entity and clears active; whistle + message unless silent. */
    public static void recall(ServerPlayer player, boolean silent) {
        Optional<PetKey> active = PetData.get(player.server).active(player.getUUID());
        clear(player.server, player.getUUID());
        if (!silent) {
            whistle(player);
            active.ifPresent(key -> player.sendSystemMessage(Component.translatable("gettcore.msg.pet.recalled",
                    CapturedMobItem.displayName(key)).withStyle(ChatFormatting.GREEN)));
        }
        if (!player.hasDisconnected()) {
            sync(player);
        }
    }

    /** Silent recall that also works while the owner is offline. */
    private static void recall(MinecraftServer server, UUID owner) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player != null) {
            recall(player, true);
        } else {
            clear(server, owner);
        }
    }

    private static void clear(MinecraftServer server, UUID owner) {
        Active active = ACTIVE.remove(owner);
        if (active != null) {
            Entity pet = findEntity(server, active.petId());
            if (pet != null) {
                pet.discard();
            }
        }
        PetData data = PetData.get(server);
        if (data.active(owner).isPresent()) {
            data.setActive(owner, null);
        }
    }

    @Nullable
    private static Entity findEntity(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e != null) return e;
        }
        return null;
    }

    public static void sync(ServerPlayer player) {
        PetData data = PetData.get(player.server);
        List<PetKey> owned = data.owned(player.getUUID());
        List<Boolean> boss = new ArrayList<>(owned.size());
        for (PetKey key : owned) {
            boss.add(isBoss(key));
        }
        PacketDistributor.sendToPlayer(player,
                new PetDexSyncPayload(List.copyOf(owned), boss, data.active(player.getUUID())));
    }

    /** Pets never survive a restart: clear every saved active pet and the tracking map. */
    public static void onServerStarted(MinecraftServer server) {
        ACTIVE.clear();
        PetData.get(server).clearAllActive();
    }

    /** Login: a saved active pet with no tracked entity is stale (crash, restart), so clear it; then sync. */
    public static void onLogin(ServerPlayer player) {
        if (!ACTIVE.containsKey(player.getUUID())) {
            PetData data = PetData.get(player.server);
            if (data.active(player.getUUID()).isPresent()) {
                data.setActive(player.getUUID(), null);
            }
        }
        sync(player);
    }

    /** Recalls pets whose entity is gone or whose owner is offline, dead, in another dimension or too far away. */
    public static void tick(MinecraftServer server) {
        for (Map.Entry<UUID, Active> e : new ArrayList<>(ACTIVE.entrySet())) {
            Entity pet = findEntity(server, e.getValue().petId());
            if (!(pet instanceof Mob mob) || pet.isRemoved()) {
                recall(server, e.getKey());
                continue;
            }
            // Resolve through the player list (not the pet's level) so DIFFERENT_DIMENSION is reachable.
            ServerPlayer owner = server.getPlayerList().getPlayer(e.getKey());
            if (PetBehavior.recallReason(owner, mob).isPresent()) {
                recall(server, e.getKey());
            }
        }
    }

    /** Whether {@code entity} (a {@link GettPet}) is the tracked active pet of its online owner. */
    public static boolean isTrackedActive(MinecraftServer server, GettPet pet, Entity entity) {
        UUID owner = pet.ownerId();
        if (owner == null || server.getPlayerList().getPlayer(owner) == null) return false;
        Active active = ACTIVE.get(owner);
        return active != null && entity.getUUID().equals(active.petId());
    }

    /**
     * Pure reload rule for one summoned pet: unchanged species entry: {@link ReloadAction#KEEP}; changed or removed:
     * {@link ReloadAction#RECALL}, except a flying mount that is carrying someone, which is never recalled mid-air
     * (that would drop the rider) and only gets its stats re-applied ({@link ReloadAction#REAPPLY}).
     */
    public static ReloadAction reloadAction(@Nullable PetSpecies before, Optional<PetSpecies> after,
                                            boolean flyingMountWithRider) {
        if (before != null && after.isPresent() && after.get().equals(before)) return ReloadAction.KEEP;
        return flyingMountWithRider ? ReloadAction.REAPPLY : ReloadAction.RECALL;
    }

    /**
     * Registered once at startup: after /gett reload or an editor save every summoned pet goes through
     * {@link #reloadAction}. Its species entry is compared with the one it was summoned with; only pets whose entry
     * changed or disappeared are recalled, and a flying mount with a rider is re-applied in place instead.
     */
    public static void registerReloadHook() {
        GettData.addReloadHook(() -> {
            try {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server == null) return;
                for (Map.Entry<UUID, Active> e : new ArrayList<>(ACTIVE.entrySet())) {
                    reload(server, e.getKey(), e.getValue());
                }
            } catch (RuntimeException e) {
                GettCore.LOGGER.error("gettcore: failed re-checking pets on reload", e);
            }
        });
    }

    private static void reload(MinecraftServer server, UUID owner, Active active) {
        Entity entity = findEntity(server, active.petId());
        if (!(entity instanceof Mob mob) || !(entity instanceof GettPet pet) || entity.isRemoved()) {
            recall(server, owner);
            return;
        }
        Optional<PetSpecies> now = species(active.key());
        boolean ridden = pet.kind() == PetSpecies.Kind.FLYING_MOUNT && mob.isVehicle();
        switch (reloadAction(active.species(), now, ridden)) {
            case KEEP -> {
                // species entry unchanged
            }
            case REAPPLY -> {
                pet.petState().reapplyStats();
                ACTIVE.put(owner, new Active(active.petId(), active.key(), now.orElse(null)));
            }
            case RECALL -> recall(server, owner);
        }
    }

    private static Optional<PetSpecies> species(PetKey key) {
        ResourceLocation id = ResourceLocation.tryParse(key.speciesId());
        return id == null ? Optional.empty() : GettData.get().pets().species(id);
    }

    private static boolean isBoss(PetKey key) {
        return species(key).map(PetSpecies::boss).orElse(false);
    }

    private static void whistle(ServerPlayer player) {
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), PetRegistry.WHISTLE.get(),
                SoundSource.PLAYERS, 1.0F, 1.0F);
    }
}
