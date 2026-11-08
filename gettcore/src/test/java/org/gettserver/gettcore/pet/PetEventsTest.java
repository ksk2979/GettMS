package org.gettserver.gettcore.pet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class PetEventsTest {
    private static ResourceLocation id(String s) {
        return ResourceLocation.parse(s);
    }

    private static final Set<ResourceLocation> WHITELIST = Set.of(id("naturalist:lion"), id("gettcore:pet_lion"));
    private static final Predicate<ResourceLocation> LISTED = WHITELIST::contains;

    @Test
    void wildWhitelistedSpeciesIsBlocked() {
        assertTrue(PetEvents.shouldBlockInteract(id("naturalist:lion"), false, LISTED));
    }

    @Test
    void ourOwnPetIsNeverBlocked() {
        assertFalse(PetEvents.shouldBlockInteract(id("gettcore:pet_lion"), true, LISTED));
        assertFalse(PetEvents.shouldBlockInteract(id("gettcore:pet_lion"), false, LISTED));
    }

    @Test
    void notWhitelistedIsNotBlocked() {
        assertFalse(PetEvents.shouldBlockInteract(id("minecraft:wolf"), false, LISTED));
    }
}
