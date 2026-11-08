package org.gettserver.gettcore.pet.entity;

import java.util.UUID;
import org.gettserver.gettcore.pet.PetKey;
import org.gettserver.gettcore.pet.PetSpecies;
import org.jetbrains.annotations.Nullable;

/**
 * A Gett-owned pet entity. Such entities always count as already owned when hit by a capture ball.
 * Implementations only hold a {@link PetState}; everything else is shared through these defaults.
 */
public interface GettPet {
    /** Per-entity pet state (owner, key, boss flag, scale, flight input). Never null. */
    PetState petState();

    /** The owning player's UUID, or null before {@link #setOwner}. */
    @Nullable
    default UUID ownerId() {
        return petState().ownerId();
    }

    /** Species + variant this pet was summoned as, or null before {@link #setOwner}. */
    @Nullable
    default PetKey key() {
        return petState().key();
    }

    default boolean isBossPet() {
        return petState().boss();
    }

    default PetSpecies.Kind kind() {
        return petState().kind();
    }

    /** Called by the pet service right after spawning: binds the owner, applies variant, stats and boss scale. */
    default void setOwner(UUID id, PetKey key, boolean boss) {
        petState().setOwner(id, key, boss);
    }

    /** Ascend/descend keys of the rider; ignored unless this is a {@link PetSpecies.Kind#FLYING_MOUNT}. */
    default void setFlightInput(boolean up, boolean down) {
        petState().setFlightInput(up, down);
    }
}
