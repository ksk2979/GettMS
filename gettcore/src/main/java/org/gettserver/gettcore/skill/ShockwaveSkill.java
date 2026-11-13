package org.gettserver.gettcore.skill;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.gettserver.gettcore.pet.boss.BossBear;

/**
 * {@code shockwave} (곰라텐세): the caster gathers (a boss bear raises its head, {@link BossBear#EVENT_ROAR}), then roars
 * and a ring spreads from where it stood at {@code shape.speed} blocks per second up to {@code shape.radius}. Whoever
 * the ring reaches is hit once and thrown outward; with {@code terrain} on, only what the caster could not walk
 * through (and light plants) is broken, in the order the ring reaches it. The ring effect spreads a little past the
 * radius; damage and terrain stop at it.
 */
public final class ShockwaveSkill implements SkillType {
    static final double VERTICAL_REACH = 6.0;     // victims further than this above/below the center are missed
    static final double FX_OVERSHOOT = 1.4;       // the ring effect spreads to radius x this
    private static final int GROWL_INTERVAL = 10; // ticks between charge growls
    private static final int MAX_RING_POINTS = 24;
    private static final int RING_SCAN = 4;       // blocks above/below the center searched for the ground

    /** The wave of each running cast, from release until its ring effect is done; weak so a cancelled cast is dropped. */
    private final Map<Cast, Wave> waves = new WeakHashMap<>();

    @Override
    public void charge(Cast cast, int age) {
        Mob caster = cast.caster();
        ServerLevel level = cast.level();
        if (age == 0 && caster instanceof BossBear) level.broadcastEntityEvent(caster, BossBear.EVENT_ROAR);
        SkillFx.footDust(level, caster.position(), 3, caster.getBbWidth() * 0.5);
        if (age % GROWL_INTERVAL == 0) SkillFx.sound(level, caster.position(), SoundEvents.POLAR_BEAR_WARNING, 2.0F, 0.5F);
    }

    @Override
    public void release(Cast cast) {
        Mob caster = cast.caster();
        ServerLevel level = cast.level();
        SkillSpec spec = cast.spec();
        Vec3 center = caster.position();
        cast.aim()[0] = center;
        SkillFx.sound(level, center, SoundEvents.RAVAGER_ROAR, 4.0F, 0.6F);
        SkillFx.sound(level, center, SoundEvents.POLAR_BEAR_WARNING, 4.0F, 0.5F);
        List<WalkCarve.Carve> plan = List.of();
        if (spec.terrain().enabled()) {
            plan = WalkCarve.disc(Mth.floor(center.x), Mth.floor(center.y + 1.0E-3), Mth.floor(center.z),
                    spec.shape().radius(), Mth.ceil(caster.getBbHeight()), SkillWorld.walkBlocks(level));
        }
        waves.put(cast, new Wave(center, plan));
    }

    @Override
    public boolean active(Cast cast, int age) {
        Wave wave = waves.get(cast);
        if (wave == null) return false;
        ServerLevel level = cast.level();
        SkillSpec.Shape shape = cast.spec().shape();
        double r = radiusAt(age, shape.speed(), shape.radius());
        double prev = age == 0 ? 0 : radiusAt(age - 1, shape.speed(), shape.radius());
        hitReached(cast, wave, prev, r);
        carvePlanned(level, wave, r);
        double fx = (age + 1) * shape.speed() / 20.0;
        double fxMax = shape.radius() * FX_OVERSHOOT;
        ring(level, wave.center, Math.min(fx, fxMax));
        if (fx >= fxMax) {
            waves.remove(cast);
            return false;
        }
        return true;
    }

    /** Pure: the ring radius after tick {@code age} (0 = first active tick), capped at {@code maxRadius}. */
    static double radiusAt(int age, double speedPerSecond, double maxRadius) {
        return Math.min((age + 1) * speedPerSecond / 20.0, maxRadius);
    }

    /**
     * Pure: the ring, growing from {@code prevRadius} to {@code radius} this tick, newly covers a target at
     * {@code horizontalDistance} (prev = 0 on the first tick: everything from the center out counts).
     */
    static boolean reached(double prevRadius, double radius, double horizontalDistance) {
        return horizontalDistance <= radius && (prevRadius <= 0 || horizontalDistance > prevRadius);
    }

    private static void hitReached(Cast cast, Wave wave, double prev, double r) {
        Mob caster = cast.caster();
        SkillSpec spec = cast.spec();
        Vec3 c = wave.center;
        AABB box = new AABB(c.x - r, c.y - VERTICAL_REACH, c.z - r, c.x + r, c.y + VERTICAL_REACH, c.z + r);
        for (LivingEntity victim : SkillTargets.in(cast.level(), box, caster)) {
            Vec3 at = victim.position();
            if (Math.abs(at.y - c.y) > VERTICAL_REACH) continue;
            if (!reached(prev, r, Math.hypot(at.x - c.x, at.z - c.z)) || !wave.hit.add(victim.getId())) continue;
            SkillTargets.hit(caster, victim, spec.damage(), ShockPush.outward(c, at, spec.knockback()));
        }
    }

    /** The planned blocks the ring has reached (the plan is sorted by distance) go to the carve queue. */
    private static void carvePlanned(ServerLevel level, Wave wave, double r) {
        List<BlockPos> batch = new ArrayList<>();
        while (wave.next < wave.plan.size() && wave.plan.get(wave.next).distance() <= r) {
            WalkCarve.Carve c = wave.plan.get(wave.next++);
            batch.add(new BlockPos(c.x(), c.y(), c.z()));
        }
        SkillWorld.carve(level, batch);
    }

    /** Dust and sweep marks along the circle of {@code radius} on the ground, about two blocks apart. */
    private static void ring(ServerLevel level, Vec3 center, double radius) {
        int points = Mth.clamp(Mth.ceil(Mth.TWO_PI * radius / 2.0), 8, MAX_RING_POINTS);
        WalkCarve.Blocks blocks = SkillWorld.walkBlocks(level);
        for (int i = 0; i < points; i++) {
            double a = Mth.TWO_PI * i / points;
            double x = center.x + Math.cos(a) * radius, z = center.z + Math.sin(a) * radius;
            if (!level.hasChunk(Mth.floor(x) >> 4, Mth.floor(z) >> 4)) continue;
            Vec3 p = new Vec3(x, groundY(blocks, x, center.y, z) + 0.2, z);
            SkillFx.particles(level, ParticleTypes.CLOUD, p, 1, 0.2, 0.02);
            SkillFx.particles(level, ParticleTypes.SWEEP_ATTACK, p, 1, 0.1, 0);
        }
    }

    /** Top of the first solid block from a few blocks above {@code y} down to a few below; {@code y} when none. */
    static double groundY(WalkCarve.Blocks blocks, double x, double y, double z) {
        int bx = Mth.floor(x), bz = Mth.floor(z), by = Mth.floor(y);
        for (int dy = RING_SCAN; dy >= -RING_SCAN; dy--) {
            if (blocks.solid(bx, by + dy, bz) && !blocks.solid(bx, by + dy + 1, bz)) return by + dy + 1;
        }
        return y;
    }

    /** State of one cast: where it went off, what it will break, who it already hit. */
    private static final class Wave {
        final Vec3 center;
        final List<WalkCarve.Carve> plan;
        final Set<Integer> hit = new HashSet<>();
        int next;

        Wave(Vec3 center, List<WalkCarve.Carve> plan) {
            this.center = center;
            this.plan = plan;
        }
    }
}
