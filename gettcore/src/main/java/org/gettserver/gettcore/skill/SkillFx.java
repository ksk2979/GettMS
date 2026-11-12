package org.gettserver.gettcore.skill;

import com.mojang.math.Transformation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Particles, sounds and flying block debris shared by the skill types. */
public final class SkillFx {
    /** Every debris display entity carries this tag; one loaded from disk is discarded. */
    public static final String DEBRIS_TAG = "gettcore_debris";
    private static final double GATHER_SPEED = 0.08;
    private static final int DEBRIS_FLIGHT_TICKS = 15;
    private static final int DEBRIS_LIFE_TICKS = 18;
    private static final int DEBRIS_PARTICLES = 8;

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

    /**
     * One chunk of {@code state} thrown from {@code pos} up 4-7 blocks and away from {@code from}, spinning, gone after
     * {@link #DEBRIS_LIFE_TICKS}; plus that block's break particles. A display entity: never a skill target.
     */
    public static void debris(ServerLevel level, BlockPos pos, BlockState state, Vec3 from) {
        Vec3 center = Vec3.atCenterOf(pos);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), center.x, center.y, center.z,
                DEBRIS_PARTICLES, 0.3, 0.3, 0.3, 0.1);
        if (!level.hasChunkAt(pos)) return;
        Display.BlockDisplay piece = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
        piece.setBlockState(state);
        piece.moveTo(pos.getX(), pos.getY(), pos.getZ());
        piece.addTag(DEBRIS_TAG);
        if (!level.addFreshEntity(piece)) return;
        RandomSource random = level.getRandom();
        Vec3 out = new Vec3(center.x - from.x, 0, center.z - from.z);
        if (out.lengthSqr() < 1.0E-6) out = new Vec3(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5);
        out = out.normalize().scale(1 + random.nextDouble() * 2);
        Vector3f axis = new Vector3f(random.nextFloat() - 0.5F, random.nextFloat() - 0.5F, random.nextFloat() - 0.5F);
        if (axis.lengthSquared() < 1.0E-6F) axis.set(0, 1, 0);
        Quaternionf spin = new Quaternionf().fromAxisAngleRad(axis.normalize(), random.nextFloat() * Mth.PI);
        Transformation flight = new Transformation(
                new Vector3f((float) out.x, 4 + random.nextFloat() * 3, (float) out.z), spin, null, null);
        // Spawned untransformed; the next tick's change is what the client interpolates over the flight.
        SkillWorld.later(level, 1, () -> {
            if (piece.isRemoved()) return;
            piece.setTransformationInterpolationDuration(DEBRIS_FLIGHT_TICKS);
            piece.setTransformationInterpolationDelay(0);
            piece.setTransformation(flight);
        });
        SkillWorld.later(level, DEBRIS_LIFE_TICKS, piece::discard);
    }

    /**
     * Debris without digging (terrain off): up to {@link SkillWorld#DEBRIS_PER_CARVE} chunks of the block at
     * {@code ground}, thrown from random spots on top of it within {@code radius}. Nothing is removed.
     */
    public static void debrisBurst(ServerLevel level, BlockPos ground, double radius) {
        if (!level.hasChunkAt(ground)) return;
        BlockState state = level.getBlockState(ground);
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) return;
        RandomSource random = level.getRandom();
        Vec3 from = Vec3.atCenterOf(ground);
        for (int i = 0; i < SkillWorld.DEBRIS_PER_CARVE; i++) {
            double angle = random.nextDouble() * Mth.TWO_PI;
            double dist = Math.sqrt(random.nextDouble()) * radius;
            BlockPos at = BlockPos.containing(from.x + Math.cos(angle) * dist, ground.getY() + 1, from.z + Math.sin(angle) * dist);
            debris(level, at, state, from);
        }
    }

    /** A hostile-category sound at {@code at}, normal pitch. */
    public static void sound(ServerLevel level, Vec3 at, Holder<SoundEvent> sound, float volume) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.HOSTILE, volume, 1.0F);
    }
}
