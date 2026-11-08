package org.gettserver.gettcore.pet.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.world.entity.PlayerRideableJumping;
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
            "getDefaultDimensions", "addAdditionalSaveData", "readAdditionalSaveData", "defineSynchedData");
    private static final List<Class<?>> GROUND_MOUNTS = List.of(PetElephant.class, PetMammoth.class,
            PetGiraffe.class, PetOstrich.class);

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

    @Test
    void groundMountsJumpInstantlyWithoutAJumpBar() {
        for (Class<?> c : GROUND_MOUNTS) {
            assertTrue(declared(c).contains("tickRidden"), c.getSimpleName() + " must override tickRidden");
            if (c != PetOstrich.class) {
                assertFalse(PlayerRideableJumping.class.isAssignableFrom(c),
                        c.getSimpleName() + " must not be PlayerRideableJumping (no jump bar)");
            }
        }
        // Naturalist's Ostrich is PlayerRideableJumping itself: the pet must switch the bar off.
        assertTrue(declared(PetOstrich.class).containsAll(List.of("canJump", "onPlayerJump")));
    }

    @Test
    void noPetShowsABossBar() {
        for (Class<?> c : PETS) {
            assertFalse(declared(c).contains("startSeenByPlayer"), c.getSimpleName() + " has no owner boss bar");
        }
    }

    private static Set<String> declared(Class<?> c) {
        return Arrays.stream(c.getDeclaredMethods()).filter(m -> !m.isSynthetic())
                .map(Method::getName).collect(Collectors.toSet());
    }
}
