package org.gettserver.gettcore.equipment;

/** Client-side copy of the server's {@link EquipmentConfig} (tooltips). Plain data; safe to load on servers. */
public final class ClientEquipmentConfig {
    private static volatile EquipmentConfig config = EquipmentConfig.DEFAULT;

    private ClientEquipmentConfig() {}

    public static void set(EquipmentConfig value) {
        config = value;
    }

    public static EquipmentConfig get() {
        return config;
    }
}
