package org.gettserver.gettcore.data;

import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/** Existence checks for registry entries; injectable so data loading can be unit tested without a bootstrapped registry. */
public record RegistryChecks(Predicate<ResourceLocation> itemExists, Predicate<ResourceLocation> entityExists) {
    public static final RegistryChecks ALL_EXIST = new RegistryChecks(rl -> true, rl -> true);

    /** Looks up the built-in registries; if they are not bootstrapped (plain unit test) everything is assumed to exist. */
    public static RegistryChecks live() {
        return new RegistryChecks(
                rl -> safe(() -> BuiltInRegistries.ITEM.containsKey(rl)),
                rl -> safe(() -> BuiltInRegistries.ENTITY_TYPE.containsKey(rl)));
    }

    private static boolean safe(java.util.function.BooleanSupplier check) {
        try {
            return check.getAsBoolean();
        } catch (Throwable t) {
            return true;
        }
    }
}
