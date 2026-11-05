package org.gettserver.gettcore.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.gettserver.gettcore.economy.ClientBalance;

public final class GettNetwork {
    private GettNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(BalanceSyncPayload.TYPE, BalanceSyncPayload.STREAM_CODEC,
                (payload, ctx) -> ClientBalance.set(payload.balance()));
    }
}
