package org.gettserver.gettcore.detector;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Finds blocks around a point. Looks only at chunks that are already loaded. */
public final class DetectorScan {
    private DetectorScan() {}

    /**
     * Positions ({@link BlockPos#asLong}) of {@code blocks} inside the cube of +-{@code range} around {@code center}
     * (clipped to the world height). Sections that cannot contain them are skipped. Over {@code max} results, the
     * {@code max} nearest to {@code center} are kept. {@code exclude} (may be null) is left out.
     */
    public static long[] find(ServerLevel level, BlockPos center, int range, Set<Block> blocks, int max,
                              @Nullable BlockPos exclude) {
        if (blocks.isEmpty()) return new long[0];
        int minX = center.getX() - range;
        int maxX = center.getX() + range;
        int minZ = center.getZ() - range;
        int maxZ = center.getZ() + range;
        int minY = Math.max(center.getY() - range, level.getMinBuildHeight());
        int maxY = Math.min(center.getY() + range, level.getMaxBuildHeight() - 1);
        long excluded = exclude == null ? Long.MIN_VALUE : exclude.asLong();
        LongList found = new LongArrayList();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int si = 0; si < sections.length; si++) {
                    LevelChunkSection section = sections[si];
                    int baseY = level.getSectionYFromSectionIndex(si) << 4;
                    if (baseY + 15 < minY || baseY > maxY) continue;
                    if (!section.maybeHas(s -> blocks.contains(s.getBlock()))) continue;
                    int y0 = Math.max(baseY, minY);
                    int y1 = Math.min(baseY + 15, maxY);
                    int x0 = Math.max(cx << 4, minX);
                    int x1 = Math.min((cx << 4) + 15, maxX);
                    int z0 = Math.max(cz << 4, minZ);
                    int z1 = Math.min((cz << 4) + 15, maxZ);
                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int x = x0; x <= x1; x++) {
                                if (!blocks.contains(section.getBlockState(x & 15, y & 15, z & 15).getBlock())) {
                                    continue;
                                }
                                long packed = pos.set(x, y, z).asLong();
                                if (packed != excluded) found.add(packed);
                            }
                        }
                    }
                }
            }
        }
        return nearest(found, center, max);
    }

    /** The {@code max} entries of {@code found} nearest to {@code center} (squared distance, ascending). */
    static long[] nearest(LongList found, BlockPos center, int max) {
        long[] all = found.toLongArray();
        if (all.length <= max) return all;
        Long[] boxed = new Long[all.length];
        for (int i = 0; i < all.length; i++) boxed[i] = all[i];
        Arrays.sort(boxed, Comparator.comparingLong(p -> distSq(p, center)));
        long[] out = new long[max];
        for (int i = 0; i < max; i++) out[i] = boxed[i];
        return out;
    }

    private static long distSq(long p, BlockPos c) {
        long dx = BlockPos.getX(p) - c.getX();
        long dy = BlockPos.getY(p) - c.getY();
        long dz = BlockPos.getZ(p) - c.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
