package org.gettserver.gettcore.web;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.network.WebFirePayload;
import org.gettserver.gettcore.network.WebRollPayload;
import org.gettserver.gettcore.network.WebSyncPayload;

/**
 * 거미줄 발사기의 서버 쪽. 이동(진자)은 클라이언트가 계산하고, 서버는 붙일 수 있는지 판정하고 상태를 주변에 알리며
 * 낙하 피해·공중 체류 강퇴를 막는다.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class WebService {
    /** 줄을 놓은 뒤 이 시간 안의 착지는 줄을 쓴 착지로 본다(낙하 피해 감소·구르기). */
    static final int LANDING_GRACE_TICKS = 200;
    /** 클라이언트가 보낸 점과 사거리 사이에 봐주는 오차(칸). */
    private static final double RANGE_SLACK = 3.0;

    /** One player's web: where it sticks, when it was fired and how long the tip flies. */
    private record Web(Vec3 anchor, BlockPos block, long start, int flight, boolean offhand) {
        boolean attached(long now) {
            return now >= start + flight;
        }
    }

    private static final Map<UUID, Web> WEBS = new ConcurrentHashMap<>();
    /** Game time of the last tick a player had a web (for the landing grace). */
    private static final Map<UUID, Long> LAST_USE = new ConcurrentHashMap<>();
    /** Game time of the last shot (for the cooldown). */
    private static final Map<UUID, Long> LAST_SHOT = new ConcurrentHashMap<>();

    /** Players the client reported as clinging to a wall. */
    private static final java.util.Set<UUID> CLINGING = ConcurrentHashMap.newKeySet();

    private WebService() {}

    private static boolean holdsAny(Player player) {
        return holds(player, false) || holds(player, true);
    }

    /** A block the player could be holding on to: something solid right next to the body. */
    private static boolean nearWall(ServerPlayer player) {
        return !player.level().noCollision(player, player.getBoundingBox().inflate(0.3, 0, 0.3));
    }

    public static void cling(ServerPlayer player, boolean on) {
        boolean ok = on && GettData.get().web().wallCling() && player.isAlive() && !player.isSpectator()
                && holdsAny(player) && nearWall(player);
        boolean changed = ok ? CLINGING.add(player.getUUID()) : CLINGING.remove(player.getUUID());
        if (changed) {
            PacketDistributor.sendToPlayersTrackingEntity(player,
                    new org.gettserver.gettcore.network.WebClingSyncPayload(player.getId(), ok));
        }
    }

    private static boolean holds(Player player, boolean offhand) {
        return player.getItemInHand(offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND)
                .is(GettCore.WEB_SHOOTER.get());
    }

    public static void fire(ServerPlayer player, WebFirePayload p) {
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        WebConfig cfg = GettData.get().web();
        Vec3 target = new Vec3(p.x(), p.y(), p.z());
        Vec3 eye = player.getEyePosition();
        double distance = eye.distanceTo(target);
        Long last = LAST_SHOT.get(player.getUUID());
        boolean ok = player.isAlive() && !player.isSpectator() && holds(player, p.offhand())
                && Double.isFinite(distance) && distance <= cfg.range() + RANGE_SLACK
                && (last == null || now - last >= Math.round(cfg.cooldownSeconds() * 20));
        if (ok && p.hit()) {
            // The point must lie on a block that is really there (loaded, not air).
            ok = level.isLoaded(p.block()) && !level.getBlockState(p.block()).isAir()
                    && new AABB(p.block()).inflate(0.2).contains(target);
        }
        if (!ok) {
            // Cancels the client's prediction.
            PacketDistributor.sendToPlayer(player, WebSyncPayload.gone(player.getId()));
            return;
        }
        LAST_SHOT.put(player.getUUID(), now);
        int flight = WebRules.flightTicks(distance, cfg.shotSpeed());
        if (p.hit()) {
            WEBS.put(player.getUUID(), new Web(target, p.block().immutable(), now, flight, p.offhand()));
            LAST_USE.put(player.getUUID(), now);
        } else {
            WEBS.remove(player.getUUID());
        }
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new WebSyncPayload(player.getId(), true,
                p.hit(), p.offhand(), target.x, target.y, target.z, flight));
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FISHING_BOBBER_THROW,
                SoundSource.PLAYERS, 0.6f, 1.7f);
    }

    /** Counts as web use now: the next landing within the grace gets the web landing rules. */
    static void markUse(ServerPlayer player) {
        LAST_USE.put(player.getUUID(), player.serverLevel().getGameTime());
    }

    public static void release(ServerPlayer player) {
        if (WEBS.remove(player.getUUID()) != null) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, WebSyncPayload.gone(player.getId()));
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (CLINGING.contains(player.getUUID())) {
            if (player.isAlive() && !player.isSpectator() && holdsAny(player) && nearWall(player)) {
                LAST_USE.put(player.getUUID(), player.serverLevel().getGameTime());
                player.resetFallDistance();
                player.connection.aboveGroundTickCount = 0;
            } else {
                cling(player, false);
            }
        }
        Web web = WEBS.get(player.getUUID());
        if (web == null) return;
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        WebConfig cfg = GettData.get().web();
        boolean keep = player.isAlive() && !player.isSpectator() && holds(player, web.offhand())
                && player.position().distanceTo(web.anchor()) <= cfg.range() * 2 + 16;
        if (keep && now % 10 == 0) {
            keep = level.isLoaded(web.block()) && !level.getBlockState(web.block()).isAir();
        }
        if (!keep) {
            release(player);
            return;
        }
        LAST_USE.put(player.getUUID(), now);
        if (web.attached(now)) {
            // Hanging is neither falling nor flying: no fall damage builds up, no "flying is not enabled" kick.
            player.resetFallDistance();
            player.connection.aboveGroundTickCount = 0;
        }
    }

    /** 줄을 쓴 직후의 착지: 낙하 피해를 줄이고, 충분히 높았으면 구르기 동작을 보낸다. */
    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Long last = LAST_USE.remove(player.getUUID());
        if (last == null || player.serverLevel().getGameTime() - last > LANDING_GRACE_TICKS) return;
        WebConfig cfg = GettData.get().web();
        event.setDamageMultiplier(event.getDamageMultiplier() * cfg.landingDamagePercent() / 100f);
        if (event.getDistance() >= cfg.rollMinFall()) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new WebRollPayload(player.getId()));
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            release(player);
            cling(player, false);
        }
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) release(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        WEBS.remove(id);
        CLINGING.remove(id);
        LAST_USE.remove(id);
        LAST_SHOT.remove(id);
    }

    /** 뒤늦게 시야에 들어온 사람에게도 줄이 보이게 한다. */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getTarget() instanceof ServerPlayer target)) return;
        if (!(event.getEntity() instanceof ServerPlayer viewer)) return;
        if (CLINGING.contains(target.getUUID())) {
            PacketDistributor.sendToPlayer(viewer,
                    new org.gettserver.gettcore.network.WebClingSyncPayload(target.getId(), true));
        }
        Web web = WEBS.get(target.getUUID());
        if (web == null) return;
        long now = target.serverLevel().getGameTime();
        int left = (int) Math.max(0, web.start() + web.flight() - now);
        PacketDistributor.sendToPlayer(viewer, new WebSyncPayload(target.getId(), true, true, web.offhand(),
                web.anchor().x, web.anchor().y, web.anchor().z, left));
    }
}
