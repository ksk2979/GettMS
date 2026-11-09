package org.gettserver.gettcore.mobs;

import java.util.Optional;

/**
 * Pure rules for mobs.json. Order for bosses: the override replaces the type's base value when the mob first appears,
 * then the boss multiplier is applied once on top (both steps are guarded by a flag, so a restart repeats neither).
 */
public final class MobStatsRules {
    private MobStatsRules() {}

    /**
     * Only a newly appearing (not loaded from disk), not yet handled mob that is no player, gettcore pet or hunting
     * variant (variants use their zone's absolute numbers).
     */
    public static boolean shouldApply(boolean alreadyApplied, boolean loadedFromDisk, boolean player, boolean pet,
                                      boolean variant) {
        return !alreadyApplied && !loadedFromDisk && !player && !pet && !variant;
    }

    /** The base value after the override (absent = the type's own default). */
    public static double base(double original, Optional<Double> override) {
        return override.orElse(original);
    }

    /** The boss multiplier on top of the (overridden) base; a non-positive multiplier is ignored. */
    public static double bossBase(double base, double mult) {
        return mult > 0 ? base * mult : base;
    }
}
