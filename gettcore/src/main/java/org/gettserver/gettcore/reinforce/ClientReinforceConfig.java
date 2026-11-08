package org.gettserver.gettcore.reinforce;

/** Client-side copy of the server's {@link ReinforceConfig} (screen numbers). Plain data; safe to load on servers. */
public final class ClientReinforceConfig {
    private static volatile ReinforceConfig config = ReinforceConfig.DEFAULT;

    private ClientReinforceConfig() {}

    public static void set(ReinforceConfig value) {
        config = value;
    }

    public static ReinforceConfig get() {
        return config;
    }
}
