package org.gettserver.gettcore.detector.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.detector.OutlineEdges;
import org.gettserver.gettcore.network.DetectorHighlightPayload;

/**
 * 클라 전용: 받은 윤곽선 묶음을 보관하고, 만료·캐서 없어진 블록을 정리하며, 그릴 선분을 캐시한다.
 * 시간은 모두 클라 레벨의 게임 시각이다.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class DetectorHighlights {
    private static final int CHECK_INTERVAL = 5;
    private static final int[] NO_EDGES = new int[0];

    private static final HighlightStore STORE = new HighlightStore();
    private static final BlockPos.MutableBlockPos CURSOR = new BlockPos.MutableBlockPos();
    private static int[] edges = NO_EDGES;
    private static ClientLevel trackedLevel;

    private DetectorHighlights() {}

    public static void apply(DetectorHighlightPayload p, long gameTime) {
        STORE.apply(p.key(), p.positions(), p.ticks(), gameTime);
        rebuild();
    }

    /** 캐시된 선분(정수 격자 좌표, 선분당 6개 int). 없으면 빈 배열. */
    static int[] edges() {
        return edges;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != trackedLevel) {
            trackedLevel = level;
            reset();
        }
        if (level == null || STORE.isEmpty() || level.getGameTime() % CHECK_INTERVAL != 0) return;
        boolean changed = STORE.expire(level.getGameTime());
        changed |= STORE.remove(pos -> {
            CURSOR.set(pos);
            return level.hasChunkAt(CURSOR) && level.getBlockState(CURSOR).isAir();
        });
        if (changed) rebuild();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        trackedLevel = null;
        reset();
    }

    private static void reset() {
        STORE.clear();
        edges = NO_EDGES;
    }

    private static void rebuild() {
        edges = STORE.isEmpty() ? NO_EDGES : OutlineEdges.of(STORE.union());
    }
}
