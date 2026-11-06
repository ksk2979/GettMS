package org.gettserver.gettcore;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import org.gettserver.gettcore.enchant.AbilityKey;
import org.gettserver.gettcore.enchant.EnchantScreen;
import org.gettserver.gettcore.shop.ShopScreen;

@Mod(value = GettCore.MOD_ID, dist = Dist.CLIENT)
public class GettCoreClient {
    public GettCoreClient(IEventBus modBus) {
        modBus.addListener(RegisterMenuScreensEvent.class,
                event -> {
                    event.register(GettCore.SHOP_MENU.get(), ShopScreen::new);
                    event.register(GettCore.ENCHANT_MENU.get(), EnchantScreen::new);
                });
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> event.register(AbilityKey.TOGGLE));
    }
}
