package org.gettserver.gettcore.protect;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * protect.json: global explosion/enderman rules plus protected regions (보호구역). Missing fields fall back to
 * defaults (everything blocked); {@link #sanitized(List)} orders min/max and drops duplicate region ids.
 */
public record ProtectConfig(boolean explosionsBreakBlocks, boolean endermanPickup, List<Region> regions) {
    public static final String FILE = "protect.json";
    public static final ProtectConfig DEFAULT = new ProtectConfig(false, false, List.of());

    /** What a region allows; false = blocked (the default for all three). */
    public record Flags(boolean fireSpread, boolean bossGrief, boolean explosions) {
        public static final Flags DEFAULT = new Flags(false, false, false);
        public static final Codec<Flags> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.BOOL.optionalFieldOf("fireSpread", false).forGetter(Flags::fireSpread),
                Codec.BOOL.optionalFieldOf("bossGrief", false).forGetter(Flags::bossGrief),
                Codec.BOOL.optionalFieldOf("explosions", false).forGetter(Flags::explosions)
        ).apply(i, Flags::new));
    }

    /** A box from {@code min} to {@code max} (both inclusive) in {@code dimension}. */
    public record Region(String id, ResourceKey<Level> dimension, BlockPos min, BlockPos max, Flags flags) {
        public static final Codec<Region> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Region::id),
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Region::dimension),
                BlockPos.CODEC.fieldOf("min").forGetter(Region::min),
                BlockPos.CODEC.fieldOf("max").forGetter(Region::max),
                Flags.CODEC.optionalFieldOf("flags", Flags.DEFAULT).forGetter(Region::flags)
        ).apply(i, Region::new));

        public boolean contains(ResourceKey<Level> dim, BlockPos pos) {
            return dimension.equals(dim)
                    && pos.getX() >= min.getX() && pos.getX() <= max.getX()
                    && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                    && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
        }

        /** The block at the middle of the box (rounded down). */
        public BlockPos center() {
            return new BlockPos(Math.floorDiv(min.getX() + max.getX(), 2), Math.floorDiv(min.getY() + max.getY(), 2),
                    Math.floorDiv(min.getZ() + max.getZ(), 2));
        }

        /** This region with min holding the smaller and max the larger coordinate on every axis. */
        public Region normalized() {
            BlockPos lo = new BlockPos(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()),
                    Math.min(min.getZ(), max.getZ()));
            BlockPos hi = new BlockPos(Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()),
                    Math.max(min.getZ(), max.getZ()));
            return lo.equals(min) && hi.equals(max) ? this : new Region(id, dimension, lo, hi, flags);
        }
    }

    public static final Codec<ProtectConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("explosionsBreakBlocks", false).forGetter(ProtectConfig::explosionsBreakBlocks),
            Codec.BOOL.optionalFieldOf("endermanPickup", false).forGetter(ProtectConfig::endermanPickup),
            Region.CODEC.listOf().optionalFieldOf("regions", List.of()).forGetter(ProtectConfig::regions)
    ).apply(i, ProtectConfig::new));

    public ProtectConfig {
        regions = List.copyOf(regions);
    }

    /** Orders min/max per axis and drops regions whose id was already used, one warning each. */
    public ProtectConfig sanitized(List<String> warnings) {
        List<Region> out = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        boolean changed = false;
        for (Region r : regions) {
            if (!ids.add(r.id())) {
                warnings.add(FILE + ": duplicate region id " + r.id() + " (skipped)");
                changed = true;
                continue;
            }
            Region n = r.normalized();
            if (n != r) {
                warnings.add(FILE + ": region " + r.id() + " min/max swapped to " + n.min().toShortString()
                        + " ~ " + n.max().toShortString());
                changed = true;
            }
            out.add(n);
        }
        return changed ? new ProtectConfig(explosionsBreakBlocks, endermanPickup, out) : this;
    }

    /** The first region containing {@code pos} in {@code dim}. */
    public Optional<Region> regionAt(ResourceKey<Level> dim, BlockPos pos) {
        for (Region r : regions) {
            if (r.contains(dim, pos)) return Optional.of(r);
        }
        return Optional.empty();
    }

    public Optional<Region> region(String id) {
        return regions.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    public ProtectConfig withRegions(List<Region> newRegions) {
        return new ProtectConfig(explosionsBreakBlocks, endermanPickup, newRegions);
    }
}
