package org.gettserver.gettcore.pet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.gettserver.gettcore.pet.PetService.SummonResult;
import org.junit.jupiter.api.Test;

class PetServiceTest {
    private static final PetKey A = new PetKey("naturalist:lion", "");
    private static final PetKey B = new PetKey("naturalist:bear", "brown");
    private static final List<PetKey> OWNED = List.of(A, B);

    @Test
    void outOfRangeIndexIsInvalid() {
        assertEquals(SummonResult.INVALID_INDEX, PetService.decide(OWNED, Optional.empty(), -1));
        assertEquals(SummonResult.INVALID_INDEX, PetService.decide(OWNED, Optional.empty(), 2));
        assertEquals(SummonResult.INVALID_INDEX, PetService.decide(List.of(), Optional.of(A), 0));
    }

    @Test
    void activeSameAsChosenIsRecalled() {
        assertEquals(SummonResult.RECALLED, PetService.decide(OWNED, Optional.of(B), 1));
    }

    @Test
    void otherActiveBlocksSummon() {
        assertEquals(SummonResult.BLOCKED_ACTIVE, PetService.decide(OWNED, Optional.of(A), 1));
    }

    @Test
    void releaseValidIndex() {
        assertEquals(PetService.ReleaseResult.RELEASED, PetService.decideRelease(OWNED, 0));
        assertEquals(PetService.ReleaseResult.RELEASED, PetService.decideRelease(OWNED, 1));
    }

    @Test
    void releaseOutOfRangeIsInvalid() {
        assertEquals(PetService.ReleaseResult.INVALID_INDEX, PetService.decideRelease(OWNED, -1));
        assertEquals(PetService.ReleaseResult.INVALID_INDEX, PetService.decideRelease(OWNED, 2));
        assertEquals(PetService.ReleaseResult.INVALID_INDEX, PetService.decideRelease(List.of(), 0));
    }

    @Test
    void noActiveSummons() {
        assertEquals(SummonResult.SUMMONED, PetService.decide(OWNED, Optional.empty(), 0));
    }

    private static PetSpecies species(double attack) {
        return new PetSpecies("naturalist:vulture", PetSpecies.Kind.FLYING_MOUNT,
                net.minecraft.resources.ResourceLocation.parse("gettcore:pet_vulture"), attack, 0.3, 1, 0.6, 0.3, false,
                1, 1, 1, 0, 1);
    }

    @Test
    void reloadKeepsUnchangedSpecies() {
        assertEquals(PetService.ReloadAction.KEEP, PetService.reloadAction(species(6), Optional.of(species(6)), false));
        assertEquals(PetService.ReloadAction.KEEP, PetService.reloadAction(species(6), Optional.of(species(6)), true));
    }

    @Test
    void reloadRecallsChangedOrRemovedSpecies() {
        assertEquals(PetService.ReloadAction.RECALL, PetService.reloadAction(species(6), Optional.of(species(9)), false));
        assertEquals(PetService.ReloadAction.RECALL, PetService.reloadAction(species(6), Optional.empty(), false));
        assertEquals(PetService.ReloadAction.RECALL, PetService.reloadAction(null, Optional.of(species(6)), false));
    }

    @Test
    void reloadNeverRecallsARiddenFlyingMount() {
        assertEquals(PetService.ReloadAction.REAPPLY, PetService.reloadAction(species(6), Optional.of(species(9)), true));
        assertEquals(PetService.ReloadAction.REAPPLY, PetService.reloadAction(species(6), Optional.empty(), true));
    }
}
