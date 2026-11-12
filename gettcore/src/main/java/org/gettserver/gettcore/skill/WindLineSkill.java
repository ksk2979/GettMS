package org.gettserver.gettcore.skill;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.IntPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.gettserver.gettcore.pet.boss.BossGiraffe;
import org.jetbrains.annotations.Nullable;

/**
 * {@code wind_line} (람각): wind gathers on the caster during the charge (a boss giraffe draws its foreleg back and
 * kicks, {@link BossGiraffe#EVENT_RAMGAK}), then a piercing blast flies from 1 block
 * above its feet along a straight line locked shortly before release (aimed at the target's centre); the ground it
 * passed over erupts {@code aftershock.delaySeconds} later (an aftershock buried too deep is skipped),
 * digging craters when {@code terrain} is on and its depth is above 0. The line itself punches a hole
 * {@code terrain.width} thick through what blocks it, but never digs below the caster's feet layer.
 * The line is not stopped by blocks, and never loads chunks.
 */
public final class WindLineSkill implements SkillType {
    static final double FRONT_SPEED = 1.5;        // blocks per tick
    static final int AIM_LOCK_TICKS = 6;          // direction frozen 0.3 s before release
    private static final int WIND_COLOR = 0x4FA8FF;
    private static final float WIND_SIZE = 2.0F;
    private static final double FX_STEP = 0.75;   // blocks between particle puffs along a swept section
    private static final double LIFT = 0.4;       // upward part of the line's push
    private static final double AFTERSHOCK_PUSH = 1.0;
    private static final int GROUND_SCAN = 12;
    private static final double ORIGIN_HEIGHT = 1.0;  // line origin above the caster's feet

    /** The line of each running cast, from release until the front has passed; weak so a cancelled cast is dropped. */
    private final Map<Cast, Line> lines = new WeakHashMap<>();

    @Override
    public void charge(Cast cast, int age) {
        Mob caster = cast.caster();
        if (age == 0 && caster instanceof BossGiraffe) cast.level().broadcastEntityEvent(caster, BossGiraffe.EVENT_RAMGAK);
        Vec3 chest = chest(caster);
        double radius = caster.getBbWidth() * 0.5 + 1.5;
        SkillFx.gather(cast.level(), ParticleTypes.FLAME, chest, radius, 2);
        SkillFx.gather(cast.level(), ParticleTypes.END_ROD, chest, radius, 1);
        if (age == lockAge(cast.spec().chargeTicks())) cast.aim()[0] = aim(cast, lineOrigin(caster));
    }

