package org.gettserver.gettcore.shop;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.gettserver.gettcore.data.ShopDefinition;

/** Client-side copy of shop definitions received from the server. No client-only imports. */
public final class ClientShopCache {
    private static final Map<String, ShopDefinition> SHOPS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> DYNAMICS = new ConcurrentHashMap<>();

    private ClientShopCache() {}

    public static void set(String shopId, ShopDefinition def, boolean dynamics) {
        SHOPS.put(shopId, def);
        DYNAMICS.put(shopId, dynamics);
    }

    public static ShopDefinition get(String shopId) {
        return SHOPS.get(shopId);
    }

    public static boolean dynamicsEnabled(String shopId) {
        return DYNAMICS.getOrDefault(shopId, false);
    }
}
