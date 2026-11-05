package org.gettserver.gettcore.economy;

import org.gettserver.gettcore.data.EconomyConfig;

/** Client-side copy of the server config values the HUD needs. Plain data; safe to load on servers. */
public final class ClientConfig {
    private static volatile int hudOffsetX = EconomyConfig.DEFAULT.hudOffsetX();
    private static volatile int hudOffsetY = EconomyConfig.DEFAULT.hudOffsetY();
    private static volatile String currencyName = EconomyConfig.DEFAULT.currencyName();

    private ClientConfig() {}

    public static void set(int offsetX, int offsetY, String currency) {
        hudOffsetX = offsetX;
        hudOffsetY = offsetY;
        currencyName = currency;
    }

    public static int hudOffsetX() {
        return hudOffsetX;
    }

    public static int hudOffsetY() {
        return hudOffsetY;
    }

    public static String currencyName() {
        return currencyName;
    }
}
