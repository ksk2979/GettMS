package org.gettserver.gettcore.quest.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.gettserver.gettcore.GettCore;
import org.lwjgl.glfw.GLFW;

/** Quest keys: J opens the quest list, K turns the tracker HUD on/off, Ctrl+- moves it. Client only. */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class QuestKeys {
    public static final KeyMapping QUEST_LOG = new KeyMapping("key.gettcore.quest_log",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "gettcore.key.category");
    public static final KeyMapping QUEST_HUD = new KeyMapping("key.gettcore.quest_hud",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "gettcore.key.category");
    public static final KeyMapping HUD_MOVE = new KeyMapping("key.gettcore.hud_move", KeyConflictContext.IN_GAME,
            KeyModifier.CONTROL, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_MINUS, "gettcore.key.category");

    private QuestKeys() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (QUEST_LOG.consumeClick()) {
            if (mc.player != null && mc.screen == null) mc.setScreen(new QuestLogScreen());
        }
        while (QUEST_HUD.consumeClick()) {
            if (mc.player != null) toggleHud(mc);
        }
        while (HUD_MOVE.consumeClick()) {
            if (mc.player != null && mc.screen == null) mc.setScreen(new HudMoveScreen());
        }
    }

    /** Toggles the tracker HUD and says so in the action bar. */
    public static void toggleHud(Minecraft mc) {
        boolean on = ClientQuestState.toggleHud();
        mc.gui.setOverlayMessage(Component.translatable(on ? "gettcore.quest.hud.on" : "gettcore.quest.hud.off"),
                false);
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
