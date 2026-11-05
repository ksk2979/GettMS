package org.gettserver.gettcore.economy;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.network.BalanceSyncPayload;

@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class EconomyEvents {
    private EconomyEvents() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            Ledger ledger = EconomyData.get(sp.server).ledger();
            PacketDistributor.sendToPlayer(sp, new BalanceSyncPayload(ledger.get(sp.getUUID())));
            org.gettserver.gettcore.data.DataEvents.syncConfig(sp);
        }
    }
}
