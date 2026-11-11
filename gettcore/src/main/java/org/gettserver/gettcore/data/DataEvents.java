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
import org.gettserver.gettcore.network.EnchantConfigSyncPayload;
import org.gettserver.gettcore.network.EquipmentConfigSyncPayload;
import org.gettserver.gettcore.network.UpgradeConfigSyncPayload;
import org.gettserver.gettcore.network.PetConfigSyncPayload;
import org.gettserver.gettcore.network.ReinforceConfigSyncPayload;
import org.gettserver.gettcore.network.NpcMappingSyncPayload;

@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class DataEvents {
    private DataEvents() {}

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get().resolve("gettcore");
    }

    public static ConfigSyncPayload configPayload() {
        EconomyConfig c = GettData.get().economy();
        return new ConfigSyncPayload(c.hudOffsetX(), c.hudOffsetY(), c.currencyName(), (float) c.otherGunSoundVolume());
    }

    public static void syncConfig(MinecraftServer server) {
        PacketDistributor.sendToAllPlayers(configPayload());
        PacketDistributor.sendToAllPlayers(NpcMappingSyncPayload.of(GettData.get().npcMapping()));
        PacketDistributor.sendToAllPlayers(new EnchantConfigSyncPayload(GettData.get().enchant()));
        PacketDistributor.sendToAllPlayers(new PetConfigSyncPayload(GettData.get().pets()));
        PacketDistributor.sendToAllPlayers(new ReinforceConfigSyncPayload(GettData.get().reinforce()));
        PacketDistributor.sendToAllPlayers(new EquipmentConfigSyncPayload(GettData.get().equipment()));
        PacketDistributor.sendToAllPlayers(new UpgradeConfigSyncPayload(GettData.get().upgrade()));
    }

    public static void syncConfig(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, configPayload());
        PacketDistributor.sendToPlayer(player, NpcMappingSyncPayload.of(GettData.get().npcMapping()));
        PacketDistributor.sendToPlayer(player, new EnchantConfigSyncPayload(GettData.get().enchant()));
        PacketDistributor.sendToPlayer(player, new PetConfigSyncPayload(GettData.get().pets()));
        PacketDistributor.sendToPlayer(player, new ReinforceConfigSyncPayload(GettData.get().reinforce()));
        PacketDistributor.sendToPlayer(player, new EquipmentConfigSyncPayload(GettData.get().equipment()));
        PacketDistributor.sendToPlayer(player, new UpgradeConfigSyncPayload(GettData.get().upgrade()));
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        GettData.LoadReport r = GettData.get().load(configDir());
        r.errors().forEach(e -> GettCore.LOGGER.error("gettcore load: {}", e));
        GettCore.LOGGER.info("gettcore: 상점 {}, 대화 {}", r.shops(), r.dialogs());
    }
}
