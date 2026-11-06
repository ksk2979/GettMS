package org.gettserver.gettcore.enchant;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.network.AbilityStatePayload;

/** 플레이어별 특수 능력 켜짐/꺼짐(서버 스레드 전용). 기본 켜짐, 접속이 끊기면 잊는다. */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class AbilityToggle {
    private static final Map<UUID, Boolean> STATE = new HashMap<>();

    private AbilityToggle() {}

    public static boolean enabled(ServerPlayer player) {
        return STATE.getOrDefault(player.getUUID(), Boolean.TRUE);
    }

    public static void toggle(ServerPlayer player) {
        boolean next = !enabled(player);
        STATE.put(player.getUUID(), next);
        PacketDistributor.sendToPlayer(player, new AbilityStatePayload(next));
    }

    /** 접속 시: 상태를 기본(켜짐)으로 되돌리고 클라이언트 HUD에 알린다. */
    public static void reset(ServerPlayer player) {
        STATE.remove(player.getUUID());
        PacketDistributor.sendToPlayer(player, new AbilityStatePayload(true));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            STATE.remove(player.getUUID());
        }
    }
}
