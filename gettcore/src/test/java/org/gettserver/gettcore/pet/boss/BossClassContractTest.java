package org.gettserver.gettcore.pet.boss;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The six boss classes repeat the same forwarding overrides by hand (each extends a different Naturalist class);
 * this guards against one of them missing an override. Only reflects declared methods: no class initialization.
 */
class BossClassContractTest {
    private static final List<Class<?>> BOSSES = List.of(BossLion.class, BossTiger.class, BossBear.class,
            BossElephant.class, BossMammoth.class, BossVulture.class);
    private static final List<String> REQUIRED = List.of("getVariantRegistryKey", "getDefaultVariant",
            "registerGoals", "mobInteract", "removeWhenFarAway", "getDefaultLootTable", "dropFromLootTable",
            "addAdditionalSaveData", "readAdditionalSaveData", "defineSynchedData", "getDefaultDimensions",
            "startSeenByPlayer", "stopSeenByPlayer", "bossState", "tick", "isPersistenceRequired", "canBeLeashed",
            "isBaby", "onSyncedDataUpdated");

    @Test
    void everyBossDeclaresTheSharedOverrides() {
        for (Class<?> c : BOSSES) {
            assertTrue(GettBoss.class.isAssignableFrom(c), c.getSimpleName() + " implements GettBoss");
            Set<String> declared = Arrays.stream(c.getDeclaredMethods()).filter(m -> !m.isSynthetic())
                    .map(Method::getName).collect(Collectors.toSet());
            for (String name : REQUIRED) {
                assertTrue(declared.contains(name), c.getSimpleName() + " must override " + name);
            }
        }
    }
}
