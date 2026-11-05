package org.gettserver.gettcore.item;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
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
        teleport(player);
        held.shrink(1);
        player.sendSystemMessage(Component.translatable("gettcore.msg.return_done"));
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
