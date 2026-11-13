package org.gettserver.gettcore.skill;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.protect.ProtectConfig;
import org.jetbrains.annotations.Nullable;

/** World side effects of skills: delayed actions (aftershocks) and terrain. */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class SkillWorld {
    /** Blocks set to air per dimension per tick; the rest waits in the queue. */
    public static final int CARVE_BUDGET_PER_TICK = 400;
    /** Removed blocks thrown up as debris, per {@link #carve} call. */
    static final int DEBRIS_PER_CARVE = 16;

    /** Pending actions per dimension; cleared when the server stops (singleplayer reuses the JVM). */
    private static final Map<ResourceKey<Level>, List<Pending>> QUEUES = new HashMap<>();
    /** Blocks waiting to be dug out, per dimension; cleared with {@link #QUEUES}. */
    private static final Map<ResourceKey<Level>, CarveQueue> CARVES = new HashMap<>();

    private SkillWorld() {}

    /** One {@link #carve} call: its positions in order, how far it got, how much debris it may still throw. */
    private static final class CarveJob {
        final long[] positions;
        final Vec3 center;
        int next;
        int debrisLeft = DEBRIS_PER_CARVE;

        CarveJob(long[] positions, Vec3 center) {
            this.positions = positions;
            this.center = center;
        }
    }

    private static final class CarveQueue {
        final ArrayDeque<CarveJob> jobs = new ArrayDeque<>();
        /** Every position queued and not taken yet, so overlapping shapes are dug once. */
        final LongOpenHashSet pending = new LongOpenHashSet();
    }

    private static final class Pending {
        int ticksLeft;
        final Runnable action;

        Pending(int ticksLeft, Runnable action) {
            this.ticksLeft = ticksLeft;
            this.action = action;
        }
    }

    /** Runs {@code action} at the end of the server tick {@code delayTicks} ticks from now (0 = this tick). */
    public static void later(ServerLevel level, int delayTicks, Runnable action) {
        QUEUES.computeIfAbsent(level.dimension(), k -> new ArrayList<>()).add(new Pending(Math.max(0, delayTicks), action));
    }

    /**
     * Digs out the given blocks over the next ticks ({@link #CARVE_BUDGET_PER_TICK} per dimension per tick), skipping
     * unloaded chunks and every block {@link TerrainRules#breakable} refuses. Nothing drops, nothing is restored.
     */
    public static void carve(ServerLevel level, List<BlockPos> positions) {
        if (positions.isEmpty()) return;
        CarveQueue queue = CARVES.computeIfAbsent(level.dimension(), k -> new CarveQueue());
        LongArrayList fresh = new LongArrayList(positions.size());
        double x = 0, y = 0, z = 0;
        for (BlockPos p : positions) {
            x += p.getX();
            y += p.getY();
            z += p.getZ();
            long key = p.asLong();
            if (queue.pending.add(key)) fresh.add(key);
        }
        if (fresh.isEmpty()) return;
        int n = positions.size();
        queue.jobs.add(new CarveJob(fresh.toLongArray(), new Vec3(x / n + 0.5, y / n + 0.5, z / n + 0.5)));
    }

    /**
     * Digs the given blocks right now (same exclusions as {@link #carve}) and returns how many were removed; at most
     * {@code maxDebris} of them are thrown up as debris away from {@code from}. For a body that has to walk through
     * the hole this very tick: the queued {@link #carve} would still leave the wall standing.
     */
    public static int carveNow(ServerLevel level, List<BlockPos> positions, Vec3 from, int maxDebris) {
        if (positions.isEmpty() || !GettData.get().loaded()) return 0;
        ProtectConfig protect = GettData.get().protect();
        int removedCount = 0;
        int debrisLeft = maxDebris;
        for (BlockPos p : positions) {
            try {
                BlockState removed = dig(level, protect, p);
                if (removed == null) continue;
                removedCount++;
                if (debrisLeft-- > 0) SkillFx.debris(level, p, removed, from);
            } catch (RuntimeException e) {
                GettCore.LOGGER.error("gettcore: skill terrain carve failed at {}", p, e);
            }
        }
        return removedCount;
    }

    /** The level as {@link WalkCarve.Blocks}; a block in an unloaded chunk counts as neither solid nor light. */
    public static WalkCarve.Blocks walkBlocks(ServerLevel level) {
        return new WalkCarve.Blocks() {
            private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

            @Override
            public boolean solid(int x, int y, int z) {
                pos.set(x, y, z);
                return level.hasChunkAt(pos) && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
            }

            @Override
            public boolean light(int x, int y, int z) {
                pos.set(x, y, z);
                if (!level.hasChunkAt(pos)) return false;
                BlockState state = level.getBlockState(pos);
                return state.getCollisionShape(level, pos).isEmpty() && !state.isAir() && state.getFluidState().isEmpty();
            }
        };
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        runLater(event);
        runCarves(event);
    }

    private static void runLater(ServerTickEvent.Post event) {
        if (QUEUES.isEmpty()) return;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            // Taken out first: an action may queue another one, which lands in a fresh list.
            List<Pending> queue = QUEUES.remove(level.dimension());
            if (queue == null) continue;
            List<Pending> waiting = new ArrayList<>();
            for (Pending p : queue) {
                if (p.ticksLeft-- > 0) {
                    waiting.add(p);
                    continue;
                }
                try {
                    p.action.run();
                } catch (RuntimeException e) {
                    GettCore.LOGGER.error("gettcore: delayed skill action failed", e);
                }
            }
            if (!waiting.isEmpty()) QUEUES.computeIfAbsent(level.dimension(), k -> new ArrayList<>()).addAll(waiting);
        }
    }

    private static void runCarves(ServerTickEvent.Post event) {
        // Regions are unknown until the data is loaded: nothing is dug before then.
        if (CARVES.isEmpty() || !GettData.get().loaded()) return;
        ProtectConfig protect = GettData.get().protect();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            CarveQueue queue = CARVES.get(level.dimension());
            if (queue == null) continue;
            int budget = CARVE_BUDGET_PER_TICK;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            while (budget > 0 && !queue.jobs.isEmpty()) {
                CarveJob job = queue.jobs.peek();
                while (budget > 0 && job.next < job.positions.length) {
                    long key = job.positions[job.next++];
                    queue.pending.remove(key);
                    budget--;
                    pos.set(key);
                    try {
                        BlockState removed = dig(level, protect, pos);
                        if (removed != null && job.debrisLeft > 0) {
                            job.debrisLeft--;
                            SkillFx.debris(level, pos.immutable(), removed, job.center);
                        }
                    } catch (RuntimeException e) {
                        GettCore.LOGGER.error("gettcore: skill terrain carve failed at {}", pos, e);
                    }
                }
                if (job.next >= job.positions.length) queue.jobs.poll();
            }
            if (queue.jobs.isEmpty()) CARVES.remove(level.dimension());
        }
    }

    /** Sets the block to air (a waterlogged one to its fluid) when allowed; the removed state, else null. */
    private static @Nullable BlockState dig(ServerLevel level, ProtectConfig protect, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return null;
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof LiquidBlock) return null;
        boolean ok = TerrainRules.breakable(state.isAir(), state.getDestroySpeed(level, pos),
                state.getBlock().getExplosionResistance(), state.hasBlockEntity(),
                protect.regionAt(level.dimension(), pos).map(ProtectConfig.Region::flags));
        if (!ok) return null;
        FluidState fluid = state.getFluidState();
        level.setBlock(pos, fluid.isEmpty() ? Blocks.AIR.defaultBlockState() : fluid.createLegacyBlock(), 3);
        return state;
    }

    /** Debris left in a chunk by a crash or a stop mid-flight is thrown away when the chunk loads again. */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !event.loadedFromDisk()) return;
        if (!event.getEntity().getTags().contains(SkillFx.DEBRIS_TAG)) return;
        event.setCanceled(true);
        event.getEntity().discard();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        QUEUES.clear();
        CARVES.clear();
    }
}
