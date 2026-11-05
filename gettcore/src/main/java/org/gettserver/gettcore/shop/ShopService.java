package org.gettserver.gettcore.shop;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.data.PriceDynamicsConfig;
import org.gettserver.gettcore.data.ShopDefinition;
import org.gettserver.gettcore.data.ShopEntry;
import org.gettserver.gettcore.economy.EconomyData;
import org.gettserver.gettcore.economy.Gold;
import org.gettserver.gettcore.economy.Ledger;
import org.gettserver.gettcore.economy.RateLimiter;
import org.gettserver.gettcore.network.ShopSyncPayload;
import org.gettserver.gettcore.network.TradePayload;
import org.gettserver.gettcore.perm.Perms;

public final class ShopService {
    private static final Map<UUID, String> OPEN_SHOPS = new HashMap<>();

    private static final int MAX_TRADE = 64 * 36;
    private static final RateLimiter LIMITER = new RateLimiter(50);

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
        PacketDistributor.sendToPlayer(player, new ShopSyncPayload(shopId, shop.get(), dynamicsOn()));
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

    private static PriceDynamicsConfig dynamics() {
        return GettData.get().economy().priceDynamics();
    }

    private static boolean dynamicsOn() {
        return dynamics().enabled();
    }

    private static void persistAndSync(ShopDefinition updated) {
        try {
            GettData.get().saveShop(updated);
        } catch (java.io.IOException e) {
            GettCore.LOGGER.error("gettcore: failed saving shop {}", updated.id(), e);
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (isOpen(p, updated.id())) {
                PacketDistributor.sendToPlayer(p, new ShopSyncPayload(updated.id(), updated, dynamicsOn()));
            }
        }
    }

    /** Applies the 1-minute recovery step to every shop; call from the server tick. */
    public static void recoverTick() {
        PriceDynamicsConfig cfg = dynamics();
        if (!cfg.enabled()) return;
        for (ShopDefinition def : GettData.get().allShops()) {
            ShopDefinition updated = def;
            boolean changed = false;
            for (ShopEntry e : def.entries()) {
                if (e.currentBuyPrice() == e.buyPrice() && e.currentSellPrice() == e.sellPrice()) continue;
                long nb = PriceDynamics.recover(e.currentBuyPrice(), e.buyPrice(), 1 / 60.0, cfg);
                long ns = PriceDynamics.recover(e.currentSellPrice(), e.sellPrice(), 1 / 60.0, cfg);
                if (nb != e.currentBuyPrice() || ns != e.currentSellPrice()) {
                    updated = updated.withCurrentPrices(e.item(), nb, ns);
                    changed = true;
                }
            }
            if (changed) persistAndSync(updated);
        }
    }

    public static boolean isOpen(ServerPlayer player, String shopId) {
        return shopId.equals(OPEN_SHOPS.get(player.getUUID()));
    }

    public static void closed(ServerPlayer player) {
        OPEN_SHOPS.remove(player.getUUID());
    }

    private static void msg(ServerPlayer p, String key, Object... args) {
        p.sendSystemMessage(Component.translatable(key, args));
    }

    public static void trade(ServerPlayer player, TradePayload req) {
        if (!isOpen(player, req.shopId())) return;
        if (!LIMITER.tryAcquire(player.getUUID(), System.currentTimeMillis())) {
            msg(player, "gettcore.msg.too_fast");
            return;
        }
        Optional<ShopDefinition> shop = GettData.get().shop(req.shopId());
        if (shop.isEmpty()) {
            msg(player, "gettcore.msg.invalid_trade");
            return;
        }
        if (req.sellAll()) {
            if (req.mode() != ShopMode.SELL) {
                msg(player, "gettcore.msg.invalid_trade");
                return;
            }
            sellAll(player, shop.get());
            return;
        }
        ShopEntry entry = null;
        for (ShopEntry e : shop.get().entries()) {
            if (e.item().equals(req.item())) {
                entry = e;
                break;
            }
        }
        boolean allowed = entry != null && (req.mode() == ShopMode.SELL ? entry.canSell() : entry.canBuy());
        if (!allowed || req.count() < 1 || req.count() > MAX_TRADE) {
            msg(player, "gettcore.msg.invalid_trade");
            return;
        }
        if (req.mode() == ShopMode.SELL) {
            if (sellEntry(player, entry, req.count()) < 0) msg(player, "gettcore.msg.nothing_to_sell");
        } else {
            buy(player, entry, req.count());
        }
    }

