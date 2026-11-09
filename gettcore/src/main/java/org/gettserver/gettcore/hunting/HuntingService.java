package org.gettserver.gettcore.hunting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.hunting.HuntingConfig.Entry;
import org.gettserver.gettcore.hunting.HuntingConfig.Zone;

/**
 * Hunting zones at runtime (server thread only): keeps each zone topped up while a player is near, applies variant
 * stats, tracks loaded variants, removes variants of deleted zones and variants that strayed far outside.
 */
public final class HuntingService {
    /** Tick interval of {@link #tick}. */
    public static final int CHECK_INTERVAL_TICKS = 20;
    static final int SPAWN_ATTEMPTS = 12;

    /** A loaded variant: its zone, whether it is a test spawn, the level it is in. */
    record Tracked(String zone, boolean test, ResourceKey<Level> dimension) {}

    private static final Map<UUID, Tracked> TRACKED = new HashMap<>();
    /** Zone id -> game time of its last refill. */
    private static final Map<String, Long> LAST_REFILL = new HashMap<>();
    /** Zone/entry pairs whose entity could not be spawned, warned once each until the next restart. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    /** Variants that joined before GettData was loaded; checked on the first tick after (see {@link PendingJoins}). */
    private static final PendingJoins PENDING = new PendingJoins();

    private HuntingService() {}

    public static void clear() {
        PENDING.clear();
        TRACKED.clear();
        LAST_REFILL.clear();
    }

    /** Called when a variant joins a level. False = drop it (zone gone, or zone full for one loaded from disk). */
    static boolean onJoin(Entity entity, VariantTag tag, boolean loadedFromDisk) {
        Optional<Zone> zone = GettData.get().hunting().zone(tag.zone());
        if (loadedFromDisk && !HuntingRules.keepOnLoad(zone.isPresent(), tag.test(), alive(tag.zone()),
                zone.map(Zone::maxAlive).orElse(0))) {
            return false;
        }
        TRACKED.put(entity.getUUID(), new Tracked(tag.zone(), tag.test(), entity.level().dimension()));
        return true;
    }

    /** A variant joined before the data was loaded: decided later by {@link #joinPending}. */
    static void deferJoin(Entity entity, boolean loadedFromDisk) {
        PENDING.add(entity.getUUID(), entity.level().dimension(), loadedFromDisk);
    }

    static void onLeave(Entity entity) {
        PENDING.remove(entity.getUUID());
        TRACKED.remove(entity.getUUID());
    }

    /** Runs the deferred joins through {@link #onJoin} now that the zones are known; rejected ones are discarded. */
    private static void joinPending(MinecraftServer server) {
        for (PendingJoins.Pending p : PENDING.drain()) {
            ServerLevel level = server.getLevel(p.dimension());
            Entity e = level == null ? null : level.getEntity(p.id());
            if (e == null || !e.isAlive() || TRACKED.containsKey(p.id())) continue;
            Optional<VariantTag> tag = VariantTag.read(e.getPersistentData());
            if (tag.isPresent() && !onJoin(e, tag.get(), p.loadedFromDisk())) e.discard();
        }
    }

    /** Loaded zone variants of {@code zone} (test spawns not counted). */
    static int alive(String zone) {
        int n = 0;
        for (Tracked t : TRACKED.values()) if (!t.test() && t.zone().equals(zone)) n++;
        return n;
    }

    public static void tick(MinecraftServer server) {
        HuntingConfig cfg = GettData.get().hunting();
        joinPending(server);
        sweep(server, cfg);
        long now = server.overworld().getGameTime();
        LAST_REFILL.keySet().removeIf(id -> cfg.zone(id).isEmpty());
        for (Zone z : cfg.zones()) refill(server, z, now);
    }

    /** Drops tracked entries that are gone; discards variants of removed zones and zone variants that strayed. */
    private static void sweep(MinecraftServer server, HuntingConfig cfg) {
        for (Map.Entry<UUID, Tracked> en : List.copyOf(TRACKED.entrySet())) {
            Tracked t = en.getValue();
            ServerLevel level = server.getLevel(t.dimension());
            Entity e = level == null ? null : level.getEntity(en.getKey());
            if (e == null || !e.isAlive()) {
                TRACKED.remove(en.getKey());
                continue;
            }
            Optional<Zone> zone = cfg.zone(t.zone());
            boolean strayed = zone.isPresent() && !t.test()
                    && (!zone.get().dimension().equals(t.dimension())
                    || HuntingRules.strayed(zone.get(), e.getX(), e.getY(), e.getZ()));
            if (zone.isEmpty() || strayed) {
                TRACKED.remove(en.getKey());
                e.discard();
            }
        }
    }

