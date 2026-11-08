package org.gettserver.gettcore.pet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PetKeyTest {
    @Test
    void baseCoatHasNoVariantName() {
        assertEquals(Optional.empty(), PetKey.variantPath("naturalist:lion", "naturalist:lion"));
        assertEquals(Optional.empty(), PetKey.variantPath("gettcore:boss_lion", "naturalist:lion"));
    }

    @Test
    void otherCoatIsNamedByItsPath() {
        assertEquals(Optional.of("white_tiger"), PetKey.variantPath("naturalist:tiger", "naturalist:white_tiger"));
        assertEquals(Optional.of("white_tiger"), PetKey.variantPath("gettcore:boss_tiger", "naturalist:white_tiger"));
    }

    @Test
    void blankAndDefaultHaveNoVariantName() {
        assertEquals(Optional.empty(), PetKey.variantPath("x", "default"));
        assertEquals(Optional.empty(), PetKey.variantPath("x", ""));
        assertEquals(Optional.empty(), new PetKey("naturalist:tiger", " ").variantPath());
    }

    @Test
    void hasVariantSkipsBlankAndDefault() {
        assertTrue(PetKey.hasVariant("naturalist:lion"));
        assertFalse(PetKey.hasVariant(""));
        assertFalse(PetKey.hasVariant(PetKey.DEFAULT_VARIANT));
    }
}
