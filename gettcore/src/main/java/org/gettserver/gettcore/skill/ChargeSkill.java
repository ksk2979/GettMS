package org.gettserver.gettcore.skill;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
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
import org.gettserver.gettcore.pet.boss.BossBehavior;
import org.gettserver.gettcore.pet.boss.GettBoss;

/**
 * {@code charge} (돌격): after a short stance the caster rushes in a straight line through where its target stood at
 * release and {@code shape.radius} blocks beyond it, at most {@code shape.length} blocks in all, {@code shape.speed}
 * blocks per second, one block per step. With
 * {@code terrain} on it breaks what it could not walk through up to {@code terrain.depth} blocks above the body height (never the floor); a wall it cannot break (obsidian,
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
        Vec3 goal = withinLeash(caster, from,
                dashTarget(from, aimPoint, spec.shape().radius(), spec.shape().length()));
        cast.aim()[0] = goal;
        Vec3 flat = new Vec3(goal.x - from.x, 0, goal.z - from.z);
        boolean moves = flat.lengthSqr() >= 1.0E-6;
        Vec3 dir = moves ? flat.normalize() : Vec3.ZERO;
        if (moves) face(caster, dir);
        dashes.put(cast, new Dash(from, dir, goal, Mth.floor(from.y + 1.0E-3),
                Mth.ceil(caster.getBbHeight()), spec.terrain().depth(), spec.shape().speed()));
    }

    @Override
    public boolean active(Cast cast, int age) {
        Dash dash = dashes.get(cast);
        if (dash == null) return false;
        Mob caster = cast.caster();
        ServerLevel level = cast.level();
        // The bear's melee goal keeps pathing while it casts; the dash owns the movement.
        caster.getNavigation().stop();
        // BossBear.tick runs vanilla travel before this: whatever motion is left over is undone, the dash cursor rules.
        caster.moveTo(dash.x, dash.y, dash.z, caster.getYRot(), 0);
        WalkCarve.Blocks blocks = SkillWorld.walkBlocks(level);
        boolean finished = false;
        double speed = cast.spec().shape().speed();
        Steps steps = stepsThisTick(dash.budget, speed);
        dash.budget = steps.rest();
        double startX = dash.x, startZ = dash.z;
        for (int i = 0; i < steps.steps() && !finished; i++) {
            if (advance(cast, dash, blocks)) sweep(cast, dash);
            else finished = true;
        }
        caster.fallDistance = 0;
        if (age % STEP_SOUND_INTERVAL == 0) {
            SkillFx.sound(level, caster.position(), SoundEvents.RAVAGER_STEP, 2.5F, 0.7F);
        }
        if (finished) {
            caster.setDeltaMovement(Vec3.ZERO);
            dashes.remove(cast);
            return false;
        }
        // Only for the client's interpolation: the next tick snaps the caster back to the cursor before moving on.
        caster.setDeltaMovement(dash.x - startX, 0, dash.z - startZ);
        caster.hasImpulse = true;
        return true;
    }

    /**
     * Pure: the end of the dash, at height {@code from.y}: on the horizontal line from {@code from} through
     * {@code aim}, {@code overshoot} blocks past {@code aim}, at most {@code maxLength} from {@code from}. An aim
     * straight above or below gives {@code from} (no direction).
     */
    static Vec3 dashTarget(Vec3 from, Vec3 aim, double overshoot, double maxLength) {
        double dx = aim.x - from.x, dz = aim.z - from.z;
        double len = Math.hypot(dx, dz);
        if (len < 1.0E-9) return from;
        double travel = Math.min(len + Math.max(0, overshoot), maxLength);
        return new Vec3(from.x + dx / len * travel, from.y, from.z + dz / len * travel);
    }

    /** A boss of a boss point ends its dash inside the point's leash, or it would be put back home right after. */
    private static Vec3 withinLeash(Mob caster, Vec3 from, Vec3 goal) {
        if (!(caster instanceof GettBoss boss)) return goal;
        String id = boss.bossState().pointId();
        if (id.isEmpty()) return goal;
        return BossBehavior.point(id).filter(p -> p.dimension().equals(caster.level().dimension()))
                .map(p -> keepInside(from, goal, p.pos().getX() + 0.5, p.pos().getZ() + 0.5,
                        p.radius() * 2.0 - LEASH_MARGIN))
                .orElse(goal);
    }

    /** Blocks a dash stops short of the leash edge. */
    static final double LEASH_MARGIN = 1.0;

    /**
     * Pure: {@code goal}, or the last point of the line from {@code from} to {@code goal} that is horizontally
     * within {@code maxDist} of ({@code cx}, {@code cz}). A {@code from} already outside gives {@code goal} unchanged.
     */
    static Vec3 keepInside(Vec3 from, Vec3 goal, double cx, double cz, double maxDist) {
        double fx = from.x - cx, fz = from.z - cz;
        double gx = goal.x - cx, gz = goal.z - cz;
        double max2 = maxDist * maxDist;
        if (maxDist <= 0 || fx * fx + fz * fz > max2 || gx * gx + gz * gz <= max2) return goal;
        double dx = goal.x - from.x, dz = goal.z - from.z;
        double a = dx * dx + dz * dz, b = 2 * (fx * dx + fz * dz), c = fx * fx + fz * fz - max2;
        double t = (-b + Math.sqrt(Math.max(0, b * b - 4 * a * c))) / (2 * a);
        t = Math.max(0, Math.min(1, t));
        return new Vec3(from.x + dx * t, goal.y, from.z + dz * t);
    }

    /** Unit steps to take this tick and the fraction of a step carried to the next tick. */
    record Steps(int steps, double rest) {}

    /** Pure: the carry before the first tick; makes the very first tick take a step even at a slow speed. */
    static double initialStepBudget(double speedPerSecond) {
        return Math.max(0, 1.0 - speedPerSecond / 20.0);
    }

    /** Pure: {@code budget} plus this tick's share of {@code speedPerSecond}; whole blocks are walked, the rest carried. */
    static Steps stepsThisTick(double budget, double speedPerSecond) {
        double total = budget + speedPerSecond / 20.0;
        int whole = (int) Math.floor(total + 1.0E-9);
        return new Steps(whole, Math.max(0, total - whole));
    }

    /**
     * Pure: the feet height at which the wide body fits when the centre column (cx, cz) is walkable at {@code g} and the
     * body stood at {@code prevFeet}; {@code footprint} lists the (x, z) columns the body box covers.
     *
     * <p>With {@code terrain} on, first every TALL OBSTACLE among the other columns is broken: a column with a solid
     * block anywhere from base + 1 up to the top of the body (base = max(g, prevFeet)) is cleared from base up to
     * {@code carveHeight} blocks (its base block included, so no stump is left to ride over). A column solid only at
     * base is a genuine one-block step and stays. Nothing below base is ever broken, so never the floor of the cell
     * just left.
     *
     * <p>Then candidates run from g up to max(g + 1, prevFeet) and the lowest free one wins: it rides up onto a step
     * it overlaps, and on a drop stays at the old height until the box has cleared the ledge. Only when none is free
     * and {@code terrain} is on is everything from that top height up broken and the candidates tried again. Empty:
     * the dash ends where it is. {@code breaker} receives {x, y, z} triples to break.
     */
    static OptionalInt fit(int g, int prevFeet, boolean terrain, int bodyHeight, int carveHeight,
                           List<int[]> footprint, int cx, int cz, WalkCarve.Blocks blocks, IntPredicate freeAt,
                           Consumer<List<int[]>> breaker) {
        if (terrain) {
            int base = Math.max(g, prevFeet);
            List<int[]> tall = new ArrayList<>();
            for (int[] col : footprint) {
                if (col[0] == cx && col[1] == cz) continue;
                if (tallObstacle(col[0], col[1], base, bodyHeight, blocks)) {
                    addColumn(tall, col[0], col[1], base, bodyHeight, blocks);
                }
            }
            if (!tall.isEmpty()) breaker.accept(tall);
        }
        int top = Math.max(g + 1, prevFeet);
        OptionalInt h = lowestFree(g, top, freeAt);
        if (h.isPresent() || !terrain) return h;
        List<int[]> all = new ArrayList<>();
        for (int[] col : footprint) addColumn(all, col[0], col[1], top, carveHeight, blocks);
        breaker.accept(all);
        return lowestFree(g, top, freeAt);
    }

    private static boolean tallObstacle(int x, int z, int base, int bodyHeight, WalkCarve.Blocks blocks) {
        for (int y = base + 1; y < base + bodyHeight; y++) if (blocks.solid(x, y, z)) return true;
        return false;
    }

    private static void addColumn(List<int[]> out, int x, int z, int fromY, int height, WalkCarve.Blocks blocks) {
        for (int y = fromY; y < fromY + height; y++) {
            if (blocks.solid(x, y, z) || blocks.light(x, y, z)) out.add(new int[] {x, y, z});
        }
    }

    private static OptionalInt lowestFree(int from, int to, IntPredicate freeAt) {
        for (int y = from; y <= to; y++) if (freeAt.test(y)) return OptionalInt.of(y);
        return OptionalInt.empty();
    }

    /**
     * One block forward. False when the dash is over: the goal is reached, a chunk is unloaded, the way is blocked for
     * good, it is a cliff, or the body would not fit at the new spot. Otherwise the caster has been moved (breaking
     * what was in its way when terrain is on).
     */
    private static boolean advance(Cast cast, Dash d, WalkCarve.Blocks blocks) {
        ServerLevel level = cast.level();
        Mob caster = cast.caster();
        if (Math.hypot(d.goal.x - d.x, d.goal.z - d.z) < 1.0 || d.dir.lengthSqr() < 0.5) return false;
        double nx = d.x + d.dir.x, nz = d.z + d.dir.z;
        int cx = Mth.floor(nx), cz = Mth.floor(nz);
        if (!level.hasChunk(cx >> 4, cz >> 4)) return false;
        boolean terrain = cast.spec().terrain().enabled();
        Vec3 from = new Vec3(d.x, d.y, d.z);
        WalkCarve.Step step = WalkCarve.step(cx, d.feetY, cz, d.bodyHeight, d.carveHeight, blocks);
        if (!step.walkable()) {
            if (!terrain) return false;
            // Dug at once (the queued carve would leave the wall standing this tick), then looked at again: what
            // could not be broken is still there and ends the rush.
            SkillWorld.carveNow(level, positions(step.carve()), from, MAX_DEBRIS_PER_STEP);
            step = WalkCarve.step(cx, d.feetY, cz, d.bodyHeight, d.carveHeight, blocks);
            if (!step.walkable()) return false;
        }
        // The whole body, not just the cell centre: it rides up onto a step it overlaps, and breaks what it cannot ride.
        AABB box = caster.getBoundingBox();
        int g = step.feetY();
        if (terrain) SkillWorld.carveNow(level, positions(step.carve()), from, MAX_DEBRIS_PER_STEP);
        int base = Math.max(g, d.feetY);
        List<int[]> footprint = footprint(boxAt(caster, box, nx, base, nz));
        OptionalInt fit = fit(g, d.feetY, terrain, d.bodyHeight, d.carveHeight, footprint, cx, cz, blocks,
                y -> level.noCollision(caster, boxAt(caster, box, nx, y, nz)),
                triples -> SkillWorld.carveNow(level, positions(triples), from, MAX_DEBRIS_PER_STEP));
        if (fit.isEmpty()) return false;
        step = new WalkCarve.Step(true, fit.getAsInt(), step.carve());
        d.x = nx;
        d.y = step.feetY();
        d.z = nz;
        d.feetY = step.feetY();
        float yaw = face(caster, d.dir);
        caster.moveTo(nx, d.y, nz, yaw, 0);
        Vec3 feet = new Vec3(nx, d.y, nz);
        SkillFx.footDust(level, feet, 4, caster.getBbWidth() * 0.5);
        SkillFx.particles(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, feet.add(0, 0.3, 0), 1, 0.3, 0.01);
        return true;
    }

    /** The caster's box moved so that its centre column is (nx, nz) and its bottom is at {@code feetY}. */
    private static AABB boxAt(Mob caster, AABB base, double nx, int feetY, double nz) {
        return base.move(nx - caster.getX(), feetY - caster.getY(), nz - caster.getZ());
    }

    /** The (x, z) block columns {@code box} covers. */
    static List<int[]> footprint(AABB box) {
        List<int[]> out = new ArrayList<>();
        for (int x = Mth.floor(box.minX + 1.0E-7); x <= Mth.floor(box.maxX - 1.0E-7); x++) {
            for (int z = Mth.floor(box.minZ + 1.0E-7); z <= Mth.floor(box.maxZ - 1.0E-7); z++) out.add(new int[] {x, z});
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
        final int carveHeight;
        final Set<Integer> hit = new HashSet<>();
        double x;
        double y;
        double z;
        double budget;
        int feetY;

        Dash(Vec3 origin, Vec3 dir, Vec3 goal, int feetY, int bodyHeight, int extraHeight, double speed) {
            this.origin = origin;
            this.dir = dir;
            this.goal = goal;
            this.feetY = feetY;
            this.bodyHeight = bodyHeight;
            this.carveHeight = bodyHeight + extraHeight;
            this.x = origin.x;
            this.y = origin.y;
            this.budget = initialStepBudget(speed);
            this.z = origin.z;
        }
    }
}
