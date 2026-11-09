package org.gettserver.gettcore.farm.client;

/** Pure rules for the mature-crop highlight: maturity, scan range, scan cadence (no Minecraft types). */
public final class CropRipeness {
    public static final int RADIUS = 8;
    public static final int DY_MIN = -3;
    public static final int DY_MAX = 3;
    public static final int SCAN_INTERVAL = 10;
    public static final int MAX_BOXES = 256;

    private CropRipeness() {}

    public static boolean isMature(int age, int maxAge) {
        return maxAge > 0 && age >= maxAge;
    }

    /** Offset from the player's feet block: horizontal circle of {@link #RADIUS}, vertical DY_MIN..DY_MAX. */
    public static boolean inRange(int dx, int dy, int dz) {
        return dy >= DY_MIN && dy <= DY_MAX && dx * dx + dz * dz <= RADIUS * RADIUS;
    }

    public static boolean shouldScan(long tick) {
        return tick % SCAN_INTERVAL == 0;
    }

    /** Tag-fallback blocks that reach max age but have nothing to harvest (they turn into another block). */
    public static boolean excludedFromFallback(String blockPath) {
        return blockPath.startsWith("budding_");
    }
}
