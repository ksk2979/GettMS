package org.gettserver.gettcore.display.client;

import com.dreamdisplays.platform.client.displays.DisplayRegistry;
import com.dreamdisplays.platform.client.displays.DisplayScreen;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.gettserver.gettcore.GettCore;

/**
 * Client side of the personal ("only me") video, written against Dream Displays 1.10.9 and only loaded when that mod
 * is. The mod has an unused per-client address override on its screens; the mixins keep it alive (see
 * {@code ddmixin.DisplayScreenMixin}) and this class switches it.
 */
public final class ClientDisplays {
    private static Method screensByIdMethod;

    private ClientDisplays() {}

    /** {@code url} "" goes back to the shared video. */
    public static void applyPersonal(UUID id, String url) {
        try {
            DisplayScreen screen = screens().get(id);
            if (screen == null || !((Object) screen instanceof PersonalFlag flag)) {
                say("gettcore.display.not_loaded");
                return;
            }
            if (url.isEmpty()) {
                stopPersonal(screen, flag);
                return;
            }
            ClientDisplayState.setPersonal(id, true);
            flag.gettcore$setPersonal(true);
            String lang = screen.getLang();
            screen.loadVideo(url, lang == null ? "" : lang);
            say("gettcore.display.personal_on");
        } catch (LinkageError | ReflectiveOperationException | RuntimeException e) {
            GettCore.LOGGER.warn("gettcore: 나만 보기 실패 (호환되지 않는 Dream Displays 버전?): {}", e.toString());
            say("gettcore.display.unsupported");
        }
    }

    public static boolean isPersonal(DisplayScreen screen) {
        return ClientDisplayState.personal(screen.getUuid()) && (Object) screen instanceof PersonalFlag f && f.gettcore$personal();
    }

    /** From the menu button; needs no server. */
    public static void stopPersonal(DisplayScreen screen) {
        if ((Object) screen instanceof PersonalFlag flag) stopPersonal(screen, flag);
    }

    private static void stopPersonal(DisplayScreen screen, PersonalFlag flag) {
        UUID id = screen.getUuid();
        if (!ClientDisplayState.personal(id)) {
            say("gettcore.display.personal_none");
            return;
        }
        ClientDisplayState.setPersonal(id, false);
        flag.gettcore$setPersonal(false);
        // The screen does not remember the shared address: drop it, and the next info packet from the server (one a
        // second) builds it again with the shared video, or blank.
        DisplayRegistry.INSTANCE.unregisterScreen(screen);
        DisplayRegistry.INSTANCE.getUnloadedScreens().remove(id);
        say("gettcore.display.personal_off");
    }

    /** DisplayRegistry has two getScreens() that differ only in the return type, which Java source cannot call. */
    @SuppressWarnings("unchecked")
    private static Map<UUID, DisplayScreen> screens() throws ReflectiveOperationException {
        if (screensByIdMethod == null) {
            for (Method m : DisplayRegistry.class.getMethods()) {
                if (m.getName().equals("getScreens") && m.getParameterCount() == 0
                        && Map.class.isAssignableFrom(m.getReturnType())) {
                    screensByIdMethod = m;
                }
            }
            if (screensByIdMethod == null) throw new NoSuchMethodException("DisplayRegistry.getScreens(): Map");
        }
        return (Map<UUID, DisplayScreen>) screensByIdMethod.invoke(DisplayRegistry.INSTANCE);
    }

    private static void say(String key) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.translatable(key), false);
        }
    }
}
