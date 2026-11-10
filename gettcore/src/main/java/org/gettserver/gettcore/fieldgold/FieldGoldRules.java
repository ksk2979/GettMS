package org.gettserver.gettcore.fieldgold;

import java.util.Set;
import net.minecraft.world.entity.MobSpawnType;

/** Which spawns may pay field gold. */
public final class FieldGoldRules {
    private FieldGoldRules() {}

    /** persistentData flag set on mobs from {@link #NO_GOLD} spawns. */
    public static final String NO_GOLD_TAG = "gettcore_no_field_gold";

    /**
     * Farms and made-up mobs: spawners (incl. trial), eggs, commands, buckets, dispensers, breeding, mobs summoned by
     * other mobs, and EVENT (hunting zone refills and our own spawns, which pay through their own systems).
     * Natural, chunk-generation, structure, patrol, reinforcement, jockey and conversion spawns pay.
     */
    static final Set<MobSpawnType> NO_GOLD = Set.of(MobSpawnType.SPAWNER, MobSpawnType.TRIAL_SPAWNER,
            MobSpawnType.SPAWN_EGG, MobSpawnType.COMMAND, MobSpawnType.BUCKET, MobSpawnType.DISPENSER,
            MobSpawnType.BREEDING, MobSpawnType.MOB_SUMMONED, MobSpawnType.EVENT, MobSpawnType.TRIGGERED);

    public static boolean paysFor(MobSpawnType type) {
        return !NO_GOLD.contains(type);
    }
}
