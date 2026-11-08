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
    void noActiveSummons() {
        assertEquals(SummonResult.SUMMONED, PetService.decide(OWNED, Optional.empty(), 0));
    }
}
