package org.gettserver.gettcore.display.client;

import com.dreamdisplays.platform.client.displays.DisplayScreen;
import com.dreamdisplays.platform.client.ui.DisplayMenu;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.display.DisplayRules;
import org.gettserver.gettcore.network.DisplayActionPayload;

/**
 * Three small buttons in the title strip of Dream Displays' display menu (sneak + right-click a display): clear the
 * video, a personal video, the repeat switch. The menu's panels fill everything below the strip. Only registered
 * when Dream Displays is installed.
 */
public final class DisplayMenuButtons {
    /** Dream Displays draws its menu on a canvas of at least this size, scaled down to fit the window. */
    private static final double MIN_W = 640, MIN_H = 410;
    private static final int Y = 2, H = 14, GAP = 3, MARGIN = 10;
    private static boolean broken;

    private DisplayMenuButtons() {}

    public static void onInit(ScreenEvent.Init.Post event) {
        if (broken) return;
        try {
            if (event.getScreen() instanceof DisplayMenu menu) add(event, menu);
        } catch (LinkageError | RuntimeException e) {
            broken = true;
            GettCore.LOGGER.warn("gettcore: 화면 메뉴 버튼 비활성 (호환되지 않는 Dream Displays 버전?): {}", e.toString());
        }
    }

    private static void add(ScreenEvent.Init.Post event, DisplayMenu menu) {
        DisplayScreen screen = menu.getDisplayScreen();
        UUID id = screen.getUuid();
        Minecraft mc = Minecraft.getInstance();
        double scale = Math.min(1.0, Math.min(menu.width / MIN_W, menu.height / MIN_H));
        int right = (int) (menu.width / scale) - MARGIN;

        Button loop = Button.builder(loopLabel(id),
                        b -> PacketDistributor.sendToServer(new DisplayActionPayload(id, DisplayRules.ACTION_LOOP_TOGGLE, "")))
                .bounds(0, Y, 0, H).build();
        Button personal = Button.builder(personalLabel(screen), b -> {
            if (ClientDisplays.isPersonal(screen)) ClientDisplays.stopPersonal(screen);
            else mc.setScreen(new PersonalUrlScreen(id));
        }).bounds(0, Y, 0, H).build();
        Button clear = Button.builder(Component.translatable("gettcore.display.btn.clear"),
                        b -> PacketDistributor.sendToServer(new DisplayActionPayload(id, DisplayRules.ACTION_CLEAR, "")))
                .bounds(0, Y, 0, H).build();

        int x = right;
        for (Button b : new Button[] {loop, personal, clear}) {
            // the repeat label has two states: size the button for the longer one
            int w = (b == loop
                    ? Math.max(mc.font.width(Component.translatable("gettcore.display.btn.loop_on")),
                            mc.font.width(Component.translatable("gettcore.display.btn.loop_off")))
                    : mc.font.width(b.getMessage())) + 10;
            x -= w;
            b.setX(x);
            b.setWidth(w);
            x -= GAP;
            event.addListener(b);
        }
        ClientDisplayState.onLoopsChanged(() -> mc.execute(() -> loop.setMessage(loopLabel(id))));
    }

    private static Component loopLabel(UUID id) {
        return Component.translatable(ClientDisplayState.loops(id)
                ? "gettcore.display.btn.loop_on" : "gettcore.display.btn.loop_off");
    }

    private static Component personalLabel(DisplayScreen screen) {
        return Component.translatable(ClientDisplays.isPersonal(screen)
                ? "gettcore.display.btn.personal_off" : "gettcore.display.btn.personal");
    }
}
