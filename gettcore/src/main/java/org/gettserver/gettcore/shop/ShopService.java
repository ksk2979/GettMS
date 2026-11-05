package org.gettserver.gettcore.shop;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.data.ShopDefinition;
import org.gettserver.gettcore.network.ShopSyncPayload;
import org.gettserver.gettcore.perm.Perms;

public final class ShopService {
    private static final Map<UUID, String> OPEN_SHOPS = new HashMap<>();

    private ShopService() {}

    public static void open(ServerPlayer player, String shopId, ShopMode mode) {
        Optional<ShopDefinition> shop = GettData.get().shop(shopId);
        if (shop.isEmpty()) {
            GettCore.LOGGER.warn("Unknown shop {}", shopId);
            return;
        }
        boolean canEdit = Perms.has(player, Perms.SHOP_EDIT);
        // Close any open menu first: its removed() would otherwise clear the entry set below.
        player.closeContainer();
        OPEN_SHOPS.put(player.getUUID(), shopId);
        // Sync before the menu opens so the screen finds its definition on init.
        PacketDistributor.sendToPlayer(player, new ShopSyncPayload(shopId, shop.get(), false));
        var result = player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new ShopMenu(id, inv, shopId, mode, canEdit),
                Component.literal(shop.get().name())), buf -> {
            buf.writeUtf(shopId);
            buf.writeEnum(mode);
            buf.writeBoolean(canEdit);
        });
        if (result.isEmpty()) {
            OPEN_SHOPS.remove(player.getUUID());
        }
    }

    public static boolean isOpen(ServerPlayer player, String shopId) {
        return shopId.equals(OPEN_SHOPS.get(player.getUUID()));
    }

    public static void closed(ServerPlayer player) {
        OPEN_SHOPS.remove(player.getUUID());
    }

    /** Registered once at startup: /gett reload closes every open shop screen. */
    public static void registerReloadHook() {
        GettData.addReloadHook(() -> {
            try {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server == null) return;
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (p.containerMenu instanceof ShopMenu) {
                        p.closeContainer();
                    }
                }
            } catch (RuntimeException e) {
                GettCore.LOGGER.error("gettcore: failed closing shop menus on reload", e);
            }
        });
    }
}
