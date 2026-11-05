package org.gettserver.gettcore.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.gettserver.gettcore.economy.ClientBalance;
import org.gettserver.gettcore.economy.ClientConfig;
import org.gettserver.gettcore.npc.DialogClient;
import org.gettserver.gettcore.npc.ClientNpcMappings;
import org.gettserver.gettcore.npc.DialogSession;
import org.gettserver.gettcore.shop.ClientShopCache;

public final class GettNetwork {
    private GettNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(BalanceSyncPayload.TYPE, BalanceSyncPayload.STREAM_CODEC,
                (payload, ctx) -> ClientBalance.set(payload.balance()));
        registrar.playToClient(ConfigSyncPayload.TYPE, ConfigSyncPayload.STREAM_CODEC,
                (payload, ctx) -> ClientConfig.set(payload.hudOffsetX(), payload.hudOffsetY(), payload.currencyName()));
        registrar.playToClient(NpcMappingSyncPayload.TYPE, NpcMappingSyncPayload.STREAM_CODEC,
                (payload, ctx) -> ClientNpcMappings.set(payload.byUuid(), payload.byName()));
        registrar.playToClient(ShopSyncPayload.TYPE, ShopSyncPayload.STREAM_CODEC,
                (payload, ctx) -> ClientShopCache.set(payload.shopId(), payload.definition(), payload.dynamicsEnabled()));
        // DialogClient is only resolved when this client-bound handler runs, so the server never loads it.
        registrar.playToClient(OpenDialogPayload.TYPE, OpenDialogPayload.STREAM_CODEC,
                (payload, ctx) -> DialogClient.open(payload));
        registrar.playToServer(DialogChoicePayload.TYPE, DialogChoicePayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(
                        () -> DialogSession.choose((ServerPlayer) ctx.player(), payload.choiceIndex())));
    }
}
