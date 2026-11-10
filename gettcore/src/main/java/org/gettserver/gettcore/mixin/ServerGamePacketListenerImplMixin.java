package org.gettserver.gettcore.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.gettserver.gettcore.spectate.Spectate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** /관전 으로 숨은 채 나가면 "님이 게임을 떠났습니다" 를 보내지 않는다. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow public ServerPlayer player;

    @Redirect(method = "removePlayerFromWorld",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void gettcore$hideLeave(PlayerList list, Component message, boolean bypassHiddenChat) {
        if (!Spectate.isHidden(player)) list.broadcastSystemMessage(message, bypassHiddenChat);
    }
}
