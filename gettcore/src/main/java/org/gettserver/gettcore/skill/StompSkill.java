package org.gettserver.gettcore.skill;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.gettserver.gettcore.pet.boss.BossGiraffe;

/** {@code stomp}: rears up during the charge, then slams down, hitting everyone within {@code shape.radius}. */
public final class StompSkill implements SkillType {
    @Override
    public void charge(Cast cast, int age) {
        Mob caster = cast.caster();
        if (age == 0 && caster instanceof BossGiraffe) cast.level().broadcastEntityEvent(caster, BossGiraffe.EVENT_STOMP);
        SkillFx.particles(cast.level(), ParticleTypes.CLOUD, caster.position(), 2, caster.getBbWidth() * 0.5, 0.01);
    }

    @Override
    public void release(Cast cast) {
        ServerLevel level = cast.level();
        Mob caster = cast.caster();
        SkillSpec spec = cast.spec();
        Vec3 center = caster.position();
        double radius = spec.shape().radius();
        for (LivingEntity victim : SkillTargets.in(level, new AABB(center, center).inflate(radius), caster)) {
            if (!inRadius(center, victim.position(), radius)) continue;
            SkillTargets.hit(caster, victim, spec.damage(), push(center, victim.position(), spec.knockback()));
        }
        SkillFx.particles(level, ParticleTypes.EXPLOSION, center, 1, 0, 0);
        SkillFx.particles(level, ParticleTypes.POOF, center, 30, radius * 0.5, 0.1);
        SkillFx.sound(level, center, SoundEvents.GENERIC_EXPLODE, 1.5F);
    }

    @Override
    public boolean active(Cast cast, int age) {
        return false;
    }

    /** Horizontally away from {@code center} (knockback x0.4) and up (knockback x0.5); straight up at the center. */
    static Vec3 push(Vec3 center, Vec3 victim, double knockback) {
        double dx = victim.x - center.x;
        double dz = victim.z - center.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        double up = knockback * 0.5;
        if (len < 1.0E-6) return new Vec3(0, up, 0);
        double side = knockback * 0.4 / len;
        return new Vec3(dx * side, up, dz * side);
    }

    /** Within {@code radius} of {@code center}, edge included. */
    static boolean inRadius(Vec3 center, Vec3 victim, double radius) {
        return center.distanceToSqr(victim) <= radius * radius;
    }
}
