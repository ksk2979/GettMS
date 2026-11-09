package org.gettserver.gettcore.anvil;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AnvilRepairEvent;
import org.gettserver.gettcore.GettCore;

@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class SturdyAnvilEvents {
    private SturdyAnvilEvents() {}

    @SubscribeEvent
    public static void onAnvilRepair(AnvilRepairEvent event) {
        boolean sturdy = event.getEntity().containerMenu instanceof SturdyAnvilMenu;
        event.setBreakChance(SturdyAnvil.breakChance(sturdy, event.getBreakChance()));
    }
}
