package org.gettserver.gettcore.data;

import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.network.ConfigSyncPayload;

@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class DataEvents {
    private DataEvents() {}

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get().resolve("gettcore");
    }

    public static ConfigSyncPayload configPayload() {
        EconomyConfig c = GettData.get().economy();
        return new ConfigSyncPayload(c.hudOffsetX(), c.hudOffsetY(), c.currencyName());
    }

    public static void syncConfig(MinecraftServer server) {
        PacketDistributor.sendToAllPlayers(configPayload());
    }

    public static void syncConfig(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, configPayload());
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        GettData.LoadReport r = GettData.get().load(configDir());
        r.errors().forEach(e -> GettCore.LOGGER.error("gettcore load: {}", e));
        GettCore.LOGGER.info("gettcore: 상점 {}, 대화 {}", r.shops(), r.dialogs());
    }
}
