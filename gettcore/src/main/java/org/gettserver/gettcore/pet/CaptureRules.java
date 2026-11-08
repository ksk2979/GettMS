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
        return roll < chance(species.get(), tier, capture, healthRatio) / 100.0 ? Verdict.CAPTURED : Verdict.FAILED;
    }

    /**
     * Obtain percent for a mob caught with a {@code tier} ball: the species' {@code obtainChance} for that tier,
     * else the global table (the boss one for boss species); a tier missing everywhere is 0.
     */
    public static double obtainChance(PetSpecies species, CaptureTier tier, PetConfig.Capture capture) {
        Double own = species.obtainChance().get(tier);
        if (own != null) return own;
        Double global = (species.boss() ? capture.bossObtainChance() : capture.obtainChance()).get(tier);
        return global == null ? 0 : global;
    }

    /** Roll for obtaining the pet after a capture with a {@code tier} ball; @param roll uniform [0,1) */
    public static boolean obtain(PetSpecies species, CaptureTier tier, PetConfig.Capture capture, double roll) {
        return roll < obtainChance(species, tier, capture) / 100.0;
    }

    /**
     * Ball capture chance in percent at the given health: the tier's {@code base} at {@code maxPercent} health,
     * rising linearly to its {@code max} at {@code minPercent} (clamped outside the range). The tier's range is the
     * species' {@code ballChance} override, else the global table (the boss one for boss species); a missing tier
     * is 0.
     */
    public static double chance(PetSpecies species, CaptureTier tier, PetConfig.Capture capture, double healthRatio) {
        PetConfig.ChanceRange range = species.ballChance().get(tier);
        if (range == null) range = (species.boss() ? capture.bossBallChance() : capture.ballChance()).get(tier);
        if (range == null) return 0;
        double span = capture.maxPercent() - capture.minPercent();
        double t = span <= EPS ? 1 : (capture.maxPercent() - healthRatio * 100.0) / span;
        t = Math.max(0, Math.min(1, t));
        return range.base() + (range.max() - range.base()) * t;
    }
}
