package org.gettserver.gettcore.item;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.joml.Vector3f;
import org.gettserver.gettcore.GettCore;

@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class ReturnStoneTicker {
    public record Cast(int ticksLeft, Vec3 startPos, ItemStack stack, InteractionHand hand) {}

    private static final Map<UUID, Cast> CASTS = new HashMap<>();

    private ReturnStoneTicker() {}

    public static boolean isCasting(UUID id) {
        return CASTS.containsKey(id);
    }

    public static void start(ServerPlayer player, InteractionHand hand, int ticks) {
        ItemStack stack = player.getItemInHand(hand);
        CASTS.put(player.getUUID(), new Cast(ticks, player.position(), stack, hand));
        player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.6f, 1.4f);
        player.sendSystemMessage(Component.translatable("gettcore.msg.return_casting", Math.max(1, ticks / 20)));
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Cast cast = CASTS.get(player.getUUID());
        if (cast == null) return;
        if (player.position().distanceToSqr(cast.startPos()) > 0.01) {
            CASTS.remove(player.getUUID());
            player.sendSystemMessage(Component.translatable("gettcore.msg.return_cancelled"));
            return;
        }
        int left = cast.ticksLeft() - 1;
        drawMagicCircle(player, left);
        if (left > 0) {
            CASTS.put(player.getUUID(), new Cast(left, cast.startPos(), cast.stack(), cast.hand()));
            return;
        }
        CASTS.remove(player.getUUID());
        ItemStack held = player.getItemInHand(cast.hand());
        if (held.isEmpty() || !held.is(GettCore.RETURN_STONE.get())) {
            player.sendSystemMessage(Component.translatable("gettcore.msg.return_cancelled"));
            return;
        }
        departEffect(player);
        teleport(player);
        arriveEffect(player);
        held.shrink(1);
        player.sendSystemMessage(Component.translatable("gettcore.msg.return_done"));
    }

    private static final DustParticleOptions RING_DUST = new DustParticleOptions(new Vector3f(0.45f, 0.75f, 1.0f), 1.0f);
    private static final DustParticleOptions STAR_DUST = new DustParticleOptions(new Vector3f(0.8f, 0.6f, 1.0f), 0.8f);
    private static final double RING_RADIUS = 1.6;
    private static final int RING_POINTS = 24;
    private static final int STAR_EDGE_POINTS = 5;

    /** 시전 중 플레이어 발밑에 회전하는 원 + 육망성을 파티클로 그린다. 매 틱 60개 안팎이라 서버 부담은 적다. */
    private static void drawMagicCircle(ServerPlayer player, int ticksLeft) {
        ServerLevel level = player.serverLevel();
        Vec3 c = player.position();
        double y = c.y + 0.1;
        double spin = ticksLeft * 0.12;
        for (int i = 0; i < RING_POINTS; i++) {
            double a = spin + i * (Math.PI * 2 / RING_POINTS);
            level.sendParticles(RING_DUST, c.x + Math.cos(a) * RING_RADIUS, y, c.z + Math.sin(a) * RING_RADIUS, 1, 0, 0, 0, 0);
        }
        // 두 개의 삼각형(반대 방향 회전)으로 육망성을 만든다.
        for (int tri = 0; tri < 2; tri++) {
            double base = -spin * 0.5 + tri * Math.PI;
            for (int e = 0; e < 3; e++) {
                double a1 = base + e * (Math.PI * 2 / 3);
                double a2 = base + (e + 1) * (Math.PI * 2 / 3);
                double x1 = Math.cos(a1) * RING_RADIUS, z1 = Math.sin(a1) * RING_RADIUS;
                double x2 = Math.cos(a2) * RING_RADIUS, z2 = Math.sin(a2) * RING_RADIUS;
                for (int k = 0; k < STAR_EDGE_POINTS; k++) {
                    double t = (k + 0.5) / STAR_EDGE_POINTS;
                    level.sendParticles(STAR_DUST, c.x + x1 + (x2 - x1) * t, y, c.z + z1 + (z2 - z1) * t, 1, 0, 0, 0, 0);
                }
            }
        }
        // 가운데서 올라가는 빛줄기, 끝이 가까울수록 많아진다.
        int beams = ticksLeft < 20 ? 3 : 1;
        level.sendParticles(ParticleTypes.END_ROD, c.x, c.y + 0.2, c.z, beams, 0.3, 0.6, 0.3, 0.02);
    }

    private static void departEffect(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 c = player.position();
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y + 1, c.z, 60, 0.4, 0.8, 0.4, 0.15);
        level.sendParticles(ParticleTypes.END_ROD, c.x, c.y + 1, c.z, 20, 0.5, 0.8, 0.5, 0.1);
        level.playSound(null, player.blockPosition(), SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1f, 0.9f);
    }

    private static void arriveEffect(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 c = player.position();
        level.sendParticles(ParticleTypes.PORTAL, c.x, c.y + 1, c.z, 60, 0.4, 0.8, 0.4, 0.3);
        level.sendParticles(ParticleTypes.END_ROD, c.x, c.y + 0.2, c.z, 20, 0.6, 0.2, 0.6, 0.05);
        level.playSound(null, player.blockPosition(), SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1f, 1.2f);
    }

    private static void teleport(ServerPlayer player) {
        MinecraftServer server = player.server;
        ReturnPointData data = ReturnPointData.get(server);
        Optional<GlobalPos> gp = data.pos();
        ServerLevel target = gp.map(g -> server.getLevel(g.dimension())).orElse(null);
        if (target != null) {
            BlockPos p = gp.get().pos();
            player.teleportTo(target, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, Set.of(), data.yaw(), 0f);
        } else {
            ServerLevel overworld = server.overworld();
            BlockPos p = overworld.getSharedSpawnPos();
            player.teleportTo(overworld, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, Set.of(),
                    overworld.getSharedSpawnAngle(), 0f);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        CASTS.remove(event.getEntity().getUUID());
    }
}
