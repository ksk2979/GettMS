package org.gettserver.gettcore.pet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class CaptureRulesTest {
    private static final PetConfig.Capture CAP = PetConfig.DEFAULT.capture();

    private static PetSpecies species(boolean boss, double obtainPercent) {
        return new PetSpecies("zombie", PetSpecies.Kind.FIGHTER,
                ResourceLocation.fromNamespaceAndPath("gettcore", "pet_zombie"),
                6, 0.3, 1, 0.6, 0.3, boss, 1, 1, 1, 0, obtainPercent);
    }

    private static CaptureRules.Verdict eval(Optional<PetSpecies> s, boolean owned, double ratio,
                                             CaptureTier tier, double roll) {
        return CaptureRules.evaluate(s, owned, ratio, tier, CAP, roll);
    }

    @Test
    void nonWhitelistedIsNotCapturable() {
        assertEquals(CaptureRules.Verdict.NOT_CAPTURABLE,
                eval(Optional.empty(), false, 0.1, CaptureTier.IRON, 0.0));
    }

    @Test
    void ownedIsRefused() {
        assertEquals(CaptureRules.Verdict.ALREADY_OWNED,
                eval(Optional.of(species(false, 0)), true, 0.1, CaptureTier.IRON, 0.0));
    }

    @Test
    void healthAbove20Refused() {
        assertEquals(CaptureRules.Verdict.HEALTH_OUT_OF_RANGE,
                eval(Optional.of(species(false, 0)), false, 0.21, CaptureTier.IRON, 0.0));
    }

    @Test
    void healthBelow1Refused() {
        assertEquals(CaptureRules.Verdict.HEALTH_OUT_OF_RANGE,
                eval(Optional.of(species(false, 0)), false, 0.005, CaptureTier.IRON, 0.0));
    }

    @Test
    void boundaryInclusive() {
        assertEquals(CaptureRules.Verdict.CAPTURED,
                eval(Optional.of(species(false, 0)), false, 0.01, CaptureTier.IRON, 0.0));
        assertEquals(CaptureRules.Verdict.CAPTURED,
                eval(Optional.of(species(false, 0)), false, 0.20, CaptureTier.IRON, 0.0));
    }

    @Test
    void ironRollBelow10Captures() {
        assertEquals(CaptureRules.Verdict.CAPTURED,
                eval(Optional.of(species(false, 0)), false, 0.1, CaptureTier.IRON, 0.099));
        assertEquals(CaptureRules.Verdict.FAILED,
                eval(Optional.of(species(false, 0)), false, 0.1, CaptureTier.IRON, 0.10));
    }

    @Test
    void bossUsesBossChance() {
        assertEquals(CaptureRules.Verdict.FAILED,
                eval(Optional.of(species(true, 0)), false, 0.1, CaptureTier.IRON, 0.06));
    }

    @Test
    void obtainUsesSpeciesPercent() {
        PetSpecies s = species(false, 20);
        assertEquals(true, CaptureRules.obtain(s, CAP, 0.19));
        assertEquals(false, CaptureRules.obtain(s, CAP, 0.20));
    }
}
