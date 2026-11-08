package org.gettserver.gettcore.pet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
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
    void ironAtHp20RollBelowBaseCaptures() {
        // hp 20% (= maxPercent) uses the iron base chance, 5%.
        assertEquals(CaptureRules.Verdict.CAPTURED,
                eval(Optional.of(species(false, 0)), false, 0.2, CaptureTier.IRON, 0.049));
        assertEquals(CaptureRules.Verdict.FAILED,
                eval(Optional.of(species(false, 0)), false, 0.2, CaptureTier.IRON, 0.05));
    }

    @Test
    void lowerHealthRaisesChance() {
        // hp 1% (= minPercent) uses the iron max chance, 25%.
        assertEquals(CaptureRules.Verdict.CAPTURED,
                eval(Optional.of(species(false, 0)), false, 0.01, CaptureTier.IRON, 0.249));
        assertEquals(CaptureRules.Verdict.FAILED,
                eval(Optional.of(species(false, 0)), false, 0.01, CaptureTier.IRON, 0.25));
    }

    @Test
    void bossUsesBossChance() {
        // boss iron base is 3%; the normal table (5%) would capture at this roll.
        assertEquals(CaptureRules.Verdict.FAILED,
                eval(Optional.of(species(true, 0)), false, 0.2, CaptureTier.IRON, 0.04));
        assertEquals(CaptureRules.Verdict.CAPTURED,
                eval(Optional.of(species(false, 0)), false, 0.2, CaptureTier.IRON, 0.04));
    }

    @Test
    void chanceScalesWithHealth() {
        PetSpecies s = species(false, 0);
        assertEquals(5.0, CaptureRules.chance(s, CaptureTier.IRON, CAP, 0.20), 1e-9);
        assertEquals(25.0, CaptureRules.chance(s, CaptureTier.IRON, CAP, 0.01), 1e-9);
        assertEquals(15.0, CaptureRules.chance(s, CaptureTier.IRON, CAP, 0.105), 1e-9);
        assertEquals(45.0, CaptureRules.chance(s, CaptureTier.NETHERITE, CAP, 0.105), 1e-9);
        // outside the range the chance is clamped to base/max
        assertEquals(5.0, CaptureRules.chance(s, CaptureTier.IRON, CAP, 0.9), 1e-9);
        assertEquals(25.0, CaptureRules.chance(s, CaptureTier.IRON, CAP, 0.0), 1e-9);
        PetSpecies boss = species(true, 0);
        assertEquals(3.0, CaptureRules.chance(boss, CaptureTier.IRON, CAP, 0.20), 1e-9);
        assertEquals(40.0, CaptureRules.chance(boss, CaptureTier.NETHERITE, CAP, 0.01), 1e-9);
    }

    @Test
    void legacyNumberIsFlat() {
        PetConfig cfg = PetConfig.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"capture\":{\"ballChance\":{\"iron\":10}}}")).getOrThrow();
        PetSpecies s = species(false, 0);
        assertEquals(10.0, CaptureRules.chance(s, CaptureTier.IRON, cfg.capture(), 0.20), 1e-9);
        assertEquals(10.0, CaptureRules.chance(s, CaptureTier.IRON, cfg.capture(), 0.01), 1e-9);
    }

    @Test
    void equalMinMaxPercentUsesMax() {
        var cap = new PetConfig.Capture(10, 10, CAP.ballChance(), CAP.bossBallChance(), 20);
        assertEquals(25.0, CaptureRules.chance(species(false, 0), CaptureTier.IRON, cap, 0.10), 1e-9);
    }

    @Test
    void obtainUsesSpeciesPercent() {
        PetSpecies s = species(false, 20);
        assertEquals(true, CaptureRules.obtain(s, CAP, 0.19));
        assertEquals(false, CaptureRules.obtain(s, CAP, 0.20));
    }
}
