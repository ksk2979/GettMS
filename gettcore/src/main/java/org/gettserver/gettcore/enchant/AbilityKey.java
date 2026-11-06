package org.gettserver.gettcore.enchant;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.network.AbilityTogglePayload;
import org.lwjgl.glfw.GLFW;

/** 특수 능력 켜기/끄기 키(기본 ])와 꺼짐 HUD. 클라이언트 전용. */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class AbilityKey {
    public static final KeyMapping TOGGLE = new KeyMapping("gettcore.key.ability_toggle",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, "gettcore.key.category");

    private static boolean enabled = true;

    private AbilityKey() {}

    /** 서버가 알려준 상태. 바뀌었을 때만 액션바와 클릭음으로 알린다(접속 시 동기화는 조용히). */
    public static void setState(boolean value) {
        boolean changed = value != enabled;
        enabled = value;
        if (!changed) return;
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setOverlayMessage(Component.translatable(value ? "gettcore.msg.ability_on" : "gettcore.msg.ability_off"),
                false);
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    public static boolean enabled() {
        return enabled;
    }

    /** 현재 묶인 키의 표시 이름(툴팁 안내용). */
    public static String keyName() {
        return TOGGLE.getTranslatedKeyMessage().getString();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        while (TOGGLE.consumeClick()) {
            if (Minecraft.getInstance().getConnection() != null) {
                PacketDistributor.sendToServer(new AbilityTogglePayload());
            }
        }
    }

    @SubscribeEvent
    public static void onRenderLayer(RenderGuiLayerEvent.Post event) {
        if (enabled || !event.getName().equals(VanillaGuiLayers.HOTBAR)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.player.isSpectator()) return;
        var g = event.getGuiGraphics();
        Font font = mc.font;
        Component text = Component.translatable("gettcore.hud.ability_off");
        // 핫바 왼쪽 끝 바로 왼쪽(왼손 슬롯 자리 바깥), 핫바 높이 가운데. 핫바 위는 체력·방어 줄이 차지한다.
        int hotbarLeft = g.guiWidth() / 2 - 91;
        int x = hotbarLeft - 29 - 4 - font.width(text);
        int y = g.guiHeight() - 15;
        g.drawString(font, text, x, y, 0xAAAAAA, true);
    }
}
