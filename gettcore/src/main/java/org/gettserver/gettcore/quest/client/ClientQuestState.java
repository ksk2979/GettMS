package org.gettserver.gettcore.quest.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.network.QuestSyncPayload;

/**
 * Client copy of the latest main-line quest sync, plus the tracker HUD on/off switch (K). The switch is kept in
 * {@code config/gettcore-client.properties} so it survives restarts. Client only.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class ClientQuestState {
    private static final String FILE = "gettcore-client.properties";
    private static final String HUD_KEY = "questHud";

    private static volatile QuestSyncPayload sync = QuestSyncPayload.empty();
    private static Boolean hudVisible;

    private ClientQuestState() {}

    public static QuestSyncPayload get() {
        return sync;
    }

    public static void set(QuestSyncPayload payload) {
        sync = payload;
    }

    public static boolean hudVisible() {
        if (hudVisible == null) hudVisible = load();
        return hudVisible;
    }

    /** Flips the HUD switch, saves it and returns the new value. */
    public static boolean toggleHud() {
        hudVisible = !hudVisible();
        save(hudVisible);
        return hudVisible;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        sync = QuestSyncPayload.empty();
        QuestToasts.clear();
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE);
    }

    private static boolean load() {
        Path path = file();
        if (!Files.exists(path)) return true;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            GettCore.LOGGER.warn("Could not read {}: {}", path, e.toString());
            return true;
        }
        return !"false".equalsIgnoreCase(props.getProperty(HUD_KEY, "true").trim());
    }

    private static void save(boolean visible) {
        Path path = file();
        Properties props = new Properties();
        try {
            if (Files.exists(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    props.load(in);
                }
            }
            props.setProperty(HUD_KEY, Boolean.toString(visible));
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "Gett Core client settings");
            }
        } catch (IOException e) {
            GettCore.LOGGER.warn("Could not save {}: {}", path, e.toString());
        }
    }
}
