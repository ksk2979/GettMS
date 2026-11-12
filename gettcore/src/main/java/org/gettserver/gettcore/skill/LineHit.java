package org.gettserver.gettcore.skill;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Geometry of a straight, wide line (the wind line): direction, who is on it, points along it. */
public final class LineHit {
    private static final double MIN_DISTANCE = 0.01;
    private static final Vec3 FORWARD = new Vec3(0, 0, 1);

    private LineHit() {}

    /** Unit vector origin->target; when they coincide (or are within 0.01), fallback (normalized); never NaN. */
    public static Vec3 direction(Vec3 origin, Vec3 target, Vec3 fallback) {
        Vec3 d = target.subtract(origin);
        if (d.length() > MIN_DISTANCE) return d.normalize();
        Vec3 f = fallback.normalize(); // Vec3.normalize returns ZERO (not NaN) for a near-zero vector
        return f.equals(Vec3.ZERO) ? FORWARD : f;
    }

    /** Distance along the line of the point's projection, or -1 when it is farther than halfWidth from the line or outside 0..length. */
    public static double along(Vec3 origin, Vec3 dir, double length, double halfWidth, Vec3 point) {
        Vec3 v = point.subtract(origin);
        double t = v.dot(dir);
        if (t < 0 || t > length) return -1;
        if (v.subtract(dir.scale(t)).lengthSqr() > halfWidth * halfWidth) return -1;
        return t;
    }

    /** Points every `step` along the line, from step/2 to length. */
    public static List<Vec3> segments(Vec3 origin, Vec3 dir, double length, double step) {
        List<Vec3> out = new ArrayList<>();
        if (length <= 0 || step <= 0) return out;
        int count = (int) Math.ceil(length / step);
        for (int i = 0; i < count; i++) {
            out.add(origin.add(dir.scale(Math.min(step / 2 + i * step, length))));
        }
        return out;
    }
}
