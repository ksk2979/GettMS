package org.gettserver.gettcore.skill;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.gettserver.gettcore.GettCore;

/** World side effects of skills: delayed actions (aftershocks) and terrain. */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class SkillWorld {
    /** Pending actions per dimension; cleared when the server stops (singleplayer reuses the JVM). */
    private static final Map<ResourceKey<Level>, List<Pending>> QUEUES = new HashMap<>();

    private SkillWorld() {}

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

    /** Digs out the given blocks. Not implemented yet: a no-op. */
    public static void carve(ServerLevel level, List<BlockPos> positions) {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
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

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        QUEUES.clear();
    }
}
