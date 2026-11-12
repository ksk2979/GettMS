package org.gettserver.gettcore.skill;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** Particles and sounds shared by the skill types (filled in by the concrete skills). */
public final class SkillFx {
    private SkillFx() {}

    /** {@code count} particles spread up to {@code spread} blocks horizontally (a quarter of that vertically). */
    public static void particles(ServerLevel level, ParticleOptions particle, Vec3 at, int count, double spread,
                                 double speed) {
        level.sendParticles(particle, at.x, at.y, at.z, count, spread, spread * 0.25, spread, speed);
    }

    /** A hostile-category sound at {@code at}, normal pitch. */
    public static void sound(ServerLevel level, Vec3 at, Holder<SoundEvent> sound, float volume) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.HOSTILE, volume, 1.0F);
    }
}
