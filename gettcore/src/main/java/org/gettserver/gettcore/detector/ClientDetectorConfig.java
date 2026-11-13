package org.gettserver.gettcore.detector;

/** Client-side copy of the server's {@link DetectorConfig}. Plain data; safe to load on servers. */
public final class ClientDetectorConfig {
    private static volatile DetectorConfig config = DetectorConfig.DEFAULT;

    private ClientDetectorConfig() {}

    public static void set(DetectorConfig value) {
        config = value;
    }

    public static DetectorConfig get() {
        return config;
    }
}
