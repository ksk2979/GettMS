package org.gettserver.gettcore.pet.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.pet.PetConfig;
import org.gettserver.gettcore.pet.PetSpecies;
import org.gettserver.gettcore.pet.client.CaptureAim.Status;
import org.junit.jupiter.api.Test;

/** Pure parts of the capture-ball aim: zoom easing and the HUD status decision. */
class CaptureAimTest {
    private static final PetConfig.Capture CAP = PetConfig.DEFAULT.capture();

    private static Optional<PetSpecies> species() {
        return Optional.of(new PetSpecies("a:w", PetSpecies.Kind.FIGHTER,
                ResourceLocation.fromNamespaceAndPath("a", "p"), 2, 0.3, 1, 0.6, 0.3, false,
                1, 1, 1, 0, 0));
    }

    @Test
    void zoomFactorEndpoints() {
        assertEquals(1.0, CaptureAim.zoomFactor(0f), 1e-9);
        assertEquals(0.8, CaptureAim.zoomFactor(1f), 1e-6);
    }

    @Test
    void zoomFactorClamps() {
        assertEquals(1.0, CaptureAim.zoomFactor(-1f), 1e-9);
        assertEquals(0.8, CaptureAim.zoomFactor(2f), 1e-6);
    }

    @Test
    void zoomFactorDecreasesMonotonically() {
        double prev = 1.0;
        for (int i = 1; i <= 10; i++) {
            double z = CaptureAim.zoomFactor(i / 10f);
            assertTrue(z < prev, "progress " + i / 10f);
            prev = z;
        }
    }

    @Test
    void stepReachesFullInFiveTicksAndBack() {
        float p = 0;
        for (int i = 0; i < 5; i++) p = CaptureAim.step(p, true);
        assertEquals(1f, p, 1e-6);
        assertEquals(1f, CaptureAim.step(p, true), 1e-6);
        for (int i = 0; i < 5; i++) p = CaptureAim.step(p, false);
        assertEquals(0f, p, 1e-6);
        assertEquals(0f, CaptureAim.step(p, false), 1e-6);
    }

    @Test
    void statusNotSpecies() {
        assertEquals(Status.NOT_CAPTURABLE, CaptureAim.status(Optional.empty(), false, 0.1, CAP));
    }

    @Test
    void statusOwnedWinsEvenForNonSpecies() {
        assertEquals(Status.OWNED, CaptureAim.status(Optional.empty(), true, 0.1, CAP));
        assertEquals(Status.OWNED, CaptureAim.status(species(), true, 0.1, CAP));
    }

    @Test
    void statusHealthTooHigh() {
        assertEquals(Status.HEALTH_TOO_HIGH, CaptureAim.status(species(), false, 0.5, CAP));
        assertEquals(Status.HEALTH_TOO_HIGH, CaptureAim.status(species(), false, 0.201, CAP));
    }

    @Test
    void statusHealthTooLowBelowMinPercent() {
        assertEquals(Status.HEALTH_TOO_LOW, CaptureAim.status(species(), false, 0.005, CAP));
    }

    @Test
    void statusCapturableInRangeIncludingBounds() {
        assertEquals(Status.CAPTURABLE, CaptureAim.status(species(), false, 0.20, CAP));
        assertEquals(Status.CAPTURABLE, CaptureAim.status(species(), false, 0.10, CAP));
        assertEquals(Status.CAPTURABLE, CaptureAim.status(species(), false, 0.01, CAP));
    }
}
