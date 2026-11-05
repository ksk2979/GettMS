package org.gettserver.gettcore.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.gettserver.gettcore.economy.ClientBalance;
import org.gettserver.gettcore.economy.ClientConfig;

public final class GettNetwork {
    private GettNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(BalanceSyncPayload.TYPE, BalanceSyncPayload.STREAM_CODEC,
                (payload, ctx) -> ClientBalance.set(payload.balance()));
        registrar.playToClient(ConfigSyncPayload.TYPE, ConfigSyncPayload.STREAM_CODEC,
                (payload, ctx) -> ClientConfig.set(payload.hudOffsetX(), payload.hudOffsetY(), payload.currencyName()));
    }
}
