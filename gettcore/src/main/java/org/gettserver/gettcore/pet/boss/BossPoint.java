package org.gettserver.gettcore.pet.boss;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * A place where a boss lives (boss_points.json): up to {@code maxCount} bosses of {@code bossType} are kept alive
 * around {@code pos}, each respawning {@code respawnMinutes} after one is gone, while a player is nearby.
 */
public record BossPoint(String id, ResourceKey<Level> dimension, BlockPos pos, int radius, ResourceLocation bossType,
                        int maxCount, int respawnMinutes) {
    public static final int DEFAULT_RADIUS = 16;
    public static final int DEFAULT_MAX_COUNT = 1;
    public static final int DEFAULT_RESPAWN_MINUTES = 30;

    public static final Codec<BossPoint> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(BossPoint::id),
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(BossPoint::dimension),
            BlockPos.CODEC.fieldOf("pos").forGetter(BossPoint::pos),
            Codec.INT.optionalFieldOf("radius", DEFAULT_RADIUS).forGetter(BossPoint::radius),
            ResourceLocation.CODEC.fieldOf("bossType").forGetter(BossPoint::bossType),
            Codec.INT.optionalFieldOf("maxCount", DEFAULT_MAX_COUNT).forGetter(BossPoint::maxCount),
            Codec.INT.optionalFieldOf("respawnMinutes", DEFAULT_RESPAWN_MINUTES).forGetter(BossPoint::respawnMinutes)
    ).apply(i, BossPoint::new));

    /** A point at the player's current position and dimension, respawning every {@value #DEFAULT_RESPAWN_MINUTES} min. */
    public static BossPoint of(String id, ServerPlayer at, ResourceLocation type, int radius, int max) {
        return new BossPoint(id, at.level().dimension(), at.blockPosition(), radius, type, max, DEFAULT_RESPAWN_MINUTES);
    }
}
