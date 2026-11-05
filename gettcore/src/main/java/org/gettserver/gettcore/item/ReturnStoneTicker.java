package org.gettserver.gettcore.item;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
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
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.network.ReturnCastPayload;
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
        broadcastCast(player, true);
        player.sendSystemMessage(Component.translatable("gettcore.msg.return_casting", Math.max(1, ticks / 20)));
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Cast cast = CASTS.get(player.getUUID());
        if (cast == null) return;
        if (player.position().distanceToSqr(cast.startPos()) > 0.01) {
            CASTS.remove(player.getUUID());
            broadcastCast(player, false);
            player.sendSystemMessage(Component.translatable("gettcore.msg.return_cancelled"));
            return;
        }
        int left = cast.ticksLeft() - 1;
        castBeam(player, left);
        if (left > 0) {
            CASTS.put(player.getUUID(), new Cast(left, cast.startPos(), cast.stack(), cast.hand()));
            return;
        }
        CASTS.remove(player.getUUID());
        broadcastCast(player, false);
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

    private static void broadcastCast(ServerPlayer player, boolean active) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new ReturnCastPayload(player.getId(), active));
    }

    /** 마법진은 클라이언트가 텍스처로 그리고, 서버는 가운데서 올라가는 빛줄기만 뿌린다. */
    private static void castBeam(ServerPlayer player, int ticksLeft) {
        Vec3 c = player.position();
        int beams = ticksLeft < 20 ? 3 : 1;
        player.serverLevel().sendParticles(ParticleTypes.END_ROD, c.x, c.y + 0.2, c.z, beams, 0.3, 0.6, 0.3, 0.02);
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
        if (CASTS.remove(event.getEntity().getUUID()) != null && event.getEntity() instanceof ServerPlayer sp) {
            broadcastCast(sp, false);
        }
    }
}
