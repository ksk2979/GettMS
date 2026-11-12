package org.gettserver.gettcore.pet.boss;

import java.util.List;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import org.gettserver.gettcore.pet.PetRegistry;

/**
 * Creative-tab spawn eggs for every boss. An egg-spawned boss has an empty pointId, which BossSpawner.keepOnLoad
 * keeps and which has no leash point, so no extra code is needed for it.
 */
public final class BossEggs {
    public static final List<DeferredItem<DeferredSpawnEggItem>> ITEMS = List.of(
            egg("lion", PetRegistry.BOSS_LION, 0xD9A441, 0x7A3B1E),
            egg("tiger", PetRegistry.BOSS_TIGER, 0xE08A1E, 0x2B2B2B),
            egg("bear", PetRegistry.BOSS_BEAR, 0x5A3A22, 0x2A1A10),
            egg("elephant", PetRegistry.BOSS_ELEPHANT, 0x8C8C94, 0x4A4A52),
            egg("mammoth", PetRegistry.BOSS_MAMMOTH, 0x6B4A2E, 0xE8DCC0),
            egg("vulture", PetRegistry.BOSS_VULTURE, 0x3A2F2A, 0xC9B8A0),
            egg("giraffe", PetRegistry.BOSS_GIRAFFE, 0xE3B64F, 0x6B3F1D));

    private BossEggs() {}

    /** Loads the class so the eggs are queued on PetRegistry.ITEMS before it registers. */
    public static void bootstrap() {}

    private static DeferredItem<DeferredSpawnEggItem> egg(
            String name, DeferredHolder<EntityType<?>, ? extends EntityType<? extends Mob>> type, int base, int spots) {
        return PetRegistry.ITEMS.register("boss_" + name + "_spawn_egg",
                () -> new DeferredSpawnEggItem(type, base, spots, new Item.Properties()));
    }
}
