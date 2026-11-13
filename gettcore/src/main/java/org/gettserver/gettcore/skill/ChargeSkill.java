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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.gettserver.gettcore.pet.boss.BossBear;

/**
 * {@code charge} (돌격): after a short stance the caster rushes in a straight line to where its target stood at
 * release, at most {@code shape.length} blocks, {@code shape.speed} blocks per second, one block per step. With
 * {@code terrain} on it breaks what it could not walk through (never the floor); a wall it cannot break (obsidian,
 * protected ground, block entities) ends the rush, as do a cliff and an unloaded chunk. Everyone the body sweeps
 * past is hit once and thrown aside.
 */
public final class ChargeSkill implements SkillType {
    private static final int STEP_SOUND_INTERVAL = 2;   // ticks between footsteps
    private static final int MAX_DEBRIS_PER_STEP = 3;

    /** The dash of each running cast, from release until it ends; weak so a cancelled cast is dropped. */
    private final Map<Cast, Dash> dashes = new WeakHashMap<>();

    @Override
    public void charge(Cast cast, int age) {
        Mob caster = cast.caster();
        if (age == 0 && caster instanceof BossBear) cast.level().broadcastEntityEvent(caster, BossBear.EVENT_RUSH);
        SkillFx.footDust(cast.level(), caster.position(), 3, caster.getBbWidth() * 0.5);
    }

    @Override
    public void release(Cast cast) {
        Mob caster = cast.caster();
        SkillSpec spec = cast.spec();
        Entity target = cast.level().getEntity(cast.targetId());
        Vec3 from = caster.position();
        Vec3 aimPoint = target != null && target.isAlive()
                ? target.position()
                : from.add(caster.getLookAngle().multiply(1, 0, 1).normalize().scale(spec.shape().length()));
        Vec3 goal = dashTarget(from, aimPoint, spec.shape().length());
        cast.aim()[0] = goal;
        Vec3 flat = new Vec3(goal.x - from.x, 0, goal.z - from.z);
        boolean moves = flat.lengthSqr() >= 1.0E-6;
        Vec3 dir = moves ? flat.normalize() : Vec3.ZERO;
        if (moves) face(caster, dir);
        dashes.put(cast, new Dash(from, dir, goal, Mth.floor(from.y + 1.0E-3),
                Mth.ceil(caster.getBbHeight()), Mth.ceil(caster.getBbWidth() / 2.0)));
    }

    @Override
    public boolean active(Cast cast, int age) {
        Dash dash = dashes.get(cast);
        if (dash == null) return false;
        Mob caster = cast.caster();
        ServerLevel level = cast.level();
        // The bear's melee goal keeps pathing while it casts; the dash owns the movement.
        caster.getNavigation().stop();
        WalkCarve.Blocks blocks = SkillWorld.walkBlocks(level);
        boolean finished = false;
        int steps = stepsPerTick(cast.spec().shape().speed());
        for (int i = 0; i < steps && !finished; i++) {
            if (advance(cast, dash, blocks)) sweep(cast, dash);
            else finished = true;
        }
        if (age % STEP_SOUND_INTERVAL == 0) {
            SkillFx.sound(level, caster.position(), SoundEvents.RAVAGER_STEP, 2.5F, 0.7F);
        }
        if (finished) {
            caster.setDeltaMovement(Vec3.ZERO);
            dashes.remove(cast);
            return false;
        }
        caster.setDeltaMovement(dash.dir.scale(cast.spec().shape().speed() / 20.0));
        caster.hasImpulse = true;
        caster.fallDistance = 0;
        return true;
    }

    /** Pure: the horizontal point {@code aim} seen from {@code from}, cut to {@code maxLength}; at height {@code from.y}. */
    static Vec3 dashTarget(Vec3 from, Vec3 aim, double maxLength) {
        double dx = aim.x - from.x, dz = aim.z - from.z;
        double len = Math.hypot(dx, dz);
        if (len < 1.0E-9) return from;
        if (len <= maxLength) return new Vec3(aim.x, from.y, aim.z);
        return new Vec3(from.x + dx / len * maxLength, from.y, from.z + dz / len * maxLength);
    }

    /** Pure: blocks to advance per tick at {@code speedPerSecond}, at least one. */
    static int stepsPerTick(double speedPerSecond) {
        return Math.max(1, (int) Math.round(speedPerSecond / 20.0));
    }

