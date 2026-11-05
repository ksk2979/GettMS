package org.gettserver.gettcore.npc;

import net.minecraft.client.Minecraft;
import org.gettserver.gettcore.network.OpenDialogPayload;

/** Client-only entry point; only referenced from a client-bound packet handler. */
public final class DialogClient {
    private DialogClient() {}

    public static void open(OpenDialogPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (payload.choiceLabels().isEmpty()) {
            if (mc.screen instanceof DialogScreen) {
                mc.setScreen(null);
            }
            return;
        }
        mc.setScreen(new DialogScreen(payload));
    }
}
