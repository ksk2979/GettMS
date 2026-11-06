package org.gettserver.gettcore.enchant;

/** Client-side copy of the server's {@link EnchantConfig} (tooltip numbers). Plain data; safe to load on servers. */
public final class ClientEnchantConfig {
    private static volatile EnchantConfig config = EnchantConfig.DEFAULT;

    private ClientEnchantConfig() {}

    public static void set(EnchantConfig value) {
        config = value;
    }

    public static EnchantConfig get() {
        return config;
    }
}
