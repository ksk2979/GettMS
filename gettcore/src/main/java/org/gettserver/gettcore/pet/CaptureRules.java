package org.gettserver.gettcore.pet;

import java.util.Optional;

/** Pure capture judgement; no world access so it is unit-testable. Percent values are 0-100. */
public final class CaptureRules {
    private static final double EPS = 1e-9;

    private CaptureRules() {}

    public enum Verdict { NOT_CAPTURABLE, ALREADY_OWNED, HEALTH_OUT_OF_RANGE, FAILED, CAPTURED }

    /** @param healthRatio current/max in [0,1]; @param roll uniform [0,1) */
    public static Verdict evaluate(Optional<PetSpecies> species, boolean hasOwner, double healthRatio,
                                   CaptureTier tier, PetConfig.Capture capture, double roll) {
        if (species.isEmpty()) return Verdict.NOT_CAPTURABLE;
        if (hasOwner) return Verdict.ALREADY_OWNED;
        double percent = healthRatio * 100.0;
        if (percent < capture.minPercent() - EPS || percent > capture.maxPercent() + EPS) {
            return Verdict.HEALTH_OUT_OF_RANGE;
        }
        return roll < chance(species.get(), tier, capture) / 100.0 ? Verdict.CAPTURED : Verdict.FAILED;
    }

    /** Roll for obtaining the pet after a kill-capture; species percent (if &gt; 0) overrides the global one. */
    public static boolean obtain(PetSpecies species, PetConfig.Capture capture, double roll) {
        double percent = species.obtainPercent() > 0 ? species.obtainPercent() : capture.obtainPercent();
        return roll < percent / 100.0;
    }

    /** Ball capture chance in percent; boss species use the boss table, missing tier is 0. */
    public static double chance(PetSpecies species, CaptureTier tier, PetConfig.Capture capture) {
        var table = species.boss() ? capture.bossBallChance() : capture.ballChance();
        return table.getOrDefault(tier, 0.0);
    }
}
