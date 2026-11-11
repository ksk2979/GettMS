package org.gettserver.gettcore.shop;

import org.gettserver.gettcore.data.PriceFluctuationConfig;
import org.gettserver.gettcore.data.ShopEntry;

/**
 * Timed market prices, pure math. A price is a function of (seed, shop, item, period), so nothing has to be stored
 * to know what the price of the current period is: a restart or a config reload lands on the same prices, and the
 * period flips on the wall clock (every 30 minutes = at :00 and :30).
 */
public final class PriceFluctuation {
    private PriceFluctuation() {}

    /** Index of the period that contains this instant. */
    public static long period(long epochMillis, PriceFluctuationConfig c) {
        return Math.floorDiv(epochMillis, c.intervalMinutes() * 60_000L);
    }

    /** Milliseconds until the next period starts. */
    public static long millisLeft(long epochMillis, PriceFluctuationConfig c) {
        long len = c.intervalMinutes() * 60_000L;
        return len - Math.floorMod(epochMillis, len);
    }

    /**
     * This period's sell price of the entry: one of the multiples of {@code roundTo} between min% and max% of the
     * base price, each equally likely, and never below {@code roundTo}. A base price too small to have such a step
     * (or 0) stays as it is. When the item can also be bought here, the sell price never exceeds the buy price —
     * otherwise buying and selling back would print gold.
     */
    public static long sellPrice(ShopEntry e, long seed, String shopId, long period, PriceFluctuationConfig c) {
        long base = e.sellPrice();
        long step = c.roundTo();
        if (base < step) return base;
        long lo = Math.max(ceilTo(base * c.minPercent() / 100.0, step), step);
        long hi = floorTo(base * c.maxPercent() / 100.0, step);
        long price;
        if (hi < lo) {
            price = Math.max(step, Math.round((double) base / step) * step);
        } else {
            long choices = (hi - lo) / step + 1;
            price = Math.min(hi, lo + step * (long) (unit(seed, shopId, e.item().toString(), period) * choices));
        }
        if (e.canBuy() && e.buyPrice() > 0 && price > e.buyPrice()) {
            long capped = floorTo(e.buyPrice(), step);
            price = capped >= step ? capped : e.buyPrice();
        }
        return price;
    }

    private static long ceilTo(double v, long step) {
        return (long) Math.ceil(v / step - 1e-9) * step;
    }

    private static long floorTo(double v, long step) {
        return (long) Math.floor(v / step + 1e-9) * step;
    }

    /** Uniform in [0, 1), fixed for (seed, shop, item, period). */
    static double unit(long seed, String shopId, String item, long period) {
        long h = mix(seed ^ shopId.hashCode());
        h = mix(h ^ item.hashCode());
        h = mix(h ^ period);
        return (h >>> 11) * 0x1.0p-53;
    }

    /** SplitMix64 finalizer. */
    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
