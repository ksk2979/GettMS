package org.gettserver.gettcore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WalkCarveTest {
    /** A tiny fake world: solid and light blocks in sets, everything else is air. */
    static final class World implements WalkCarve.Blocks {
        final Set<String> solid = new HashSet<>();
        final Set<String> light = new HashSet<>();

        static String k(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        /** Solid layer at {@code y} for x in from..to, z in -12..12. */
        World floor(int y, int from, int to) {
            for (int x = from; x <= to; x++) for (int z = -12; z <= 12; z++) solid.add(k(x, y, z));
            return this;
        }

        World column(int x, int z, int y0, int y1) {
            for (int y = y0; y <= y1; y++) solid.add(k(x, y, z));
            return this;
        }

        @Override public boolean solid(int x, int y, int z) { return solid.contains(k(x, y, z)); }
        @Override public boolean light(int x, int y, int z) { return light.contains(k(x, y, z)); }
    }

    static List<WalkCarve.Carve> solids(List<WalkCarve.Carve> all) {
        return all.stream().filter(c -> !c.light()).toList();
    }

    @Test
    void flatGroundCarvesNothingSolid() {
        World w = new World().floor(63, -12, 12);
        assertTrue(solids(WalkCarve.disc(0, 64, 0, 6, 3, w)).isEmpty());
    }

    @Test
    void oneBlockStepsAreWalked() {
        World w = new World().floor(63, -12, 12).floor(64, 3, 12);
        w.light.add(World.k(5, 65, 0)); // grass on top of the step
        List<WalkCarve.Carve> out = WalkCarve.disc(0, 64, 0, 6, 3, w);
        assertTrue(solids(out).isEmpty(), out.toString());
        assertTrue(out.stream().anyMatch(c -> c.x() == 5 && c.y() == 65 && c.z() == 0 && c.light()));
        assertEquals(65, WalkCarve.step(3, 64, 0, 3, w).feetY());
    }

    @Test
    void wallIsCarvedAtBodyHeightOnly() {
        World w = new World().floor(63, -12, 12);
        for (int z = -12; z <= 12; z++) w.column(3, z, 64, 68);
        List<WalkCarve.Carve> out = WalkCarve.disc(0, 64, 0, 6, 3, w);
        List<WalkCarve.Carve> wall = out.stream().filter(c -> c.x() == 3).toList();
        assertFalse(wall.isEmpty());
        for (WalkCarve.Carve c : wall) assertTrue(c.y() >= 64 && c.y() <= 66, c.toString());
        assertTrue(wall.stream().anyMatch(c -> c.z() == 0 && c.y() == 64));
        assertTrue(wall.stream().anyMatch(c -> c.z() == 0 && c.y() == 66));
        assertTrue(out.stream().noneMatch(c -> c.y() == 63 || c.y() == 67 || c.y() == 68));
        // the plan continues through the wall: x=4 is open ground again
        assertTrue(WalkCarve.step(4, 64, 0, 3, w).walkable());
    }

    @Test
    void wallBehindIsReachedThroughTheFirst() {
        World w = new World().floor(63, -12, 12);
        for (int z = -12; z <= 12; z++) {
            w.column(3, z, 64, 68);
            w.column(5, z, 64, 68);
        }
        List<WalkCarve.Carve> out = WalkCarve.disc(0, 64, 0, 8, 3, w);
        assertTrue(out.stream().anyMatch(c -> c.x() == 5 && c.z() == 0), "second wall must be planned too");
    }

    @Test
    void lowCeilingIsCarved() {
        World w = new World().floor(63, -12, 12).floor(66, -12, 12);
        List<WalkCarve.Carve> out = WalkCarve.disc(0, 64, 0, 3, 3, w);
        assertFalse(out.isEmpty());
        for (WalkCarve.Carve c : out) assertEquals(66, c.y());
    }

    @Test
    void treeTrunkCarved() {
        World w = new World().floor(63, -12, 12).column(2, 0, 64, 69);
        List<WalkCarve.Carve> out = WalkCarve.disc(0, 64, 0, 4, 3, w);
        assertEquals(3, out.size(), out.toString());
        assertTrue(out.stream().allMatch(c -> c.x() == 2 && c.z() == 0 && c.y() >= 64 && c.y() <= 66));
    }

    @Test
    void lightThingsSwept() {
        World w = new World().floor(63, -12, 12);
        w.light.add(World.k(2, 64, 1));
        List<WalkCarve.Carve> out = WalkCarve.disc(0, 64, 0, 5, 3, w);
        assertEquals(1, out.size());
        assertTrue(out.get(0).light());
    }

    @Test
    void startCellLightIncluded() {
        World w = new World().floor(63, -12, 12);
        w.light.add(World.k(0, 65, 0));
        List<WalkCarve.Carve> out = WalkCarve.disc(0, 64, 0, 2, 3, w);
        assertEquals(1, out.size());
        assertEquals(0.0, out.get(0).distance());
    }

    @Test
    void deepDropBlocks() {
        // x>=3 is five blocks lower (ground at y=58), nothing solid within 3 below the feet
        World w = new World().floor(63, -12, 2).floor(58, 3, 12);
        assertFalse(WalkCarve.step(3, 64, 0, 3, w).walkable());
        List<WalkCarve.Carve> out = WalkCarve.disc(0, 64, 0, 8, 3, w);
        assertTrue(out.stream().noneMatch(c -> c.x() >= 4));
    }

    @Test
    void threeBlockDropIsWalked() {
        World w = new World().floor(63, -12, 2).floor(60, 3, 12);
        WalkCarve.Step down = WalkCarve.step(3, 64, 0, 3, w);
        assertTrue(down.walkable());
        assertEquals(61, down.feetY());
        assertTrue(down.carve().isEmpty());
    }

    @Test
    void deepDropStillSweepsLight() {
        World w = new World().floor(63, -12, 2);
        w.light.add(World.k(3, 65, 0));
        WalkCarve.Step s = WalkCarve.step(3, 64, 0, 3, w);
        assertFalse(s.walkable());
        assertEquals(1, s.carve().size());
    }

    @Test
    void nothingOutsideRadius() {
        World w = new World().floor(63, -12, 12);
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) w.column(x, z, 64, 66);
        List<WalkCarve.Carve> out = WalkCarve.disc(0, 64, 0, 5, 3, w);
        assertFalse(out.isEmpty());
        for (WalkCarve.Carve c : out) assertTrue(Math.hypot(c.x(), c.z()) <= 5.0 + 1e-9, c.toString());
    }

    @Test
    void sortedByDistance() {
        World w = new World().floor(63, -12, 12);
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) w.column(x, z, 64, 65);
        List<WalkCarve.Carve> out = WalkCarve.disc(0, 64, 0, 6, 3, w);
        assertFalse(out.isEmpty());
        for (int i = 1; i < out.size(); i++) assertTrue(out.get(i - 1).distance() <= out.get(i).distance());
    }

    @Test
    void floorNeverCarved() {
        // a solid 4-high slab on the far side: every cell's floor (y-1 of its first body cell) stays
        World w = new World().floor(63, -12, 12);
        for (int x = 4; x <= 12; x++) for (int z = -12; z <= 12; z++) w.column(x, z, 64, 67);
        List<WalkCarve.Carve> out = WalkCarve.disc(0, 64, 0, 8, 3, w);
        assertFalse(out.isEmpty());
        for (WalkCarve.Carve c : out) assertTrue(c.y() >= 64 && c.y() <= 66, c.toString());
    }
}
