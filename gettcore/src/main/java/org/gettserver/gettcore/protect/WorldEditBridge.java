package org.gettserver.gettcore.protect;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.neoforge.NeoForgeAdapter;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * The only class that references WorldEdit. Callers must check {@code ModList.get().isLoaded("worldedit")} before
 * touching it, so a server without WorldEdit never loads this class.
 */
public final class WorldEditBridge {
    /** A complete WorldEdit selection (min/max inclusive). */
    public record Selection(ResourceKey<Level> dimension, BlockPos min, BlockPos max) {}

    private WorldEditBridge() {}

    /** The player's current WorldEdit selection, or empty if nothing (or only one corner) is selected. */
    public static Optional<Selection> selection(ServerPlayer player) {
        LocalSession session = WorldEdit.getInstance().getSessionManager().get(NeoForgeAdapter.adaptPlayer(player));
        World world = session.getSelectionWorld();
        if (world == null) return Optional.empty();
        try {
            Region region = session.getSelection(world);
            return Optional.of(new Selection(NeoForgeAdapter.adapt(world).dimension(), pos(region.getMinimumPoint()),
                    pos(region.getMaximumPoint())));
        } catch (IncompleteRegionException e) {
            return Optional.empty();
        }
    }

    private static BlockPos pos(BlockVector3 v) {
        return new BlockPos(v.x(), v.y(), v.z());
    }
}
