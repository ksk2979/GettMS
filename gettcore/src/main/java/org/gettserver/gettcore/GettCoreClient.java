package org.gettserver.gettcore;

import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.gettserver.gettcore.enchant.AbilityKey;
import org.gettserver.gettcore.enchant.EnchantScreen;
import org.gettserver.gettcore.pet.PetRegistry;
import org.gettserver.gettcore.quest.client.QuestHud;
import org.gettserver.gettcore.quest.client.QuestKeys;
import org.gettserver.gettcore.shop.ShopScreen;

@Mod(value = GettCore.MOD_ID, dist = Dist.CLIENT)
public class GettCoreClient {
    public GettCoreClient(IEventBus modBus) {
        modBus.addListener(RegisterMenuScreensEvent.class,
                event -> {
                    event.register(GettCore.SHOP_MENU.get(), ShopScreen::new);
                    event.register(GettCore.ENCHANT_MENU.get(), EnchantScreen::new);
                });
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
            event.register(AbilityKey.TOGGLE);
            event.register(QuestKeys.QUEST_LOG);
            event.register(QuestKeys.QUEST_HUD);
            event.register(QuestKeys.HUD_MOVE);
        });
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class,
                event -> event.registerEntityRenderer(PetRegistry.THROWN_CAPTURE_BALL.get(), ThrownItemRenderer::new));
        // 퀘스트 추적 HUD(오른쪽 위): 상태 효과 레이어 바로 다음에 그린다. 효과 아이콘이 있으면 그 아래로 비켜 그린다.
        modBus.addListener(RegisterGuiLayersEvent.class,
                event -> event.registerAbove(VanillaGuiLayers.EFFECTS, QuestHud.LAYER_ID, QuestHud::render));
    }
}
