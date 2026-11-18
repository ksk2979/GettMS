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
import org.gettserver.gettcore.network.DetectorConfigSyncPayload;
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
        return new ConfigSyncPayload(c.hudOffsetX(), c.hudOffsetY(), c.currencyName(), (float) c.gunSoundVolume());
    }

    public static void syncConfig(MinecraftServer server) {
        PacketDistributor.sendToAllPlayers(org.gettserver.gettcore.network.PresetSyncPayload.current());
        PacketDistributor.sendToAllPlayers(configPayload());
        PacketDistributor.sendToAllPlayers(NpcMappingSyncPayload.of(GettData.get().npcMapping()));
        PacketDistributor.sendToAllPlayers(new EnchantConfigSyncPayload(GettData.get().enchant()));
        PacketDistributor.sendToAllPlayers(new PetConfigSyncPayload(GettData.get().pets()));
        PacketDistributor.sendToAllPlayers(new ReinforceConfigSyncPayload(GettData.get().reinforce()));
        PacketDistributor.sendToAllPlayers(new EquipmentConfigSyncPayload(GettData.get().equipment()));
        PacketDistributor.sendToAllPlayers(new UpgradeConfigSyncPayload(GettData.get().upgrade()));
        PacketDistributor.sendToAllPlayers(new DetectorConfigSyncPayload(GettData.get().detector()));
        PacketDistributor.sendToAllPlayers(new org.gettserver.gettcore.network.WebConfigSyncPayload(GettData.get().web()));
    }

    public static void syncConfig(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, org.gettserver.gettcore.network.PresetSyncPayload.current());
        PacketDistributor.sendToPlayer(player, configPayload());
        PacketDistributor.sendToPlayer(player, NpcMappingSyncPayload.of(GettData.get().npcMapping()));
        PacketDistributor.sendToPlayer(player, new EnchantConfigSyncPayload(GettData.get().enchant()));
        PacketDistributor.sendToPlayer(player, new PetConfigSyncPayload(GettData.get().pets()));
        PacketDistributor.sendToPlayer(player, new ReinforceConfigSyncPayload(GettData.get().reinforce()));
        PacketDistributor.sendToPlayer(player, new EquipmentConfigSyncPayload(GettData.get().equipment()));
        PacketDistributor.sendToPlayer(player, new UpgradeConfigSyncPayload(GettData.get().upgrade()));
        PacketDistributor.sendToPlayer(player, new DetectorConfigSyncPayload(GettData.get().detector()));
        PacketDistributor.sendToPlayer(player, new org.gettserver.gettcore.network.WebConfigSyncPayload(GettData.get().web()));
    }

    /** Item presets first: shop entries and rewards that use preset:* ids are checked against them while loading. */
    public static void loadPresets(MinecraftServer server) {
        org.gettserver.gettcore.preset.ItemPresets.load(configDir(), server.registryAccess())
                .forEach(e -> GettCore.LOGGER.error("gettcore load: {}", e));
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        loadPresets(event.getServer());
        GettData.LoadReport r = GettData.get().load(configDir());
        r.errors().forEach(e -> GettCore.LOGGER.error("gettcore load: {}", e));
        GettCore.LOGGER.info("gettcore: 상점 {}, 대화 {}", r.shops(), r.dialogs());
    }
}
