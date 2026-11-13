package org.gettserver.gettcore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ShockPushTest {
    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 1e-9, "x");
        assertEquals(expected.y, actual.y, 1e-9, "y");
        assertEquals(expected.z, actual.z, 1e-9, "z");
    }

    @Test
    void outwardPushesAwayFromCenter() {
        assertVec(new Vec3(1.8, 0.5, 0), ShockPush.outward(Vec3.ZERO, new Vec3(5, 0, 0), 2));
    }

    @Test
    void outwardIgnoresVerticalOffsetAndDistance() {
        Vec3 near = ShockPush.outward(Vec3.ZERO, new Vec3(1, 7, 0), 2);
        Vec3 far = ShockPush.outward(Vec3.ZERO, new Vec3(30, -2, 0), 2);
        assertVec(near, far);
        assertVec(new Vec3(1.8, 0.5, 0), far);
    }

    @Test
    void outwardOnTheCenterGoesStraightUp() {
        assertVec(new Vec3(0, 0.5, 0), ShockPush.outward(new Vec3(3, 64, 3), new Vec3(3, 64, 3), 2));
    }

    @Test
    void sidewaysNorthOfAnEastwardCharge() {
        // east = +x, north = -z
        assertVec(new Vec3(0, 0.6, -1.4), ShockPush.sideways(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(5, 0, -1), 2));
    }

    @Test
    void sidewaysSouthOfAnEastwardCharge() {
        assertVec(new Vec3(0, 0.6, 1.4), ShockPush.sideways(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(5, 0, 1), 2));
    }

    @Test
    void sidewaysExactlyOnTheLineGoesRight() {
        // the right-hand side of an eastward heading is south (+z)
        assertVec(new Vec3(0, 0.6, 1.4), ShockPush.sideways(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(5, 0, 0), 2));
    }
}
