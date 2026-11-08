package org.gettserver.gettcore.protect;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
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

    /** A fire that appeared in a region without fireSpread is removed unless a player lit it. */
    public static boolean shouldExtinguish(Optional<Flags> region, boolean playerIgnited) {
        return region.isPresent() && !region.get().fireSpread() && !playerIgnited;
    }
}
