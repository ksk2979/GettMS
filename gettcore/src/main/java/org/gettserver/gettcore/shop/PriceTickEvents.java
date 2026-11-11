package org.gettserver.gettcore.shop;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.gettserver.gettcore.GettCore;

@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class PriceTickEvents {
    private static final int INTERVAL = 1200;
    /** Timed market check: every 5 seconds, so a new period shows up almost on the clock. */
    private static final int FLUCTUATION_INTERVAL = 100;
    private static int ticks;
    private static int fluctuationTicks;

    private PriceTickEvents() {}

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (++fluctuationTicks >= FLUCTUATION_INTERVAL) {
            fluctuationTicks = 0;
            ShopService.fluctuationTick();
        }
        if (++ticks < INTERVAL) return;
        ticks = 0;
        ShopService.recoverTick();
    }
}
