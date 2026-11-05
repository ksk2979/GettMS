package org.gettserver.gettcore.shop;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.gettserver.gettcore.GettCore;

@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class PriceTickEvents {
    private static final int INTERVAL = 1200;
    private static int ticks;

    private PriceTickEvents() {}

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (++ticks < INTERVAL) return;
        ticks = 0;
        ShopService.recoverTick();
    }
}
