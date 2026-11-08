package org.gettserver.gettcore.pet.client;

import java.util.Optional;
import org.gettserver.gettcore.pet.PetConfig;
import org.gettserver.gettcore.pet.PetSpecies;

/**
 * Pure state and rules of capture-ball aiming (right click held): the zoom easing and the HUD status. No client
 * classes so it is unit-testable; {@link CaptureAimClient} drives it from client events.
 */
public final class CaptureAim {
    /** Zoomed FOV multiplier at full aim. */
    static final double FULL_ZOOM = 0.8;
    /** Ticks to ease fully in or out. */
    static final int EASE_TICKS = 5;
    private static final double EPS = 1e-9;

    private static float prevProgress;
    private static float progress;

    private CaptureAim() {}

    /** What the aim HUD shows for the looked-at living entity. */
    public enum Status { CAPTURABLE, HEALTH_TOO_HIGH, HEALTH_TOO_LOW, NOT_CAPTURABLE, OWNED }

    /** FOV multiplier for an aim progress in [0,1] (clamped): 1.0 at 0, {@link #FULL_ZOOM} at 1, smoothstep between. */
    public static double zoomFactor(float progress) {
        double p = Math.max(0, Math.min(1, progress));
        double eased = p * p * (3 - 2 * p);
        return 1.0 - (1.0 - FULL_ZOOM) * eased;
    }

    /** One client tick of easing toward aimed (1) or not (0). */
    public static float step(float progress, boolean aiming) {
        float next = progress + (aiming ? 1f : -1f) / EASE_TICKS;
        return Math.max(0f, Math.min(1f, next));
    }

    /** Advances the shared easing state; call once per client tick. */
    public static void tick(boolean aiming) {
        prevProgress = progress;
        progress = step(progress, aiming);
    }

    /** Current FOV multiplier, interpolated by the frame's partial tick. */
    public static double currentZoom(float partialTick) {
        return zoomFactor(prevProgress + (progress - prevProgress) * partialTick);
    }

    public static void reset() {
        prevProgress = 0;
        progress = 0;
    }

    /**
     * HUD status, mirroring the server's capture judgement except that an owner is reported first (our pets are not
     * a species, and "owned" says more than "not capturable").
     *
     * @param healthRatio current/max health in [0,1]
     */
    public static Status status(Optional<PetSpecies> species, boolean owned, double healthRatio,
                                PetConfig.Capture capture) {
        if (owned) return Status.OWNED;
        if (species.isEmpty()) return Status.NOT_CAPTURABLE;
        double percent = healthRatio * 100.0;
        if (percent > capture.maxPercent() + EPS) return Status.HEALTH_TOO_HIGH;
        if (percent < capture.minPercent() - EPS) return Status.HEALTH_TOO_LOW;
        return Status.CAPTURABLE;
    }
}
