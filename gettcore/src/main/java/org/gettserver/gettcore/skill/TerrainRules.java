package org.gettserver.gettcore.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.gettserver.gettcore.protect.ProtectConfig;

/** Which blocks a skill may dig out, and the shapes it digs. Level-free. */
public final class TerrainRules {
    /** Blocks at or above this explosion resistance (obsidian and up) are never broken. */
    public static final float MAX_BLAST_RESISTANCE = 1200F;
    private static final double CRATER_BOTTOM = 0.4; // bottom layer radius, as a fraction of the surface radius

    private TerrainRules() {}

    /**
     * regionFlags: the protect region at the block, if any. {@code neighbourHasBlockEntity}: a block next to a block
     * entity (wall sign, banner, chest, ...) stays, so nothing attached to those is knocked off or destroyed.
     */
    public static boolean breakable(boolean air, float destroySpeed, float explosionResistance, boolean hasBlockEntity,
                                    boolean neighbourHasBlockEntity, Optional<ProtectConfig.Flags> regionFlags) {
        if (air || hasBlockEntity || neighbourHasBlockEntity) return false;
        if (destroySpeed < 0 || explosionResistance >= MAX_BLAST_RESISTANCE) return false;
        return regionFlags.map(ProtectConfig.Flags::bossGrief).orElse(true);
    }

    /** Bowl: radius at the surface, narrowing to ~40% at `depth`; top layer is `ground.y`. Bounded to minY..maxY. */
    public static List<BlockPos> crater(BlockPos ground, double radius, int depth, int minY, int maxY) {
        List<BlockPos> out = new ArrayList<>();
        if (radius <= 0) return out;
        int reach = Mth.ceil(radius);
        for (int dy = 0; dy < depth; dy++) {
            int y = ground.getY() - dy;
            if (y < minY || y > maxY) continue;
            double r = depth == 1 ? radius : radius * (1 - (1 - CRATER_BOTTOM) * dy / (depth - 1));
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    if (dx * dx + dz * dz <= r * r) out.add(new BlockPos(ground.getX() + dx, y, ground.getZ() + dz));
                }
            }
        }
        return out;
    }

    /** All block positions within `radius` of the segment a->b (a block counts as its integer corner point). */
    public static List<BlockPos> tube(Vec3 a, Vec3 b, double radius, int minY, int maxY) {
        List<BlockPos> out = new ArrayList<>();
        if (radius <= 0) return out;
        int x0 = Mth.floor(Math.min(a.x, b.x) - radius), x1 = Mth.ceil(Math.max(a.x, b.x) + radius);
        int y0 = Math.max(minY, Mth.floor(Math.min(a.y, b.y) - radius));
        int y1 = Math.min(maxY, Mth.ceil(Math.max(a.y, b.y) + radius));
        int z0 = Mth.floor(Math.min(a.z, b.z) - radius), z1 = Mth.ceil(Math.max(a.z, b.z) + radius);
        Vec3 ab = b.subtract(a);
        double lenSqr = ab.lengthSqr();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    Vec3 p = new Vec3(x, y, z);
                    double t = lenSqr < 1.0E-12 ? 0 : Mth.clamp(p.subtract(a).dot(ab) / lenSqr, 0, 1);
                    if (p.distanceToSqr(a.add(ab.scale(t))) <= radius * radius) out.add(new BlockPos(x, y, z));
                }
            }
        }
        return out;
    }
}