    @Override
    public void release(Cast cast) {
        Mob caster = cast.caster();
        SkillSpec spec = cast.spec();
        Vec3 origin = lineOrigin(caster);
        Vec3 dir = cast.aim()[0] != null ? cast.aim()[0] : LineHit.direction(origin, origin, caster.getLookAngle());
        lines.put(cast, new Line(origin, dir, spec.shape().length(), spec.shape().width() / 2,
                Math.max(1, spec.aftershock().width()), lowestCarvableY(caster.getY())));
        SkillFx.sound(cast.level(), origin, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.BREEZE_SHOOT), 2.0F);
    }

    @Override
    public boolean active(Cast cast, int age) {
        Line line = lines.get(cast);
        if (line == null || !sweeping(age, line.length)) {
            lines.remove(cast);
            return false;
        }
        ServerLevel level = cast.level();
        SkillSpec spec = cast.spec();
        Vec3 start = line.at(line.from(age));
        Vec3 end = line.at(line.to(age));
        if (loaded(level, start) && loaded(level, end)) sweep(cast, line, age, start, end);
        int delay = (int) Math.round(spec.aftershock().delaySeconds() * 20);
        double radius = spec.aftershock().width() / 2 + 1;
        for (Shock shock : line.passedShocks(line.to(age), spec.aftershock().damage(), radius, spec.terrain(),
                cast.caster().getUUID())) {
            if (!loaded(level, shock.point())) continue;
            // Ground found now, before any crater nearby is dug: a later scan would sink into the trench.
            ground(level, shock.point()).map(shock::at)
                    .ifPresent(grounded -> SkillWorld.later(level, delay, () -> erupt(level, grounded)));
        }
        return true;
    }

    /** The front sweeps [{@code from(age)}, {@code to(age)}] this tick; done once it has reached {@code length}. */
    static boolean sweeping(int age, double length) {
        return age * FRONT_SPEED < length;
    }

    /** The sweep digs no block below this height: the layer the caster's feet are in and above, never the floor under it. */
    static int lowestCarvableY(double feetY) {
        return Mth.floor(feetY);
    }

    /** Charge age at which the direction is locked (0 when the charge is shorter than the lock). */
    static int lockAge(int chargeTicks) {
        return Math.max(0, chargeTicks - AIM_LOCK_TICKS);
    }

    /** One swept section: wind particles across the width, everyone on it hit (once per cast), blocks in it dug out. */
    private static void sweep(Cast cast, Line line, int age, Vec3 start, Vec3 end) {
        ServerLevel level = cast.level();
        Mob caster = cast.caster();
        SkillSpec spec = cast.spec();
        DustParticleOptions wind = new DustParticleOptions(Vec3.fromRGB24(WIND_COLOR).toVector3f(), WIND_SIZE);
        double spread = line.halfWidth * 0.5;
        for (double t = line.from(age); t <= line.to(age); t += FX_STEP) {
            Vec3 p = line.at(t);
            SkillFx.particles(level, wind, p, 4, spread, 0);
            SkillFx.particles(level, ParticleTypes.CLOUD, p, 1, spread, 0.02);
        }
        Vec3 push = line.dir.scale(spec.knockback()).add(0, LIFT, 0);
        for (LivingEntity victim : SkillTargets.in(level, new AABB(start, end).inflate(line.halfWidth + 1), caster)) {
            if (line.sweepHits(line.from(age), line.to(age), victim.getId(), victim.getBoundingBox().getCenter())) {
                SkillTargets.hit(caster, victim, spec.damage(), push);
            }
        }
        if (spec.terrain().enabled()) {
            SkillWorld.carve(level, TerrainRules.tube(start, end, spec.terrain().width() / 2.0,
                    Math.max(level.getMinBuildHeight(), line.minCarveY), level.getMaxBuildHeight() - 1));
        }
    }

    /** A scheduled aftershock on the ground at {@code shock.point}: level-free values, so it outlives the caster. */
    private static void erupt(ServerLevel level, Shock shock) {
        if (!loaded(level, shock.point())) return;
        Vec3 ground = shock.point();
        SkillFx.particles(level, ParticleTypes.EXPLOSION, ground, 1, 0, 0);
        SkillFx.particles(level, ParticleTypes.POOF, ground, 12, shock.radius() * 0.5, 0.05);
        SkillFx.sound(level, ground, SoundEvents.GENERIC_EXPLODE, 1.0F);
        @Nullable Mob caster = level.getEntity(shock.casterId()) instanceof Mob m && m.isAlive() ? m : null;
        for (LivingEntity victim : SkillTargets.in(level, new AABB(ground, ground).inflate(shock.radius()), caster)) {
            if (!StompSkill.inRadius(ground, victim.position(), shock.radius())) continue;
            SkillTargets.hit(level, caster, victim, shock.damage(), StompSkill.push(ground, victim.position(), AFTERSHOCK_PUSH));
        }
        BlockPos surface = BlockPos.containing(ground).below();
        SkillSpec.Terrain terrain = shock.terrain();
        if (terrain.enabled() && terrain.depth() > 0) {
            SkillWorld.carve(level, TerrainRules.crater(surface, terrain.width() / 2.0, terrain.depth(),
                    level.getMinBuildHeight(), level.getMaxBuildHeight() - 1));
        } else {
            SkillFx.debrisBurst(level, surface, shock.radius());
        }
    }

    /** {@link #groundY} on the level's column at {@code point} (non-air = solid); empty = skip this aftershock. */
    private static Optional<Vec3> ground(ServerLevel level, Vec3 point) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(point).mutable();
        OptionalDouble y = groundY(point.y, by -> !level.getBlockState(pos.setY(by)).isAir());
        return y.isPresent() ? Optional.of(new Vec3(point.x, y.getAsDouble(), point.z)) : Optional.empty();
    }

    /**
     * Pure: the ground height for a shock at height {@code y}. In air: the top of the first solid block at most
     * {@link #GROUND_SCAN} below, else {@code y} itself. Inside a solid block: the first non-solid height at most
     * {@link #GROUND_SCAN} above (the surface), else empty (skip: no eruption hidden underground).
     */
    static OptionalDouble groundY(double y, IntPredicate solidAt) {
        int by = Mth.floor(y);
        if (solidAt.test(by)) {
            for (int i = 1; i <= GROUND_SCAN; i++) {
                if (!solidAt.test(by + i)) return OptionalDouble.of(by + i);
            }
            return OptionalDouble.empty();
        }
        for (int i = 1; i <= GROUND_SCAN; i++) {
            if (solidAt.test(by - i)) return OptionalDouble.of(by - i + 1);
        }
        return OptionalDouble.of(y);
    }

    /** Target's center seen from the line origin; the look direction when the target is gone or on top of it. */
    private static Vec3 aim(Cast cast, Vec3 origin) {
        Entity target = cast.level().getEntity(cast.targetId());
        Vec3 at = target != null && target.isAlive() ? target.getBoundingBox().getCenter() : origin;
        return LineHit.direction(origin, at, cast.caster().getLookAngle());
    }

    /** Where the wind gathers during the charge. */
    private static Vec3 chest(Mob caster) {
        return caster.position().add(0, caster.getBbHeight() * 0.5, 0);
    }

    /** Where the line starts: sent from the feet, so it runs along the ground rather than diving into it. */
    private static Vec3 lineOrigin(Mob caster) {
        return caster.position().add(0, ORIGIN_HEIGHT, 0);
    }

    /** Whether the chunk column holding {@code p} is loaded (never loads it). */
    private static boolean loaded(ServerLevel level, Vec3 p) {
        return level.hasChunk(Mth.floor(p.x) >> 4, Mth.floor(p.z) >> 4);
    }

    /** An aftershock waiting to go off: positions and numbers and the caster's UUID only, never the Mob. */
    record Shock(Vec3 point, double damage, double radius, SkillSpec.Terrain terrain, UUID casterId) {
        Shock at(Vec3 newPoint) {
            return new Shock(newPoint, damage, radius, terrain, casterId);
        }
    }

    /** Level-free state of one cast's line: geometry, who was already hit, which aftershocks are scheduled. */
    static final class Line {
        final Vec3 origin;
        final Vec3 dir;
        final double length;
        final double halfWidth;
        final int minCarveY;
        private final List<Vec3> shockPoints;
        private final Set<Integer> hit = new HashSet<>();
        private int nextShock;

        Line(Vec3 origin, Vec3 dir, double length, double halfWidth, double shockStep, int minCarveY) {
            this.origin = origin;
            this.dir = dir;
            this.length = length;
            this.halfWidth = halfWidth;
            this.minCarveY = minCarveY;
            this.shockPoints = LineHit.segments(origin, dir, length, shockStep);
        }

        /** Start of the section the front sweeps at {@code age}. */
        double from(int age) {
            return Math.min(age * FRONT_SPEED, length);
        }

        /** End of the section the front sweeps at {@code age} (the front after this tick). */
        double to(int age) {
            return Math.min((age + 1) * FRONT_SPEED, length);
        }

        Vec3 at(double distance) {
            return origin.add(dir.scale(distance));
        }

        /** True when {@code point} is on the line within [from, to] and entity {@code id} was not hit yet this cast. */
        boolean sweepHits(double from, double to, int id, Vec3 point) {
            double t = LineHit.along(origin, dir, length, halfWidth, point);
            return t >= from && t <= to && hit.add(id);
        }

        /** Aftershocks for the points the front (at {@code front}) has newly passed; each point once. */
        List<Shock> passedShocks(double front, double damage, double radius, SkillSpec.Terrain terrain, UUID casterId) {
            List<Shock> out = new ArrayList<>();
            while (nextShock < shockPoints.size()
                    && shockPoints.get(nextShock).subtract(origin).dot(dir) <= front + 1.0E-9) {
                out.add(new Shock(shockPoints.get(nextShock++), damage, radius, terrain, casterId));
            }
            return out;
        }
    }
}