    private static String itemName(ResourceLocation id) {
        return BuiltInRegistries.ITEM.get(id).getDescription().getString();
    }

    /** Returns gold earned, or -1 when nothing was sold. */
    private static long sellEntry(ServerPlayer player, ShopEntry entry, int wanted) {
        List<ItemStack> inv = player.getInventory().items;
        int n = Math.min(wanted, TradeCalculator.countSellable(inv, entry.item()));
        if (n <= 0) return -1;
        long total = TradeCalculator.total(entry.currentSellPrice(), n);
        EconomyData data = EconomyData.get(player.server);
        if (data.ledger().deposit(player.getUUID(), total) != Ledger.Result.OK) {
            msg(player, "gettcore.msg.invalid_trade");
            return 0;
        }
        int left = n;
        for (ItemStack s : inv) {
            if (left <= 0) break;
            if (TradeCalculator.matches(s, entry.item())) {
                int take = Math.min(left, s.getCount());
                s.shrink(take);
                left -= take;
            }
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        data.changed(player.getUUID());
        msg(player, "gettcore.msg.sold", itemName(entry.item()), n, Gold.format(total));
        PriceDynamicsConfig cfg = dynamics();
        if (cfg.enabled()) {
            // Re-read the live definition so consecutive entries in sellAll don't overwrite each other.
            String shopId = OPEN_SHOPS.get(player.getUUID());
            Optional<ShopDefinition> live = shopId == null ? Optional.empty() : GettData.get().shop(shopId);
            if (live.isPresent()) {
                long ns = PriceDynamics.afterSale(entry.currentSellPrice(), entry.sellPrice(), n, cfg);
                persistAndSync(live.get().withCurrentPrices(entry.item(), entry.currentBuyPrice(), ns));
            }
        }
        return total;
    }

    private static void sellAll(ServerPlayer player, ShopDefinition shop) {
        boolean any = false;
        for (ShopEntry e : shop.entries()) {
            if (!e.canSell()) continue;
            if (sellEntry(player, e, MAX_TRADE) >= 0) any = true;
        }
        if (!any) msg(player, "gettcore.msg.nothing_to_sell");
    }

    private static void buy(ServerPlayer player, ShopEntry entry, int count) {
        Item item = BuiltInRegistries.ITEM.get(entry.item());
        if (item == Items.AIR || !BuiltInRegistries.ITEM.containsKey(entry.item())) {
            msg(player, "gettcore.msg.invalid_trade");
            return;
        }
        int maxStack = item.getDefaultMaxStackSize();
        int capacity = TradeCalculator.capacityFor(player.getInventory().items, item, maxStack);
        EconomyData data = EconomyData.get(player.server);
        long price = entry.currentBuyPrice();
        if (price <= 0) {
            msg(player, "gettcore.msg.invalid_trade");
            return;
        }
        int n = TradeCalculator.maxBuyable(data.ledger().get(player.getUUID()), price, capacity, count);
        if (n <= 0) {
            msg(player, capacity <= 0 ? "gettcore.msg.inventory_full" : "gettcore.msg.insufficient");
            return;
        }
        long total = TradeCalculator.total(price, n);
        if (data.ledger().withdraw(player.getUUID(), total) != Ledger.Result.OK) {
            msg(player, "gettcore.msg.insufficient");
            return;
        }
        int placed = TradeCalculator.distribute(player.getInventory().items, item, maxStack, n);
        if (placed < n) {
            data.ledger().deposit(player.getUUID(), TradeCalculator.total(price, n - placed));
            total = TradeCalculator.total(price, placed);
        }
        if (placed <= 0) {
            data.changed(player.getUUID());
            msg(player, "gettcore.msg.inventory_full");
            return;
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        data.changed(player.getUUID());
        msg(player, "gettcore.msg.bought", itemName(entry.item()), placed, Gold.format(total));
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
