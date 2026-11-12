package org.gettserver.gettcore.skill;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/** Particles and sounds shared by the skill types (filled in by the concrete skills). */
public final class SkillFx {
    private static final double GATHER_SPEED = 0.08;

    private SkillFx() {}

    /** {@code count} particles spread up to {@code spread} blocks horizontally (a quarter of that vertically). */
    public static void particles(ServerLevel level, ParticleOptions particle, Vec3 at, int count, double spread,
                                 double speed) {
        level.sendParticles(particle, at.x, at.y, at.z, count, spread, spread * 0.25, spread, speed);
    }

    /** {@code count} particles on a sphere of {@code radius} around {@code center}, each drifting in towards it. */
    public static void gather(ServerLevel level, ParticleOptions particle, Vec3 center, double radius, int count) {
        RandomSource random = level.getRandom();
        for (int i = 0; i < count; i++) {
            Vec3 off = new Vec3(random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1)
                    .normalize().scale(radius);
            // count 0: the offset is the particle's velocity (times speed) instead of a spread
            level.sendParticles(particle, center.x + off.x, center.y + off.y, center.z + off.z, 0,
                    -off.x, -off.y, -off.z, GATHER_SPEED);
        }
    }

    /** A hostile-category sound at {@code at}, normal pitch. */
    public static void sound(ServerLevel level, Vec3 at, Holder<SoundEvent> sound, float volume) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.HOSTILE, volume, 1.0F);
    }
}