    /**
     * One block forward. False when the dash is over: the goal is reached, a chunk is unloaded, the way is blocked for
     * good or it is a cliff. Otherwise the caster has been moved (breaking what was in its way when terrain is on).
     */
    private static boolean advance(Cast cast, Dash d, WalkCarve.Blocks blocks) {
        ServerLevel level = cast.level();
        Mob caster = cast.caster();
        if (Math.hypot(d.goal.x - d.x, d.goal.z - d.z) < 1.0 || d.dir.lengthSqr() < 0.5) return false;
        double nx = d.x + d.dir.x, nz = d.z + d.dir.z;
        int cx = Mth.floor(nx), cz = Mth.floor(nz);
        if (!level.hasChunk(cx >> 4, cz >> 4)) return false;
        boolean terrain = cast.spec().terrain().enabled();
        Vec3 from = new Vec3(d.x, d.feetY, d.z);
        WalkCarve.Step step = WalkCarve.step(cx, d.feetY, cz, d.bodyHeight, blocks);
        if (!step.walkable()) {
            if (!terrain) return false;
            // Dug at once (the queued carve would leave the wall standing this tick), then looked at again: what
            // could not be broken is still there and ends the rush.
            SkillWorld.carveNow(level, positions(step.carve()), from, MAX_DEBRIS_PER_STEP);
            step = WalkCarve.step(cx, d.feetY, cz, d.bodyHeight, blocks);
            if (!step.walkable()) return false;
        }
        if (terrain) {
            List<BlockPos> clear = positions(step.carve());
            clear.addAll(sideBlocks(d, nx, nz, cx, cz, step.feetY(), blocks));
            SkillWorld.carveNow(level, clear, from, MAX_DEBRIS_PER_STEP);
        }
        d.x = nx;
        d.z = nz;
        d.feetY = step.feetY();
        float yaw = face(caster, d.dir);
        caster.moveTo(nx, d.feetY, nz, yaw, 0);
        Vec3 feet = new Vec3(nx, d.feetY, nz);
        SkillFx.footDust(level, feet, 4, caster.getBbWidth() * 0.5);
        SkillFx.particles(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, feet.add(0, 0.3, 0), 1, 0.3, 0.01);
        return true;
    }

    /** Body room beside the line (the bear is wider than a block): every block there at body height, solids included. */
    private static List<BlockPos> sideBlocks(Dash d, double nx, double nz, int cx, int cz, int feetY, WalkCarve.Blocks blocks) {
        List<BlockPos> out = new ArrayList<>();
        for (int k = 1; k <= d.sideReach; k++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int sx = Mth.floor(nx - d.dir.z * k * sign), sz = Mth.floor(nz + d.dir.x * k * sign);
                if (sx == cx && sz == cz) continue;
                for (int y = feetY; y < feetY + d.bodyHeight; y++) {
                    if (blocks.solid(sx, y, sz) || blocks.light(sx, y, sz)) out.add(new BlockPos(sx, y, sz));
                }
            }
        }
        return out;
    }

    private static List<BlockPos> positions(List<int[]> triples) {
        List<BlockPos> out = new ArrayList<>(triples.size());
        for (int[] t : triples) out.add(new BlockPos(t[0], t[1], t[2]));
        return out;
    }

    /** Hits everyone inside the body box (widened by {@code shape.width / 2}) once per cast. */
    private static void sweep(Cast cast, Dash d) {
        Mob caster = cast.caster();
        SkillSpec spec = cast.spec();
        double reach = spec.shape().width() / 2.0;
        AABB box = caster.getBoundingBox().inflate(reach, 0, reach);
        for (LivingEntity victim : SkillTargets.in(cast.level(), box, caster)) {
            if (!d.hit.add(victim.getId())) continue;
            SkillTargets.hit(caster, victim, spec.damage(),
                    ShockPush.sideways(d.origin, d.dir, victim.position(), spec.knockback()));
        }
    }

    /** Turns the caster to {@code dir} (horizontal); returns the yaw. */
    private static float face(Mob caster, Vec3 dir) {
        float yaw = (float) (Mth.atan2(-dir.x, dir.z) * Mth.RAD_TO_DEG);
        caster.setYRot(yaw);
        caster.setYBodyRot(yaw);
        caster.setYHeadRot(yaw);
        return yaw;
    }

    /** State of one cast's rush: the line, the cursor on it, the feet height, who was already hit. */
    private static final class Dash {
        final Vec3 origin;
        final Vec3 dir;
        final Vec3 goal;
        final int bodyHeight;
        final int sideReach;
        final Set<Integer> hit = new HashSet<>();
        double x;
        double z;
        int feetY;

        Dash(Vec3 origin, Vec3 dir, Vec3 goal, int feetY, int bodyHeight, int sideReach) {
            this.origin = origin;
            this.dir = dir;
            this.goal = goal;
            this.feetY = feetY;
            this.bodyHeight = bodyHeight;
            this.sideReach = sideReach;
            this.x = origin.x;
            this.z = origin.z;
        }
    }
}
