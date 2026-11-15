package org.gettserver.gettcore.web;

/** Client-side copy of the server's {@link WebConfig}. Plain data; safe to load on servers. */
public final class ClientWebConfig {
    private static volatile WebConfig config = WebConfig.DEFAULT;

    private ClientWebConfig() {}

    public static void set(WebConfig value) {
        config = value;
    }

    public static WebConfig get() {
        return config;
    }
}
