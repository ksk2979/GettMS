package org.gettserver.gettcore.hunting;

import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import org.gettserver.gettcore.hunting.HuntingConfig.Zone;

/** Pure hunting-zone decisions (no level access) used by the hunting events and service. */
public final class HuntingRules {
    /** A player this many blocks around a zone (any axis) counts as near it. */
    public static final int NEAR_BLOCKS = 32;
    /** A variant this many blocks outside its zone (any axis) is removed. */
    public static final int STRAY_BLOCKS = 64;
    /**
     * Spawn types blocked inside zones: the natural ones, plus patrols and zombie reinforcements (they would bring
     * plain mobs into a zone). Spawners, eggs, commands, breeding, conversions etc. stay allowed.
     */
    static final Set<MobSpawnType> BLOCKED = Set.of(MobSpawnType.NATURAL, MobSpawnType.CHUNK_GENERATION,
            MobSpawnType.PATROL, MobSpawnType.REINFORCEMENT);

    private HuntingRules() {}

    /** Inside the zone's box (both corners inclusive) in its dimension. */
    public static boolean contains(Zone z, ResourceKey<Level> dim, BlockPos pos) {
        return z.dimension().equals(dim)
                && pos.getX() >= z.min().getX() && pos.getX() <= z.max().getX()
                && pos.getY() >= z.min().getY() && pos.getY() <= z.max().getY()
                && pos.getZ() >= z.min().getZ() && pos.getZ() <= z.max().getZ();
    }

    /** True when a spawn of {@code type} at {@code pos} must be canceled: natural-like, in a zone, not our variant. */
    public static boolean blocksSpawn(HuntingConfig cfg, ResourceKey<Level> dim, BlockPos pos, MobSpawnType type,
                                      boolean variant) {
        if (variant || !BLOCKED.contains(type)) return false;
        for (Zone z : cfg.zones()) {
            if (contains(z, dim, pos)) return true;
        }
        return false;
    }

    /** A player at (x, y, z) in {@code playerDim} is within {@link #NEAR_BLOCKS} of the zone's box. */
    public static boolean nearby(Zone z, ResourceKey<Level> playerDim, double x, double y, double zz) {
        return z.dimension().equals(playerDim) && within(z, x, y, zz, NEAR_BLOCKS);
    }

    /** A variant at (x, y, z) is more than {@link #STRAY_BLOCKS} outside its zone. */
    public static boolean strayed(Zone z, double x, double y, double zz) {
        return !within(z, x, y, zz, STRAY_BLOCKS);
    }

    /** The box grown by {@code pad} blocks on every side (block max is inclusive, so it ends at max + 1). */
    private static boolean within(Zone z, double x, double y, double zz, int pad) {
        return x >= z.min().getX() - pad && x <= z.max().getX() + 1 + pad
                && y >= z.min().getY() - pad && y <= z.max().getY() + 1 + pad
                && zz >= z.min().getZ() - pad && zz <= z.max().getZ() + 1 + pad;
    }

    /**
     * Weighted pick: {@code r} in [0, 1). Empty when there is nothing to pick (no entries or every weight is 0);
     * negative weights count as 0.
     */
    public static OptionalInt pickEntry(List<Integer> weights, double r) {
        long total = 0;
        for (int w : weights) total += Math.max(0, w);
        if (total <= 0) return OptionalInt.empty();
        double target = r * total;
        long acc = 0;
        int last = -1;
        for (int n = 0; n < weights.size(); n++) {
            int w = Math.max(0, weights.get(n));
            if (w == 0) continue;
            acc += w;
            last = n;
            if (target < acc) return OptionalInt.of(n);
        }
        return OptionalInt.of(last);
    }

    /** One more variant is due: below max, a player near, and the interval passed since the last refill. */
    public static boolean shouldRefill(int alive, int maxAlive, boolean playersNear, double secondsSinceLast,
                                       int intervalSeconds) {
        return alive < maxAlive && playersNear && secondsSinceLast >= intervalSeconds;
    }

    /** One decimal, no trailing ".0", never negative (damage line: {@code 피해 12.5 · 남은 체력 87.5/100}). */
    public static String number(double v) {
        double r = Math.round(Math.max(0, v) * 10) / 10.0;
        if (r == Math.rint(r)) return Long.toString((long) r);
        return String.format(Locale.ROOT, "%.1f", r);
    }
}
