package org.gettserver.gettcore.detector;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.enchant.EnchantConfig;
import org.gettserver.gettcore.network.DetectorHighlightPayload;

/** 탐지기 아이템의 탐지 세션과 곡괭이 "광물 탐지" 능력의 발동. 표시는 쓴 사람에게만 보낸다. */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class DetectorService {
    private static final int RESCAN_TICKS = 20;

    /** 시작 시 값(범위·상한)을 그대로 쓴다 — 세션 중 설정 리로드는 영향 없음. lastSent[0] 은 마지막으로 보낸 위치들. */
    private record Session(Set<Block> blocks, ResourceKey<Level> dimension, long endTick, int range, int maxBlocks,
                           long[][] lastSent) {}

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final DetectorCooldowns COOLDOWNS = new DetectorCooldowns();

    private DetectorService() {}

    public static void use(ServerPlayer player, ItemStack detector) {
        DetectorConfig config = GettData.get().detector();
        ItemStack target = DetectorItem.target(detector);
        if (target.isEmpty()) {
            actionBar(player, Component.translatable("gettcore.detector.empty"));
            return;
        }
        Optional<DetectorTargets.Resolved> resolved = DetectorTargets.forStack(config, target);
        if (resolved.isEmpty()) {
            actionBar(player, Component.translatable("gettcore.detector.unknown"));
            return;
        }
        long now = now(player);
        long remaining = COOLDOWNS.remainingTicks(player.getUUID(), DetectorHighlightPayload.DETECTOR_KEY, now);
        if (remaining > 0) {
            actionBar(player, Component.translatable("gettcore.detector.cooldown", (remaining + 19) / 20));
            return;
        }

        long cooldownTicks = (long) config.cooldownSeconds() * 20;
        long durationTicks = (long) config.durationSeconds() * 20;
        Session session = new Session(DetectorTargets.blocks(resolved.get()), player.level().dimension(),
                now + durationTicks, config.range(), config.maxBlocks(), new long[1][]);
        SESSIONS.put(player.getUUID(), session);
        scanAndSend(player, session, now, true);
        showDetecting(player, (int) Math.max(1, Math.min(Integer.MAX_VALUE, durationTicks)));

        COOLDOWNS.start(player.getUUID(), DetectorHighlightPayload.DETECTOR_KEY, now, cooldownTicks);
        if (cooldownTicks > 0) player.getCooldowns().addCooldown(GettCore.DETECTOR.get(), (int) cooldownTicks);
        chime(player);
    }

    public static void prospect(ServerPlayer player, ServerLevel level, BlockPos broken, BlockState state, int lvl) {
        DetectorConfig config = GettData.get().detector();
        Optional<DetectorTargets.Resolved> resolved = DetectorTargets.forOreState(config, state);
        if (resolved.isEmpty()) return;
        String key = "ore:" + resolved.get().key();
        long now = now(player);
        if (COOLDOWNS.remainingTicks(player.getUUID(), key, now) > 0) return;

        EnchantConfig enchant = GettData.get().enchant();
        int range = prospectRange(enchant.prospectRange().get(lvl));
        long[] found = DetectorScan.find(level, broken, range, DetectorTargets.blocks(resolved.get()),
                config.maxBlocks(), broken);
        // 주변에 없어도 쿨타임은 돈다.
        COOLDOWNS.start(player.getUUID(), key, now, Math.round(enchant.prospectCooldownSeconds().get(lvl) * 20));
        if (found.length == 0) return;
        int displayTicks = prospectTicks(enchant.prospectDurationSeconds().get(lvl));
        PacketDistributor.sendToPlayer(player, new DetectorHighlightPayload(key, found, displayTicks));
        showDetecting(player, displayTicks);
        chime(player);
    }

    /** 남은 표시 시간을 HUD 에 보이는 효과. 더 짧은 새 적용이 긴 기존 효과를 줄이지 않는다(addEffect 가 긴 쪽 유지). */
    private static void showDetecting(ServerPlayer player, int ticks) {
        player.addEffect(new MobEffectInstance(GettCore.DETECTING, ticks, 0, false, false, true));
    }

    /** 관리자 명령용: 이 플레이어의 탐지 쿨타임(탐지기·광물별)과 아이템 쿨타임 표시를 모두 지운다. 세션은 유지. */
    public static void resetCooldowns(ServerPlayer player) {
        COOLDOWNS.forget(player.getUUID());
        player.getCooldowns().removeCooldown(GettCore.DETECTOR.get());
    }

    /** 설정값이 무엇이든 탐지기 자체 범위와 같은 1..64 로 제한한다. */
    static int prospectRange(double configured) {
        return (int) Math.max(1, Math.min(64, configured));
    }

    /** 0 이하면 클라이언트가 표시를 지우므로 최소 1틱, 최대 1시간. */
    static int prospectTicks(double seconds) {
        return (int) Math.max(1, Math.min(72000, Math.round(seconds * 20)));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (SESSIONS.isEmpty()) return;
        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();
        if (now % RESCAN_TICKS != 0) return;
        Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Session> e = it.next();
            Session s = e.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null || now >= s.endTick() || !player.level().dimension().equals(s.dimension())) {
                it.remove();
                continue;
            }
            scanAndSend(player, s, now, false);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) endSession(player);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        endSession(player);
        reapplyDetectorCooldown(player);
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        SESSIONS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // 세션은 저장되지 않으므로, 로그아웃·서버 종료 전에 걸려 있던 "탐지 중" 효과만 남아 있으면 지운다.
        player.removeEffect(GettCore.DETECTING);
        reapplyDetectorCooldown(player);
    }

    private static void reapplyDetectorCooldown(ServerPlayer player) {
        long remaining = COOLDOWNS.remainingTicks(player.getUUID(), DetectorHighlightPayload.DETECTOR_KEY, now(player));
        if (remaining > 0) player.getCooldowns().addCooldown(GettCore.DETECTOR.get(), (int) remaining);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SESSIONS.clear();
        COOLDOWNS.clear();
    }

    private static void endSession(ServerPlayer player) {
        SESSIONS.remove(player.getUUID());
        player.removeEffect(GettCore.DETECTING);
        PacketDistributor.sendToPlayer(player, new DetectorHighlightPayload("", new long[0], 0));
    }

    private static void scanAndSend(ServerPlayer player, Session s, long now, boolean force) {
        long[] positions = DetectorScan.find(player.serverLevel(), player.blockPosition(), s.range(), s.blocks(),
                s.maxBlocks(), null);
        if (!force && Arrays.equals(positions, s.lastSent()[0])) return;
        s.lastSent()[0] = positions;
        PacketDistributor.sendToPlayer(player, new DetectorHighlightPayload(DetectorHighlightPayload.DETECTOR_KEY,
                positions, (int) Math.max(1, s.endTick() - now)));
    }

    private static long now(ServerPlayer player) {
        return player.server.overworld().getGameTime();
    }

    private static void actionBar(ServerPlayer player, Component message) {
        player.displayClientMessage(message, true);
    }

    private static void chime(ServerPlayer player) {
        // Only the user hears it (playSound(null, ...) would reach everyone nearby).
        player.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.0F);
    }
}
