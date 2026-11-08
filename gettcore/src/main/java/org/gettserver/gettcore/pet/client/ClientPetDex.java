package org.gettserver.gettcore.pet.client;

import java.util.List;
import java.util.Optional;
import org.gettserver.gettcore.pet.PetKey;

/** Client-side copy of the player's pet dex (owned kinds, boss flags, active pet). Plain data; safe on servers. */
public final class ClientPetDex {
    private static volatile List<PetKey> owned = List.of();
    private static volatile List<Boolean> boss = List.of();
    private static volatile Optional<PetKey> active = Optional.empty();

    private ClientPetDex() {}

    public static void set(List<PetKey> ownedKeys, List<Boolean> bossFlags, Optional<PetKey> activeKey) {
        owned = List.copyOf(ownedKeys);
        boss = List.copyOf(bossFlags);
        active = activeKey;
    }

    /** Owned kinds in dex (acquisition) order. */
    public static List<PetKey> owned() {
        return owned;
    }

    /** {@code boss().get(i)} is the boss flag of {@code owned().get(i)}. */
    public static List<Boolean> boss() {
        return boss;
    }

    public static Optional<PetKey> active() {
        return active;
    }
}
