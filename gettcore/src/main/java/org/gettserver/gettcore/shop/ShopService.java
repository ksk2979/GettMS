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
import org.gettserver.gettcore.data.PriceFluctuationConfig;
import org.gettserver.gettcore.data.ShopDefinition;
import org.gettserver.gettcore.data.ShopEntry;
import org.gettserver.gettcore.economy.EconomyData;
import org.gettserver.gettcore.economy.Gold;
import org.gettserver.gettcore.economy.Ledger;
import org.gettserver.gettcore.economy.RateLimiter;
import org.gettserver.gettcore.network.ShopEditPayload;
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
        PacketDistributor.sendToPlayer(player, new ShopSyncPayload(shopId, shop.get(), dynamicsOn(shop.get())));
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

    /** Whether this shop's prices can differ from the base prices (the client then shows ▲/▼ marks). */
    private static boolean dynamicsOn(ShopDefinition shop) {
        return dynamics().enabled() || fluctuates(shop);
    }

    private static boolean fluctuates(ShopDefinition shop) {
        return GettData.get().economy().fluctuates(shop);
    }

    /** Period of the last {@link #fluctuationTick}; Long.MIN_VALUE = none yet (no announcement on the first one). */
    private static long lastPeriod = Long.MIN_VALUE;

    /**
     * Timed market: puts this period's sell prices into every shop marked {@code fluctuate}. Cheap when nothing
     * changed, so it also picks up edited base prices and a switched-on shop within seconds.
     */
    public static void fluctuationTick() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !GettData.get().loaded()) return;
        PriceFluctuationConfig cfg = GettData.get().economy().priceFluctuation();
        if (!cfg.enabled()) {
            lastPeriod = Long.MIN_VALUE;
            return;
        }
        long period = PriceFluctuation.period(System.currentTimeMillis(), cfg);
        long seed = server.overworld().getSeed();
        boolean anyShop = false;
        for (ShopDefinition def : GettData.get().allShops()) {
            if (!def.fluctuate()) continue;
            anyShop = true;
            Map<ResourceLocation, GettData.CurrentPrices> prices = new java.util.LinkedHashMap<>();
            for (ShopEntry e : def.entries()) {
                long sell = e.canSell() ? PriceFluctuation.sellPrice(e, seed, def.id(), period, cfg) : e.sellPrice();
                if (e.currentBuyPrice() != e.buyPrice() || e.currentSellPrice() != sell) {
                    prices.put(e.item(), new GettData.CurrentPrices(e.buyPrice(), sell));
                }
            }
            if (!prices.isEmpty()) persistPricesAndSync(def.id(), prices);
        }
        if (anyShop && cfg.announce() && lastPeriod != Long.MIN_VALUE && period != lastPeriod) {
            server.getPlayerList().broadcastSystemMessage(Component.translatable("gettcore.msg.market_changed"), false);
        }
        lastPeriod = period;
    }

    /** Returns true only when the save succeeded and the resync ran. */
    private static boolean persistAndSync(ShopDefinition updated) {
        try {
            GettData.get().saveShop(updated);
        } catch (java.io.IOException e) {
            GettCore.LOGGER.error("gettcore: failed saving shop {}", updated.id(), e);
            return false;
        }
        syncOpen(updated);
        return true;
    }

    /** Price-only update: patched into the raw shop so entries for missing items stay on disk. */
    private static void persistPricesAndSync(String shopId, Map<ResourceLocation, GettData.CurrentPrices> prices) {
        try {
            GettData.get().updateCurrentPrices(shopId, prices).ifPresent(ShopService::syncOpen);
        } catch (java.io.IOException e) {
            GettCore.LOGGER.error("gettcore: failed saving prices of shop {}", shopId, e);
        }
    }

    private static void syncOpen(ShopDefinition updated) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (isOpen(p, updated.id())) {
                PacketDistributor.sendToPlayer(p, new ShopSyncPayload(updated.id(), updated, dynamicsOn(updated)));
            }
        }
    }

    /** Applies the 1-minute recovery step to every shop; call from the server tick. */
    public static void recoverTick() {
        PriceDynamicsConfig cfg = dynamics();
        if (!cfg.enabled()) return;
        for (ShopDefinition def : GettData.get().allShops()) {
            if (fluctuates(def)) continue;   // timed market prices are not pulled back to the base
            Map<ResourceLocation, GettData.CurrentPrices> prices = new java.util.LinkedHashMap<>();
            for (ShopEntry e : def.entries()) {
                if (e.currentBuyPrice() == e.buyPrice() && e.currentSellPrice() == e.sellPrice()) continue;
                long nb = PriceDynamics.recover(e.currentBuyPrice(), e.buyPrice(), 1 / 60.0, cfg);
                long ns = PriceDynamics.recover(e.currentSellPrice(), e.sellPrice(), 1 / 60.0, cfg);
                if (nb != e.currentBuyPrice() || ns != e.currentSellPrice()) {
                    prices.put(e.item(), new GettData.CurrentPrices(nb, ns));
                }
            }
            if (!prices.isEmpty()) persistPricesAndSync(def.id(), prices);
        }
    }

    public static void edit(ServerPlayer player, ShopEditPayload req) {
        if (!Perms.has(player, Perms.SHOP_EDIT)) {
            GettCore.LOGGER.warn("gettcore: {} tried to edit shop {} without permission",
                    player.getGameProfile().getName(), req.shopId());
            msg(player, "gettcore.msg.shop_edit_denied");
            return;
        }
        Optional<ShopDefinition> shop = GettData.get().shop(req.shopId());
        if (shop.isEmpty() || !isOpen(player, req.shopId())) {
            msg(player, "gettcore.msg.shop_edit_invalid");
            return;
        }
        ShopDefinition def = shop.get();
        ResourceLocation id = req.item();
        boolean validItem = BuiltInRegistries.ITEM.containsKey(id) && BuiltInRegistries.ITEM.get(id) != Items.AIR;
        boolean validPrices = req.buyPrice() >= 0 && req.buyPrice() <= Ledger.MAX_AMOUNT
                && req.sellPrice() >= 0 && req.sellPrice() <= Ledger.MAX_AMOUNT;
        boolean validTab = def.tabs().stream().anyMatch(t -> t.id().equals(req.tab()));
        ShopDefinition updated;
        switch (req.action()) {
            case REMOVE -> {
                if (!validItem) {
                    msg(player, "gettcore.msg.shop_edit_invalid");
                    return;
                }
                updated = def.withoutEntry(id);
            }
            case ADD -> {
                if (!validItem || !validPrices || !validTab) {
                    msg(player, "gettcore.msg.shop_edit_invalid");
                    return;
                }
                updated = def.withEntry(new ShopEntry(id, req.tab(), req.buyPrice(), req.sellPrice(),
                        req.canBuy(), req.canSell(), req.buyPrice(), req.sellPrice()));
            }
            case SET_PRICE -> {
                ShopEntry existing = def.entries().stream().filter(e -> e.item().equals(id)).findFirst().orElse(null);
                if (existing == null || !validItem || !validPrices) {
                    msg(player, "gettcore.msg.shop_edit_invalid");
                    return;
                }
                updated = def.withEntry(new ShopEntry(id, existing.tab(), req.buyPrice(), req.sellPrice(),
                        req.canBuy(), req.canSell(), req.buyPrice(), req.sellPrice()));
            }
            default -> {
                msg(player, "gettcore.msg.shop_edit_invalid");
                return;
            }
        }
        msg(player, persistAndSync(updated) ? "gettcore.msg.shop_saved" : "gettcore.msg.shop_save_failed");
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
            if (sellEntry(player, entry, req.count(), true) == null) msg(player, "gettcore.msg.nothing_to_sell");
        } else {
            buy(player, entry, req.count());
        }
    }

    private static String itemName(ResourceLocation id) {
        return BuiltInRegistries.ITEM.get(id).getDescription().getString();
    }

    /** What one sale moved: item count and gold earned. */
    private record Sold(int count, long gold) {
        static final Sold FAILED = new Sold(0, 0);
    }

    /**
     * Sells up to {@code wanted} of the entry's item. Null when the player has none; {@link Sold#FAILED} when the
     * payout failed (already reported). {@code announce}: send the per-item "sold" line (sell-all sends one summary).
     */
    private static Sold sellEntry(ServerPlayer player, ShopEntry entry, int wanted, boolean announce) {
        List<ItemStack> inv = player.getInventory().items;
        int n = Math.min(wanted, TradeCalculator.countSellable(inv, entry.item()));
        if (n <= 0) return null;
        long total = TradeCalculator.total(entry.currentSellPrice(), n);
        EconomyData data = EconomyData.get(player.server);
        if (data.ledger().deposit(player.getUUID(), total) != Ledger.Result.OK) {
            msg(player, "gettcore.msg.invalid_trade");
            return Sold.FAILED;
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
        if (announce) msg(player, "gettcore.msg.sold", itemName(entry.item()), n, Gold.format(total));
        PriceDynamicsConfig cfg = dynamics();
        if (cfg.enabled()) {
            // Re-read the live definition so consecutive entries in sellAll don't overwrite each other.
            String shopId = OPEN_SHOPS.get(player.getUUID());
            Optional<ShopDefinition> live = shopId == null ? Optional.empty() : GettData.get().shop(shopId);
            if (live.isPresent() && !fluctuates(live.get())) {
                long ns = PriceDynamics.afterSale(entry.currentSellPrice(), entry.sellPrice(), n, cfg);
                persistPricesAndSync(live.get().id(),
                        Map.of(entry.item(), new GettData.CurrentPrices(entry.currentBuyPrice(), ns)));
            }
        }
        return new Sold(n, total);
    }

    /** Sells everything the shop buys; one summary line (total item count and gold) instead of a line per item. */
    private static void sellAll(ServerPlayer player, ShopDefinition shop) {
        boolean any = false;
        long count = 0;
        long gold = 0;
        for (ShopEntry e : shop.entries()) {
            if (!e.canSell()) continue;
            Sold sold = sellEntry(player, e, MAX_TRADE, false);
            if (sold == null) continue;
            any = true;
            count += sold.count();
            gold += sold.gold();
        }
        if (!any) {
            msg(player, "gettcore.msg.nothing_to_sell");
        } else if (count > 0) {
            msg(player, "gettcore.msg.sold_all", count, Gold.format(gold));
        }
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
