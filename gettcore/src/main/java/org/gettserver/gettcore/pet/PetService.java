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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.network.PetDexSyncPayload;
import org.gettserver.gettcore.pet.entity.GettPet;
import org.gettserver.gettcore.pet.entity.PetBehavior;
import org.jetbrains.annotations.Nullable;

/** Summoning, recalling and auto-returning pets; each player's active pet entity is tracked in memory. */
public final class PetService {
    public enum SummonResult { SUMMONED, RECALLED, BLOCKED_ACTIVE, INVALID_INDEX }

    /** Pets appear this many blocks in front of the owner (horizontally). */
    static final double SUMMON_DISTANCE = 2.0;

    /** Owner UUID to the UUID of the summoned pet entity. Server thread only. */
    private static final Map<UUID, UUID> ACTIVE = new HashMap<>();

    private PetService() {}

    /** What a click on dex entry {@code index} does. */
    public static SummonResult decide(List<PetKey> owned, Optional<PetKey> active, int index) {
        if (index < 0 || index >= owned.size()) return SummonResult.INVALID_INDEX;
        if (active.isPresent() && active.get().equals(owned.get(index))) return SummonResult.RECALLED;
        if (active.isPresent()) return SummonResult.BLOCKED_ACTIVE;
        return SummonResult.SUMMONED;
    }

    public static void handleAction(ServerPlayer player, int index) {
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
        ACTIVE.put(player.getUUID(), created.getUUID());
        if (!level.addFreshEntity(created)) {
            ACTIVE.remove(player.getUUID());
            GettCore.LOGGER.warn("gettcore: level refused pet entity {} for {}", key, player.getGameProfile().getName());
            unknown(player);
            return;
        }
        PetData.get(player.server).setActive(player.getUUID(), key);
        whistle(player);
        player.sendSystemMessage(Component.translatable("gettcore.msg.pet.summoned",
                CapturedMobItem.displayName(key, boss)).withStyle(ChatFormatting.GREEN));
        sync(player);
    }

    private static void unknown(ServerPlayer player) {
        player.sendSystemMessage(Component.translatable("gettcore.msg.pet.unknown").withStyle(ChatFormatting.RED));
    }

    /** Two blocks in front of the owner (horizontal look direction), lifted to the surface if that spot is solid. */
    private static Vec3 summonPos(ServerPlayer player, ServerLevel level) {
        Vec3 dir = Vec3.directionFromRotation(0.0F, player.getYRot());
        Vec3 pos = player.position().add(dir.scale(SUMMON_DISTANCE));
        BlockPos block = BlockPos.containing(pos);
        if (!level.getBlockState(block).getCollisionShape(level, block).isEmpty()) {
            BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, block);
            pos = new Vec3(pos.x, top.getY(), pos.z);
        }
        return pos;
    }

    /** Puts the active pet back: discards its entity and clears active; whistle + message unless silent. */
    public static void recall(ServerPlayer player, boolean silent) {
        Optional<PetKey> active = PetData.get(player.server).active(player.getUUID());
        clear(player.server, player.getUUID());
        if (!silent) {
            whistle(player);
            active.ifPresent(key -> player.sendSystemMessage(Component.translatable("gettcore.msg.pet.recalled",
                    CapturedMobItem.displayName(key, isBoss(key))).withStyle(ChatFormatting.GREEN)));
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
        UUID petId = ACTIVE.remove(owner);
        if (petId != null) {
            Entity pet = findEntity(server, petId);
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
        for (Map.Entry<UUID, UUID> e : new ArrayList<>(ACTIVE.entrySet())) {
            Entity pet = findEntity(server, e.getValue());
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
        return entity.getUUID().equals(ACTIVE.get(owner));
    }

    /** Registered once at startup: /gett reload recalls every summoned pet (species config may have changed). */
    public static void registerReloadHook() {
        GettData.addReloadHook(() -> {
            try {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server == null) return;
                for (UUID owner : new ArrayList<>(ACTIVE.keySet())) {
                    recall(server, owner);
                }
            } catch (RuntimeException e) {
                GettCore.LOGGER.error("gettcore: failed recalling pets on reload", e);
            }
        });
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
