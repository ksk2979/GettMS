package org.gettserver.gettcore.quest.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Consumer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.network.QuestSyncPayload;

/**
 * Client copy of the latest main-line quest sync, plus the tracker HUD on/off switch (K) and its position (Ctrl+-).
 * Both are kept in {@code config/gettcore-client.properties} so they survive restarts. Client only.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class ClientQuestState {
    private static final String FILE = "gettcore-client.properties";
    private static final String HUD_KEY = "questHud";

    private static volatile QuestSyncPayload sync = QuestSyncPayload.empty();
    private static Boolean hudVisible;
    private static HudPosition hudPosition;

    private ClientQuestState() {}

    public static QuestSyncPayload get() {
        return sync;
    }

    public static void set(QuestSyncPayload payload) {
        sync = payload;
    }

    public static boolean hudVisible() {
        if (hudVisible == null) loadAll();
        return hudVisible;
    }

    /** Flips the HUD switch, saves it and returns the new value. */
    public static boolean toggleHud() {
        hudVisible = !hudVisible();
        save(props -> props.setProperty(HUD_KEY, Boolean.toString(hudVisible)));
        return hudVisible;
    }

    /** Saved tracker HUD position (Ctrl+-). */
    public static HudPosition hudPosition() {
        if (hudPosition == null) loadAll();
        return hudPosition;
    }

    /** Uses and saves this position, keeping the other keys of the file. */
    public static void saveHudPosition(HudPosition pos) {
        hudPosition = pos;
        save(pos::write);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        sync = QuestSyncPayload.empty();
        QuestToasts.clear();
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE);
    }

    private static void loadAll() {
        Properties props = new Properties();
        Path path = file();
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException e) {
                GettCore.LOGGER.warn("Could not read {}: {}", path, e.toString());
                props = new Properties();
            }
        }
        if (hudVisible == null) hudVisible = !"false".equalsIgnoreCase(props.getProperty(HUD_KEY, "true").trim());
        if (hudPosition == null) hudPosition = HudPosition.read(props);
    }

    /** Re-reads the file, applies {@code change} and writes it back so other keys survive. */
    private static void save(Consumer<Properties> change) {
        Path path = file();
        Properties props = new Properties();
        try {
            if (Files.exists(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    props.load(in);
                }
            }
            change.accept(props);
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "Gett Core client settings");
            }
        } catch (IOException e) {
            GettCore.LOGGER.warn("Could not save {}: {}", path, e.toString());
        }
    }
}
