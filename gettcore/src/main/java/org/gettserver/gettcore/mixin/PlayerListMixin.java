package org.gettserver.gettcore.mixin;

import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.gettserver.gettcore.nick.NicknameData;
import org.gettserver.gettcore.spectate.Spectate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * /관전 으로 숨은 채 접속하면 "님이 게임에 참여했습니다" 를 보내지 않는다. 그리고 이름으로 플레이어를 찾을 때 계정
 * 이름이 없으면 닉네임으로도 찾는다(명령어의 플레이어 인자에 닉네임을 쓸 수 있게).
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Redirect(method = "placeNewPlayer",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void gettcore$hideJoin(PlayerList list, Component message, boolean bypassHiddenChat,
            Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        if (!Spectate.isHidden(player)) list.broadcastSystemMessage(message, bypassHiddenChat);
    }

    @Inject(method = "getPlayerByName", at = @At("RETURN"), cancellable = true)
    private void gettcore$byNickname(String username, CallbackInfoReturnable<ServerPlayer> cir) {
        if (cir.getReturnValue() != null || username == null) return;
        PlayerList self = (PlayerList) (Object) this;
        // Before the world is up (or while it shuts down) there is no nickname data to ask.
        if (self.getServer() == null || self.getServer().overworld() == null) return;
        UUID id = NicknameData.get(self.getServer()).book().owner(username);
        if (id != null) cir.setReturnValue(self.getPlayer(id));
    }
}
