package org.gettserver.gettcore.shop;

import java.util.Optional;
import org.gettserver.gettcore.data.PriceDynamicsConfig;

/** Pure price-dynamics math (no Minecraft imports). */
public final class PriceDynamics {
    private PriceDynamics() {}

    public static long afterSale(long current, long base, int count, PriceDynamicsConfig c) {
        if (!c.enabled()) return base;
        double v = current * (1.0 - c.sellDropPercentPerUnit() / 100.0 * count);
        double lo = base * c.minRatio();
        double hi = base * c.maxRatio();
        v = Math.max(lo, Math.min(hi, v));
        return Math.max(1L, Math.round(v));
    }

    /** Moves current toward base by base * recoverPercentPerHour% * hours, never overshooting. */
    public static long recover(long current, long base, double hours, PriceDynamicsConfig c) {
        if (!c.enabled()) return base;
        long step = Math.round(base * c.recoverPercentPerHour() / 100.0 * hours);
        if (step < 1) step = 1;
        if (current < base) return Math.min(base, current + step);
        if (current > base) return Math.max(base, current - step);
        return base;
    }

    public static Optional<Integer> delta(long current, long base) {
        return current == base ? Optional.empty() : Optional.of((int) (current - base));
    }
}
