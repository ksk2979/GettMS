package org.gettserver.gettcore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.gettserver.gettcore.protect.ProtectConfig.Flags;
import org.junit.jupiter.api.Test;

class TerrainRulesTest {
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 319;

    private static boolean stone(Optional<Flags> region) {
        return TerrainRules.breakable(false, 1.5F, 6F, false, region);
    }

    @Test void airIsNotBroken() {
        assertFalse(TerrainRules.breakable(true, 0F, 0F, false, Optional.empty()));
    }

    @Test void bedrockAndObsidianStay() {
        assertFalse(TerrainRules.breakable(false, -1F, 3_600_000F, false, Optional.empty()), "bedrock");
        assertFalse(TerrainRules.breakable(false, 50F, 1200F, false, Optional.empty()), "obsidian");
        assertTrue(stone(Optional.empty()), "stone");
    }

    @Test void blockEntitiesStay() {
        assertFalse(TerrainRules.breakable(false, 2.5F, 2.5F, true, Optional.empty()), "chest");
    }

    @Test void protectedRegionStays() {
        assertFalse(stone(Optional.of(Flags.DEFAULT)), "bossGrief off");
        assertFalse(stone(Optional.of(new Flags(true, false, true))), "bossGrief off, other flags on");
        assertTrue(stone(Optional.of(new Flags(false, true, false))), "bossGrief on");
        assertTrue(stone(Optional.empty()), "no region");
    }

    @Test void craterIsWiderAtTheTop() {
        BlockPos ground = new BlockPos(0, 64, 0);
        List<BlockPos> crater = TerrainRules.crater(ground, 5, 5, MIN_Y, MAX_Y);
        long top = crater.stream().filter(p -> p.getY() == 64).count();
        long bottom = crater.stream().filter(p -> p.getY() == 60).count();
        assertTrue(bottom > 0, "bottom layer present");
        assertTrue(top > bottom, top + " at the top vs " + bottom + " at the bottom");
        assertTrue(crater.stream().allMatch(p -> p.getY() >= 60 && p.getY() <= 64));
        assertTrue(crater.contains(ground));
        assertEquals(crater.size(), new HashSet<>(crater).size(), "no duplicates");
    }

    @Test void craterClampedToWorldBottom() {
        List<BlockPos> crater = TerrainRules.crater(new BlockPos(0, MIN_Y + 1, 0), 5, 5, MIN_Y, MAX_Y);
        assertFalse(crater.isEmpty());
        assertTrue(crater.stream().allMatch(p -> p.getY() >= MIN_Y));
    }

    @Test void craterDepthZeroIsEmpty() {
        assertTrue(TerrainRules.crater(new BlockPos(0, 64, 0), 5, 0, MIN_Y, MAX_Y).isEmpty());
    }

    @Test void tubeWithFloorMinYNeverDigsBelowTheFeetLayer() {
        int feet = WindLineSkill.lowestCarvableY(65.0);
        assertEquals(65, feet);
        assertEquals(65, WindLineSkill.lowestCarvableY(65.7), "feet partway up a slab: still that layer");
        List<BlockPos> tube = TerrainRules.tube(new Vec3(0, 66, 0), new Vec3(10, 66, 0), 1.0, feet, MAX_Y);
        assertTrue(tube.stream().noneMatch(p -> p.getY() < 65), "ground layer (64) and below stay");
        assertTrue(tube.contains(new BlockPos(5, 65, 0)), "wall at feet level is pierced");
        assertTrue(tube.contains(new BlockPos(5, 66, 0)));
        assertTrue(tube.contains(new BlockPos(5, 67, 0)), "and above");
        assertFalse(tube.contains(new BlockPos(5, 64, 0)));
    }

    @Test void tubeContainsBothEnds() {
        List<BlockPos> tube = TerrainRules.tube(new Vec3(0, 64, 0), new Vec3(10, 64, 0), 1.5, MIN_Y, MAX_Y);
        assertTrue(tube.contains(new BlockPos(0, 64, 0)));
        assertTrue(tube.contains(new BlockPos(10, 64, 0)));
        assertTrue(tube.contains(new BlockPos(5, 65, 0)));
        assertFalse(tube.contains(new BlockPos(5, 67, 0)));
    }

    @Test void tubeOfZeroLengthIsASphere() {
        Vec3 c = new Vec3(0, 64, 0);
        Set<BlockPos> tube = new HashSet<>(TerrainRules.tube(c, c, 2, MIN_Y, MAX_Y));
        Set<BlockPos> sphere = new HashSet<>();
        for (BlockPos p : BlockPos.betweenClosed(-2, 62, -2, 2, 66, 2)) {
            if (p.distSqr(new BlockPos(0, 64, 0)) <= 4) sphere.add(p.immutable());
        }
        assertEquals(sphere, tube);
    }
}
