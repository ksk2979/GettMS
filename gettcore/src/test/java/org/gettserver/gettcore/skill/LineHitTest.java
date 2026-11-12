package org.gettserver.gettcore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class LineHitTest {
    private static final Vec3 O = Vec3.ZERO;
    private static final Vec3 X = new Vec3(1, 0, 0);

    @Test void directionIsUnitLength() {
        Vec3 d = LineHit.direction(new Vec3(1, 2, 3), new Vec3(7, -4, 10), X);
        assertEquals(1.0, d.length(), 1e-9);
    }

    @Test void directionFallsBackWhenTargetOnTop() {
        Vec3 d = LineHit.direction(new Vec3(5, 5, 5), new Vec3(5, 5, 5), new Vec3(0, 0, 1));
        assertFalse(Double.isNaN(d.x) || Double.isNaN(d.y) || Double.isNaN(d.z));
        assertEquals(new Vec3(0, 0, 1), d);
        Vec3 near = LineHit.direction(O, new Vec3(0.005, 0, 0), new Vec3(0, 0, 3));
        assertEquals(new Vec3(0, 0, 1), near);
    }

    @Test void directionAimsUpAndDown() {
        assertTrue(LineHit.direction(O, new Vec3(1, 10, 0), X).y > 0.5);
        assertTrue(LineHit.direction(O, new Vec3(1, -10, 0), X).y < -0.5);
        Vec3 straightUp = LineHit.direction(O, new Vec3(0, 10, 0), X);
        assertEquals(1.0, straightUp.y, 1e-9);
    }

    @Test void alongInsideWidth() {
        assertEquals(10.0, LineHit.along(O, X, 30, 1.5, new Vec3(10, 0, 1.4)), 1e-9);
        assertEquals(-1.0, LineHit.along(O, X, 30, 1.5, new Vec3(10, 0, 1.6)));
    }

    @Test void alongOutsideLength() {
        assertEquals(-1.0, LineHit.along(O, X, 30, 1.5, new Vec3(31, 0, 0)));
        assertEquals(-1.0, LineHit.along(O, X, 30, 1.5, new Vec3(-1, 0, 0)));
    }

    @Test void alongCountsVerticalOffset() {
        assertEquals(-1.0, LineHit.along(O, X, 30, 1.5, new Vec3(10, 2, 0)));
    }

    @Test void segmentsCoverTheLine() {
        List<Vec3> s = LineHit.segments(O, X, 30, 4);
        assertEquals(8, s.size());
        for (int i = 0; i < 7; i++) assertEquals(new Vec3(2 + 4 * i, 0, 0), s.get(i));
        assertEquals(new Vec3(30, 0, 0), s.get(7));
        List<Vec3> clamped = LineHit.segments(O, X, 29, 4);
        assertEquals(8, clamped.size());
        assertEquals(new Vec3(29, 0, 0), clamped.get(7));
    }

    @Test void zeroLengthHasNoSegments() {
        assertTrue(LineHit.segments(O, X, 0, 4).isEmpty());
    }
}
