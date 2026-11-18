package org.gettserver.gettcore.craft;

/** Client-side copy of the server's {@link CraftConfig} (crafting screen). Plain data; safe to load on servers. */
public final class ClientCraftConfig {
    private static volatile CraftConfig config = CraftConfig.DEFAULT;

    private ClientCraftConfig() {}

    public static void set(CraftConfig value) {
        config = value;
    }

    public static CraftConfig get() {
        return config;
    }
}
