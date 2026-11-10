package org.gettserver.gettcore.mixin;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.gettserver.gettcore.spectate.Spectate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** /관전 으로 숨은 채 접속하면 "님이 게임에 참여했습니다" 를 보내지 않는다. */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Redirect(method = "placeNewPlayer",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void gettcore$hideJoin(PlayerList list, Component message, boolean bypassHiddenChat,
            Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        if (!Spectate.isHidden(player)) list.broadcastSystemMessage(message, bypassHiddenChat);
    }
}
