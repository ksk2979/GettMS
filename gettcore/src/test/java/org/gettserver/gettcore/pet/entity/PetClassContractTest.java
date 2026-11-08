package org.gettserver.gettcore.pet.entity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The ten pet classes repeat the same forwarding overrides by hand (each extends a different Naturalist class);
 * this guards against one of them missing an override. Only reflects declared methods: no class initialization.
 */
class PetClassContractTest {
    private static final List<Class<?>> PETS = List.of(PetLion.class, PetTiger.class, PetBear.class,
            PetBlackBear.class, PetElephant.class, PetMammoth.class, PetHippo.class, PetGiraffe.class,
            PetOstrich.class, PetVulture.class);
    private static final List<String> REQUIRED = List.of("aiStep", "getVariantRegistryKey", "getDefaultVariant",
            "registerGoals", "mobInteract", "isInvulnerableTo", "hurt", "removeWhenFarAway",
            "getDefaultDimensions", "addAdditionalSaveData", "readAdditionalSaveData", "defineSynchedData",
            "startSeenByPlayer", "stopSeenByPlayer", "remove");

    @Test
    void everyPetDeclaresTheSharedOverrides() {
        for (Class<?> c : PETS) {
            assertTrue(GettPet.class.isAssignableFrom(c), c.getSimpleName() + " implements GettPet");
            Set<String> declared = Arrays.stream(c.getDeclaredMethods()).filter(m -> !m.isSynthetic())
                    .map(Method::getName).collect(Collectors.toSet());
            for (String name : REQUIRED) {
                assertTrue(declared.contains(name), c.getSimpleName() + " must override " + name);
            }
        }
    }
}
