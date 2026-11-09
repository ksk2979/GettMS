package org.gettserver.gettcore.upgrade;

/** Client-side copy of the server's {@link UpgradeConfig} (upgrade screen). Plain data; safe to load on servers. */
public final class ClientUpgradeConfig {
    private static volatile UpgradeConfig config = UpgradeConfig.DEFAULT;

    private ClientUpgradeConfig() {}

    public static void set(UpgradeConfig value) {
        config = value;
    }

    public static UpgradeConfig get() {
        return config;
    }
}
