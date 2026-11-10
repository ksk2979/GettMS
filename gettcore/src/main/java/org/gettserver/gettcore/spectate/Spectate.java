package org.gettserver.gettcore.spectate;

import java.util.List;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * 관리자 관전(/관전): 관전 모드 + 숨김.
 * <p>
 * 바닐라는 이미 관전 플레이어를 관전 아닌 플레이어에게 보내지 않고(ServerPlayer.broadcastToPlayer),
 * 관전끼리는 머리만 반투명하게 보인다. 여기서는 그 위에 Tab 목록 숨김·접속/퇴장 메시지 숨김(믹스인)과
 * 모드 전환 즉시 추적 갱신을 더한다. 상태는 플레이어 persistentData 에 저장돼 재접속에도 유지된다.
 */
public final class Spectate {
    private static final String KEY = "gettcore_spectate";
    private static final String PREV = "gettcore_spectate_prev";

    private Spectate() {}

    public static boolean isHidden(ServerPlayer player) {
        return player.getPersistentData().getBoolean(KEY);
    }

    /** 켜고 끈다. 반환값은 켜졌는지 여부. */
    static boolean toggle(ServerPlayer player) {
        if (isHidden(player)) {
            disable(player, true);
            return false;
        }
        GameType prev = player.gameMode.getGameModeForPlayer();
        player.getPersistentData().putString(PREV, prev == GameType.SPECTATOR ? GameType.SURVIVAL.getName() : prev.getName());
        player.getPersistentData().putBoolean(KEY, true);
        player.setGameMode(GameType.SPECTATOR);
        broadcastListed(player);
        refreshTracking(player.serverLevel());
        return true;
    }

    /** 숨김을 푼다. restoreMode 이면 저장해 둔 원래 게임 모드로 돌린다(자리는 그대로). */
    static void disable(ServerPlayer player, boolean restoreMode) {
        String prev = player.getPersistentData().getString(PREV);
        player.getPersistentData().remove(KEY);
        player.getPersistentData().remove(PREV);
        if (restoreMode) player.setGameMode(SpectateRules.restoreMode(prev));
        broadcastListed(player);
        refreshTracking(player.serverLevel());
    }

    /** 모두에게 이 플레이어의 Tab 목록 표시 여부(숨김이면 안 보임)를 보낸다. */
    static void broadcastListed(ServerPlayer player) {
        player.server.getPlayerList().broadcastAll(listedPacket(player));
    }

    /** UPDATE_LISTED 패킷. 바닐라 Entry 는 listed 를 항상 true 로 만들어서 숨김이면 false 로 바꿔 끼운다. */
    static ClientboundPlayerInfoUpdatePacket listedPacket(ServerPlayer player) {
        var packet = new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED, player);
        var e = packet.entries().get(0);
        packet.entries = List.of(new ClientboundPlayerInfoUpdatePacket.Entry(e.profileId(), e.profile(), !isHidden(player),
                e.latency(), e.gameMode(), e.displayName(), e.chatSession()));
        return packet;
    }

    /** 모드가 바뀐 즉시 이 세계의 플레이어 추적을 다시 계산한다 (바닐라는 구역을 옮길 때까지 미룸). */
    static void refreshTracking(ServerLevel level) {
        ChunkMap map = level.getChunkSource().chunkMap;
        List<ServerPlayer> players = level.players();
        for (ServerPlayer p : players) {
            ChunkMap.TrackedEntity tracked = map.entityMap.get(p.getId());
            if (tracked != null) tracked.updatePlayers(players);
        }
    }
}