    private static void refill(MinecraftServer server, Zone z, long now) {
        ServerLevel level = server.getLevel(z.dimension());
        if (level == null) return;
        boolean near = false;
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator() && HuntingRules.nearby(z, level.dimension(), p.getX(), p.getY(), p.getZ())) {
                near = true;
                break;
            }
        }
        Long last = LAST_REFILL.get(z.id());
        double seconds = last == null ? Double.MAX_VALUE : (now - last) / 20.0;
        if (!HuntingRules.shouldRefill(alive(z.id()), z.maxAlive(), near, seconds, z.refillSeconds())) return;
        LAST_REFILL.put(z.id(), now);
        OptionalInt pick = HuntingRules.pickEntry(z.entries().stream().map(Entry::weight).toList(),
                level.random.nextDouble());
        if (pick.isEmpty()) return;
        Entry entry = z.entries().get(pick.getAsInt());
        Optional<Mob> mob = create(level, z, entry, false);
        if (mob.isEmpty()) return;
        Optional<BlockPos> at = pickSpawnPos(level, z, mob.get(), level.random);
        if (at.isEmpty()) {
            mob.get().discard();
            return;
        }
        BlockPos pos = at.get();
        spawn(level, mob.get(), entry, new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5),
                level.random.nextFloat() * 360.0F);
    }

    /** A fresh, not yet added mob of the entry's type carrying the variant tag, or empty if the type is no mob. */
    private static Optional<Mob> create(ServerLevel level, Zone z, Entry entry, boolean test) {
        Optional<EntityType<?>> type = EntityType.byString(entry.entity().toString());
        Entity created = type.isPresent() ? type.get().create(level) : null;
        if (!(created instanceof Mob mob)) {
            if (created != null) created.discard();
            if (WARNED.add(z.id() + "/" + entry.name())) {
                GettCore.LOGGER.warn("gettcore: hunting zone {} entry {} has entity {} which is not a mob", z.id(),
                        entry.name(), entry.entity());
            }
            return Optional.empty();
        }
        VariantTag.of(z.id(), entry, test).write(mob.getPersistentData());
        return Optional.of(mob);
    }

    private static boolean spawn(ServerLevel level, Mob mob, Entry entry, Vec3 pos, float yaw) {
        mob.moveTo(pos.x, pos.y, pos.z, yaw, 0.0F);
        EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.EVENT,
                null);
        applyVariant(mob, entry);
        return level.addFreshEntity(mob);
    }

    /**
     * Variant stats (absolute): max health (and full health), attack (only if the mob has the attribute), movement
     * speed, armor, scale; the entry name above the head (shown when looked at), never despawns.
     */
    static void applyVariant(Mob mob, Entry e) {
        setBase(mob, Attributes.MAX_HEALTH, e.health());
        mob.setHealth(mob.getMaxHealth());
        setBase(mob, Attributes.ATTACK_DAMAGE, e.attack());
        setBase(mob, Attributes.MOVEMENT_SPEED, e.speed());
        setBase(mob, Attributes.ARMOR, e.armor());
        setBase(mob, Attributes.SCALE, e.scale());
        mob.setCustomName(Component.literal(e.name()));
        mob.setCustomNameVisible(false);
        mob.setPersistenceRequired();
    }

    private static void setBase(Mob mob, Holder<Attribute> attribute, double value) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(value);
    }

    /** /사냥터 소환: one test variant 3 blocks in front of the player (anywhere). */
    public static boolean spawnTest(ServerPlayer player, Zone z, Entry entry) {
        ServerLevel level = player.serverLevel();
        Optional<Mob> mob = create(level, z, entry, true);
        if (mob.isEmpty()) return false;
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        flat = flat.lengthSqr() < 1.0E-4 ? Vec3.ZERO : flat.normalize().scale(3);
        Vec3 at = player.position().add(flat);
        if (!spawn(level, mob.get(), entry, at, player.getYRot() + 180.0F)) return false;
        GettCore.LOGGER.info("gettcore: {} test-spawned hunting variant {}/{}", player.getGameProfile().getName(),
                z.id(), entry.name());
        return true;
    }

    /**
     * A position inside the zone a mob can stand on: a random column in a loaded chunk, then a random y in it whose
     * block below has a sturdy top, with room for the mob and no liquid; {@value #SPAWN_ATTEMPTS} attempts.
     */
    static Optional<BlockPos> pickSpawnPos(ServerLevel level, Zone z, Mob mob, RandomSource r) {
        BlockPos min = z.min();
        BlockPos max = z.max();
        for (int n = 0; n < SPAWN_ATTEMPTS; n++) {
            int x = min.getX() + r.nextInt(max.getX() - min.getX() + 1);
            int zz = min.getZ() + r.nextInt(max.getZ() - min.getZ() + 1);
            if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(zz))) continue;
            int lo = Math.max(min.getY(), level.getMinBuildHeight() + 1);
            int hi = Math.min(max.getY(), level.getMaxBuildHeight() - 1);
            List<Integer> ys = new ArrayList<>();
            for (int y = lo; y <= hi; y++) {
                BlockPos pos = new BlockPos(x, y, zz);
                BlockPos below = pos.below();
                if (!level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP)) continue;
                if (!level.getFluidState(pos).isEmpty()) continue;
                AABB box = mob.getType().getSpawnAABB(x + 0.5, y, zz + 0.5);
                if (!level.noCollision(box)) continue;
                ys.add(y);
            }
            if (!ys.isEmpty()) return Optional.of(new BlockPos(x, ys.get(r.nextInt(ys.size())), zz));
        }
        return Optional.empty();
    }
}
