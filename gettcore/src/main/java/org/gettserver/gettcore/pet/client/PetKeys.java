package org.gettserver.gettcore.pet.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.gettserver.gettcore.GettCore;
import org.lwjgl.glfw.GLFW;

/** Pet keys: N opens the pet dex. Client only. */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class PetKeys {
    public static final KeyMapping PET_DEX = new KeyMapping("key.gettcore.pet_dex",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, "gettcore.key.category");

    private PetKeys() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (PET_DEX.consumeClick()) {
            if (mc.player != null && mc.screen == null) mc.setScreen(new PetDexScreen());
        }
    }
}
