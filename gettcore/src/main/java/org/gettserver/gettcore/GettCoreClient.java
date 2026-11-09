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
import net.neoforged.neoforge.common.NeoForge;
import org.gettserver.gettcore.enchant.AbilityKey;
import org.gettserver.gettcore.enchant.EnchantScreen;
import org.gettserver.gettcore.pet.PetRegistry;
import org.gettserver.gettcore.pet.client.CaptureAimClient;
import org.gettserver.gettcore.pet.client.MobHealthBar;
import org.gettserver.gettcore.pet.client.PetFlightInput;
import org.gettserver.gettcore.pet.client.PetRenderers;
import org.gettserver.gettcore.quest.client.QuestHud;
import org.gettserver.gettcore.quest.client.QuestKeys;
import org.gettserver.gettcore.reinforce.ReinforceScreen;
import org.gettserver.gettcore.shop.ShopScreen;

@Mod(value = GettCore.MOD_ID, dist = Dist.CLIENT)
public class GettCoreClient {
    public GettCoreClient(IEventBus modBus) {
        modBus.addListener(RegisterMenuScreensEvent.class,
                event -> {
                    event.register(GettCore.SHOP_MENU.get(), ShopScreen::new);
                    event.register(GettCore.ENCHANT_MENU.get(), EnchantScreen::new);
                    event.register(GettCore.REINFORCE_MENU.get(), ReinforceScreen::new);
                    event.register(GettCore.UPGRADE_MENU.get(), org.gettserver.gettcore.upgrade.UpgradeScreen::new);
                });
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
            event.register(AbilityKey.TOGGLE);
            event.register(QuestKeys.QUEST_LOG);
            event.register(QuestKeys.QUEST_HUD);
            event.register(QuestKeys.HUD_MOVE);
            event.register(org.gettserver.gettcore.pet.client.PetKeys.PET_DEX);
        });
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, event -> {
            event.registerEntityRenderer(PetRegistry.THROWN_CAPTURE_BALL.get(), ThrownItemRenderer::new);
            PetRenderers.register(event);
        });
        // 지상 탈것 점프: 탑승자 클라이언트에서 점프 키를 읽어 즉시 점프(PetState.tickRidden).
        org.gettserver.gettcore.pet.entity.PetBehavior.RIDER_JUMP_INPUT =
                () -> net.minecraft.client.Minecraft.getInstance().options.keyJump.isDown();
        // 비행 탈것 상승/하강 키(점프/달리기).
        NeoForge.EVENT_BUS.addListener(PetFlightInput::onClientTick);
        // 몹 머리 위 체력바·퍼센트(때린 몹만).
        // 몬스터 체력바: Epic Fight 가 있으면 기본으로 끔(자체 체력바와 겹침). gettcore-client.properties 의 mobHealthBar=on|off 로 강제.
        boolean epicFight = net.neoforged.fml.ModList.get().isLoaded("epicfight");
        String barSetting = org.gettserver.gettcore.pet.client.HealthBarSetting.read(
                net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("gettcore-client.properties"));
        if (org.gettserver.gettcore.pet.client.HealthBarSetting.show(barSetting, epicFight)) {
            NeoForge.EVENT_BUS.addListener(MobHealthBar::onRenderLiving);
        } else {
            GettCore.LOGGER.info("gettcore: mob health bar off (mobHealthBar={}, epicfight={})", barSetting, epicFight);
        }
        // 퀘스트 추적 HUD(오른쪽 위): 상태 효과 레이어 바로 다음에 그린다. 효과 아이콘이 있으면 그 아래로 비켜 그린다.
        // 포획틀: 우클릭 유지 = 조준(줌 + 십자선 아래 포획률), 좌클릭 = 던지기(서버 판정).
        NeoForge.EVENT_BUS.addListener(CaptureAimClient::onClientTickPre);
        NeoForge.EVENT_BUS.addListener(CaptureAimClient::onInteractionKey);
        NeoForge.EVENT_BUS.addListener(CaptureAimClient::onComputeFov);
        modBus.addListener(RegisterGuiLayersEvent.class, event -> {
            event.registerAbove(VanillaGuiLayers.EFFECTS, QuestHud.LAYER_ID, QuestHud::render);
            event.registerAbove(VanillaGuiLayers.CROSSHAIR, CaptureAimClient.LAYER_ID, CaptureAimClient::render);
        });
    }
}
