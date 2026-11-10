package org.gettserver.gettcore.spectate;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.perm.Perms;

/** /관전 (/spectate) 명령과 접속·게임 모드 변경 처리. */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class SpectateEvents {
    private SpectateEvents() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        for (String name : new String[] {"관전", "spectate"}) {
            d.register(Commands.literal(name)
                    .requires(Perms.require(Perms.ADMIN))
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        boolean on = Spectate.toggle(player);
                        player.sendSystemMessage(Component.translatable(
                                on ? "gettcore.spectate.on" : "gettcore.spectate.off"));
                        GettCore.LOGGER.info("gettcore: {} spectate {}", player.getGameProfile().getName(), on ? "on" : "off");
                        return 1;
                    }));
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer joiner)) return;
        // 새로 들어온 사람의 Tab 목록에서 숨은 관리자를 뺀다.
        for (ServerPlayer other : joiner.server.getPlayerList().getPlayers()) {
            if (other != joiner && Spectate.isHidden(other)) joiner.connection.send(Spectate.listedPacket(other));
        }
        if (!Spectate.isHidden(joiner)) return;
        if (!joiner.isSpectator()) {
            // 관전이 아닌 채로 들어왔으면(파일 수정 등) 숨김을 푼다.
            Spectate.disable(joiner, false);
            return;
        }
        Spectate.broadcastListed(joiner);
        joiner.sendSystemMessage(Component.translatable("gettcore.spectate.still_on"));
    }

    @SubscribeEvent
    public static void onGameModeChange(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !Spectate.isHidden(player)) return;
        if (event.getNewGameMode() == GameType.SPECTATOR) return;
        // /gamemode 등으로 관전에서 나가면 숨김도 함께 푼다. 모드는 이 이벤트 뒤에 바뀌므로 추적 갱신은 다음 틱에.
        Spectate.disable(player, false);
        var server = player.server;
        server.tell(new TickTask(server.getTickCount() + 1, () -> Spectate.refreshTracking(player.serverLevel())));
        player.sendSystemMessage(Component.translatable("gettcore.spectate.off"));
    }
}
