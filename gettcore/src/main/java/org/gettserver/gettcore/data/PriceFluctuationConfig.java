package org.gettserver.gettcore.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Timed market prices ({@code economy.json} → {@code priceFluctuation}): every {@code intervalMinutes} each sell
 * price of a shop marked {@code fluctuate} is re-drawn between {@code minPercent} and {@code maxPercent} of its base
 * price, in multiples of {@code roundTo}. Sales do not move these prices.
 */
public record PriceFluctuationConfig(boolean enabled, int intervalMinutes, double minPercent, double maxPercent,
                                     int roundTo, boolean announce) {
    public static final PriceFluctuationConfig DEFAULT = new PriceFluctuationConfig(true, 30, 70.0, 130.0, 10, true);

    public static final Codec<PriceFluctuationConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("enabled", DEFAULT.enabled()).forGetter(PriceFluctuationConfig::enabled),
            Codec.INT.optionalFieldOf("intervalMinutes", DEFAULT.intervalMinutes()).forGetter(PriceFluctuationConfig::intervalMinutes),
            Codec.DOUBLE.optionalFieldOf("minPercent", DEFAULT.minPercent()).forGetter(PriceFluctuationConfig::minPercent),
            Codec.DOUBLE.optionalFieldOf("maxPercent", DEFAULT.maxPercent()).forGetter(PriceFluctuationConfig::maxPercent),
            Codec.INT.optionalFieldOf("roundTo", DEFAULT.roundTo()).forGetter(PriceFluctuationConfig::roundTo),
            Codec.BOOL.optionalFieldOf("announce", DEFAULT.announce()).forGetter(PriceFluctuationConfig::announce)
    ).apply(i, PriceFluctuationConfig::new));

    /** Values forced into a usable range (interval 1 minute..7 days, 1%..1000%, min ≤ max, roundTo ≥ 1). */
    public PriceFluctuationConfig sanitized() {
        int interval = Math.max(1, Math.min(7 * 24 * 60, intervalMinutes));
        double lo = clamp(minPercent);
        double hi = clamp(maxPercent);
        if (lo > hi) {
            double t = lo;
            lo = hi;
            hi = t;
        }
        return new PriceFluctuationConfig(enabled, interval, lo, hi, Math.max(1, Math.min(1_000_000, roundTo)), announce);
    }

    private static double clamp(double percent) {
        return Double.isFinite(percent) ? Math.max(1.0, Math.min(1000.0, percent)) : 100.0;
    }
}
