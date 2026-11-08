package org.gettserver.gettcore.pet.boss;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.EventHooks;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;

/** Keeps every boss point populated: checked every {@link #CHECK_INTERVAL_TICKS} ticks from PetEvents. */
public final class BossSpawner {
    public static final int CHECK_INTERVAL_TICKS = 100;
    static final double PLAYER_RANGE = 128.0;
    static final int SPAWN_ATTEMPTS = 10;
    /** Flying bosses appear this many blocks above the ground. */
    static final int FLYING_SPAWN_HEIGHT = 8;
    /** Points whose bossType could not be spawned, warned once each until the next restart. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private BossSpawner() {}

    /**
     * Pure: a point below its maxCount spawns while a player is nearby once {@code respawnMinutes} passed since
     * the last boss was gone; a point that never lost a boss ({@code lastGoneGameTime < 0}) spawns right away.
     */
    public static boolean shouldSpawn(int alive, int maxCount, long lastGoneGameTime, long now, int respawnMinutes,
                                      boolean playerNearby) {
        if (alive >= maxCount || !playerNearby) return false;
        if (lastGoneGameTime < 0) return true;
        return now - lastGoneGameTime >= respawnMinutes * 60L * 20L;
    }

    public static void tick(MinecraftServer server) {
        List<BossPoint> points = GettData.get().bossPoints();
        if (points.isEmpty()) return;
        BossPointData data = BossPointData.get(server);
        long now = server.overworld().getGameTime();
        for (BossPoint p : points) tickPoint(server, data, p, now);
    }

    private static void tickPoint(MinecraftServer server, BossPointData data, BossPoint p, long now) {
        ServerLevel level = server.getLevel(p.dimension());
        if (level == null) return;
        BlockPos center = p.pos();
        // An unloaded center would make its bosses look missing (their chunks are not loaded either).
        if (!level.isPositionEntityTicking(center)) return;
        int alive = data.prune(p.id(), id -> {
            Entity e = level.getEntity(id);
            return e != null && e.isAlive();
        }, now);
        boolean nearby = level.hasNearbyAlivePlayer(center.getX() + 0.5, center.getY(), center.getZ() + 0.5,
                PLAYER_RANGE);
        while (shouldSpawn(alive, p.maxCount(), data.lastGone(p.id()), now, p.respawnMinutes(), nearby)) {
            if (!spawn(level, data, p)) return;
            alive++;
        }
    }

    private static boolean spawn(ServerLevel level, BossPointData data, BossPoint p) {
        Optional<EntityType<?>> type = EntityType.byString(p.bossType().toString());
        Entity created = type.isPresent() ? type.get().create(level) : null;
        if (!(created instanceof Mob mob) || !(created instanceof GettBoss boss)) {
            if (created != null) created.discard();
            if (WARNED.add(p.id())) {
                GettCore.LOGGER.warn("gettcore: boss point {} has bossType {} which is not a Gett boss", p.id(),
                        p.bossType());
            }
            return false;
        }
        Optional<BlockPos> at = pickSpawnPos(level, p.pos(), p.radius(), level.random);
        if (at.isEmpty()) {
            mob.discard();
            return false;
        }
        BlockPos pos = at.get();
        double y = pos.getY() + (mob instanceof FlyingAnimal ? FLYING_SPAWN_HEIGHT : 0);
        mob.moveTo(pos.getX() + 0.5, y, pos.getZ() + 0.5, level.random.nextFloat() * 360.0F, 0.0F);
        boss.bossState().setPointId(p.id());
        EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
        BossBehavior.apply(mob, BossBehavior.species(mob));
        UUID id = mob.getUUID();
        // Registered first: the join check (keepOnLoad) must already know this boss.
        data.registerAlive(p.id(), id);
        if (!level.addFreshEntity(mob)) {
            data.unregister(p.id(), id);
            return false;
        }
        GettCore.LOGGER.info("gettcore: boss point {} spawned {} at {} {} {}", p.id(), p.bossType(), pos.getX(),
                (int) y, pos.getZ());
        return true;
    }

    /**
     * A ground position (heightmap MOTION_BLOCKING_NO_LEAVES) within {@code radius} of {@code center}, not in or on a
     * liquid, in an already loaded chunk; {@value #SPAWN_ATTEMPTS} attempts.
     */
    public static Optional<BlockPos> pickSpawnPos(ServerLevel level, BlockPos center, int radius, RandomSource r) {
        radius = Math.max(1, radius);
        for (int n = 0; n < SPAWN_ATTEMPTS; n++) {
            int dx = r.nextInt(radius * 2 + 1) - radius;
            int dz = r.nextInt(radius * 2 + 1) - radius;
            if (dx * dx + dz * dz > radius * radius) continue;
            int x = center.getX() + dx;
            int z = center.getZ() + dz;
            if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y <= level.getMinBuildHeight()) continue;
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.below()).isEmpty()) continue;
            return Optional.of(pos);
        }
        return Optional.empty();
    }

    /**
     * A boss entering a level (chunk load): dropped when its point no longer exists, or when the point already has
     * its maxCount of other bosses (it was given up for missing while unloaded and replaced). Bosses without a
     * point (e.g. /summon) are kept, and so is everything before the data is first loaded.
     */
    public static boolean keepOnLoad(MinecraftServer server, Entity entity, GettBoss boss) {
        String pointId = boss.pointId();
        if (pointId.isEmpty() || !GettData.get().loaded()) return true;
        Optional<BossPoint> point = BossBehavior.point(pointId);
        if (point.isEmpty()) return false; // orphan: its point was removed
        BossPointData data = BossPointData.get(server);
        List<UUID> alive = data.alive(pointId);
        if (alive.contains(entity.getUUID())) return true;
        if (alive.size() >= point.get().maxCount()) return false;
        data.registerAlive(pointId, entity.getUUID());
        return true;
    }
}
