package org.gettserver.gettcore.protect;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Explosion;
import org.gettserver.gettcore.protect.ProtectConfig.Flags;

/** Pure protection decisions (no level access) used by {@link ProtectEvents}. */
public final class ProtectRules {
    private ProtectRules() {}

    /**
     * The explosion blocks to KEEP (i.e. that may still break). Inside a region a block breaks only if the region
     * allows it ({@code bossGrief} for a boss blast, {@code explosions} otherwise); outside, only if
     * {@code globalBreak} is on or the blast comes from a boss.
     */
    public static List<BlockPos> filterBlocks(List<BlockPos> blocks, boolean bossSource, boolean globalBreak,
                                              Function<BlockPos, Optional<Flags>> regionAt) {
        List<BlockPos> keep = new ArrayList<>(blocks.size());
        for (BlockPos pos : blocks) {
            Optional<Flags> flags = regionAt.apply(pos);
            boolean breaks = flags.isPresent()
                    ? (bossSource ? flags.get().bossGrief() : flags.get().explosions())
                    : globalBreak || bossSource;
            if (breaks) keep.add(pos);
        }
        return keep;
    }

    /**
     * Whether an explosion's block list is filtered at all. TRIGGER_BLOCK blasts (wind charge, breeze) never break
     * blocks; their list only toggles doors, levers, buttons and bells, which must keep working everywhere.
     */
    /** Min/max corners after {@link #padY}. */
    public record Box(BlockPos min, BlockPos max) {}

    /**
     * Grows the Y range by {@code pad} below min and above max, clamped to {@code worldMin..worldMax} (both
     * inclusive). X/Z are unchanged.
     */
    public static Box padY(BlockPos min, BlockPos max, int pad, int worldMin, int worldMax) {
        int lo = Math.max(worldMin, min.getY() - pad);
        int hi = Math.min(worldMax, max.getY() + pad);
        return new Box(new BlockPos(min.getX(), lo, min.getZ()), new BlockPos(max.getX(), hi, max.getZ()));
    }

    public static boolean filtersBlocks(Explosion.BlockInteraction interaction) {
        return interaction != Explosion.BlockInteraction.TRIGGER_BLOCK;
    }

    /** A fire that appeared in a region without fireSpread is removed unless a player lit it. */
    public static boolean shouldExtinguish(Optional<Flags> region, boolean playerIgnited) {
        return region.isPresent() && !region.get().fireSpread() && !playerIgnited;
    }

    /** A player standing in a region that blocks explosions takes no explosion damage. */
    public static boolean blocksExplosionDamage(Optional<Flags> regionAtVictim) {
        return regionAtVictim.isPresent() && !regionAtVictim.get().explosions();
    }
}
