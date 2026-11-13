package org.gettserver.gettcore.skill;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ChargeSkillTest {
    @Test void dashTargetIsHorizontalAndCapped() {
        assertEquals(new Vec3(10, 64, 0), ChargeSkill.dashTarget(new Vec3(0, 64, 0), new Vec3(10, 70, 0), 24));
        assertEquals(new Vec3(24, 64, 0), ChargeSkill.dashTarget(new Vec3(0, 64, 0), new Vec3(40, 64, 0), 24));
        assertEquals(new Vec3(0, 64, 0), ChargeSkill.dashTarget(new Vec3(0, 64, 0), new Vec3(0, 80, 0), 24));
    }

    private static int[] stepsOver(double speed, int ticks) {
        double acc = ChargeSkill.initialStepBudget(speed);
        int[] seen = new int[ticks];
        for (int i = 0; i < ticks; i++) {
            ChargeSkill.Steps s = ChargeSkill.stepsThisTick(acc, speed);
            seen[i] = s.steps();
            acc = s.rest();
        }
        return seen;
    }

    @Test void stepsFollowTheSpeedOnAverage() {
        assertArrayEquals(new int[] {1, 2, 1, 2, 1, 2}, stepsOver(30, 6));
    }

    @Test void slowSpeedStepsEveryFewTicksAndStartsAtOnce() {
        assertArrayEquals(new int[] {1, 0, 0, 0, 1, 0, 0, 0}, stepsOver(5, 8));
    }

    @Test void speedsBetween30And49Differ() {
        assertEquals(30, java.util.Arrays.stream(stepsOver(30, 20)).sum());
        assertEquals(45, java.util.Arrays.stream(stepsOver(45, 20)).sum());
    }

    /** A dash over a fake world: the bear body is 3x3 cells wide and 3 high, centred on column (nx, 0). */
    private static final class Dash {
        final WalkCarveTest.World w;
        final Set<String> unbreakable = new HashSet<>();
        final List<int[]> carved = new ArrayList<>();
        int feet = 64;

        Dash(WalkCarveTest.World w) {
            this.w = w;
        }

        /** One advance to column nx; false when the dash ends there. */
        boolean advance(int nx, boolean terrain) {
            WalkCarve.Step step = WalkCarve.step(nx, feet, 0, 3, w);
            if (!step.walkable()) return false;
            OptionalInt h = ChargeSkill.fit(step.feetY(), feet, terrain, y -> free(nx, y), minY -> carveFrom(nx, minY));
            if (h.isEmpty()) return false;
            feet = h.getAsInt();
            return true;
        }

        boolean free(int nx, int y) {
            for (int x = nx - 1; x <= nx + 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    for (int yy = y; yy < y + 3; yy++) if (w.solid(x, yy, z)) return false;
                }
            }
            return true;
        }

        void carveFrom(int nx, int minY) {
            for (int x = nx - 1; x <= nx + 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    for (int yy = minY; yy < minY + 3; yy++) {
                        String k = WalkCarveTest.World.k(x, yy, z);
                        if (w.solid.contains(k) && !unbreakable.contains(k)) {
                            w.solid.remove(k);
                            carved.add(new int[] {x, yy, z});
                        }
                    }
                }
            }
        }

        /** The last column reached. */
        int run(int from, int to, boolean terrain) {
            for (int x = from; x <= to; x++) if (!advance(x, terrain)) return x - 1;
            return to;
        }
    }

    @Test void onePlateauIsRiddenUpWithoutCarving() {
        Dash d = new Dash(new WalkCarveTest.World().floor(63, -12, 12).floor(64, 3, 12));
        assertEquals(8, d.run(1, 8, true));
        assertEquals(65, d.feet);
        assertTrue(d.carved.isEmpty());
    }

    @Test void plateauWithTerrainOffIsAlsoRiddenUp() {
        Dash d = new Dash(new WalkCarveTest.World().floor(63, -12, 12).floor(64, 3, 12));
        assertEquals(8, d.run(1, 8, false));
        assertEquals(65, d.feet);
    }

    @Test void staircaseHillUpAndDownCarvesNothing() {
        Dash d = new Dash(new WalkCarveTest.World().floor(63, -12, 12).floor(64, 2, 7).floor(65, 4, 5));
        assertEquals(12, d.run(1, 12, true));
        assertTrue(d.carved.isEmpty());
        assertEquals(64, d.feet, "back at ground level");
    }

    @Test void threeHighWallIsCarvedAboveTheFeetLayerOnly() {
        WalkCarveTest.World w = new WalkCarveTest.World().floor(63, -12, 12);
        for (int z = -12; z <= 12; z++) w.column(3, z, 64, 66);
        Dash d = new Dash(w);
        assertEquals(8, d.run(1, 8, true));
        assertFalse(d.carved.isEmpty());
        for (int[] c : d.carved) assertTrue(c[1] == 65 || c[1] == 66, "carved y=" + c[1]);
        for (int x = -12; x <= 12; x++) assertTrue(w.solid(x, 63, 0), "floor intact");
    }

    @Test void unbreakableWallEndsTheDashAtThePreviousColumn() {
        WalkCarveTest.World w = new WalkCarveTest.World().floor(63, -12, 12);
        Dash d = new Dash(w);
        for (int z = -12; z <= 12; z++) {
            for (int y = 64; y <= 66; y++) {
                w.solid.add(WalkCarveTest.World.k(5, y, z));
                d.unbreakable.add(WalkCarveTest.World.k(5, y, z));
            }
        }
        assertEquals(3, d.run(1, 8, true));
        assertTrue(d.carved.isEmpty());
    }

    @Test void twoBlockDropIsWalkedDownWithoutCarving() {
        Dash d = new Dash(new WalkCarveTest.World().floor(63, -12, 2).floor(61, 3, 12));
        assertEquals(10, d.run(1, 10, true));
        assertEquals(62, d.feet);
        assertTrue(d.carved.isEmpty());
    }

    @Test void twoBlockDropWithTerrainOffContinues() {
        Dash d = new Dash(new WalkCarveTest.World().floor(63, -12, 2).floor(61, 3, 12));
        assertEquals(10, d.run(1, 10, false));
        assertEquals(62, d.feet);
        assertTrue(d.carved.isEmpty());
    }

    @Test void downhillStaircaseThenUphillCarvesNothing() {
        Dash d = new Dash(new WalkCarveTest.World().floor(63, -12, 1).floor(62, 2, 3).floor(61, 4, 5)
                .floor(60, 6, 7).floor(61, 8, 9).floor(62, 10, 12));
        assertEquals(12, d.run(1, 12, true));
        assertEquals(63, d.feet);
        assertTrue(d.carved.isEmpty());
    }

    @Test void threeBlockDropWithTerrainOffContinues() {
        Dash d = new Dash(new WalkCarveTest.World().floor(63, -12, 2).floor(60, 3, 12));
        assertEquals(10, d.run(1, 10, false));
        assertEquals(61, d.feet);
    }
}
