package org.gettserver.gettcore.pet.client;

/** Pure fade math for {@link MobHealthBar}; no Minecraft imports so it is unit-testable. */
public final class HealthBarFade {
    private HealthBarFade() {}

    /**
     * 0 = hidden, 1 = fully visible. {@code healthRatio <= captureMax} → always 1; otherwise 1 up to
     * {@code fadeDelayTicks} after the hit, then a linear fade over {@code fadeTicks}, then 0.
     */
    public static float alpha(long ticksSinceHit, double healthRatio, int fadeDelayTicks, int fadeTicks,
                              double captureMax) {
        if (healthRatio <= captureMax) return 1f;
        if (ticksSinceHit <= fadeDelayTicks) return 1f;
        if (ticksSinceHit < (long) fadeDelayTicks + fadeTicks) {
            return 1f - (ticksSinceHit - fadeDelayTicks) / (float) fadeTicks;
        }
        return 0f;
    }
}
