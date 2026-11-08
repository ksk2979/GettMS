package org.gettserver.gettcore.client;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.pet.PetConfig;
import org.gettserver.gettcore.pet.PetSpecies;

/** Client-side copy of the server's {@link PetConfig}. Plain data; safe to load on servers. */
public final class ClientPetConfig {
    private static volatile PetConfig config = PetConfig.DEFAULT;

    private ClientPetConfig() {}

    public static void set(PetConfig value) {
        config = value;
    }

    public static PetConfig get() {
        return config;
    }

    public static Optional<PetSpecies> species(ResourceLocation wildEntityType) {
        return config.species(wildEntityType);
    }
}
