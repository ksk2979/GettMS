package org.gettserver.gettcore.skill;

import net.minecraft.world.phys.Vec3;

/** Knockback vectors for the bear's shockwave and charge. Pure math. */
public final class ShockPush {
    private ShockPush() {}

    /** Horizontally away from the center (knockback x 0.9), up by knockback x 0.25; on the center, straight up. */
    public static Vec3 outward(Vec3 center, Vec3 victim, double knockback) {
        double dx = victim.x - center.x, dz = victim.z - center.z;
        double len = Math.hypot(dx, dz);
        double up = knockback * 0.25;
        if (len < 1.0E-6) return new Vec3(0, up, 0);
        double h = knockback * 0.9 / len;
        return new Vec3(dx * h, up, dz * h);
    }

    /**
     * Perpendicular to the charge line {@code dir} (horizontal unit vector) toward the side {@code victim} is on
     * (knockback x 0.7), up by knockback x 0.3. Exactly on the line goes to the right of {@code dir}.
     */
    public static Vec3 sideways(Vec3 origin, Vec3 dir, Vec3 victim, double knockback) {
        double rx = victim.x - origin.x, rz = victim.z - origin.z;
        double side = dir.x * rz - dir.z * rx; // >= 0: right of the heading (east heading -> south is right)
        double s = side < 0 ? -1 : 1;
        double h = knockback * 0.7 * s;
        return new Vec3(-dir.z * h, knockback * 0.3, dir.x * h);
    }
}
