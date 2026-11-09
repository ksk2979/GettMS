package org.gettserver.gettcore.farm.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.gettserver.gettcore.GettCore;

/**
 * 주변(가로 8칸, 위아래 3칸)의 다 자란 작물 위로 초록 반짝이(뼛가루와 같은 파티클)가 가끔 올라온다. 항상 켜져 있음.
 * 클라이언트에서만 그리는 효과라 서버·다른 플레이어에게는 영향이 없다.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class MatureCropHighlight {
    /** 검사 한 번(10틱)마다 작물 하나에서 반짝이가 날 확률 → 작물당 초당 약 0.7개. */
    private static final float SPARKLE_CHANCE = 0.35f;

    private static final BlockPos.MutableBlockPos CURSOR = new BlockPos.MutableBlockPos();
    private static long ticks;

    private MatureCropHighlight() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player player = mc.player;
        if (level == null || player == null || mc.isPaused()) return;
        if (!CropRipeness.shouldScan(ticks++)) return;
        scan(level, player.blockPosition(), level.getRandom());
    }

    private static void scan(ClientLevel level, BlockPos feet, RandomSource random) {
        int n = 0;
        int r = CropRipeness.RADIUS;
        outer:
        for (int dy = CropRipeness.DY_MIN; dy <= CropRipeness.DY_MAX; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (!CropRipeness.inRange(dx, dy, dz)) continue;
                    CURSOR.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    BlockState state = level.getBlockState(CURSOR);
                    if (state.isAir() || !isMature(state)) continue;
                    if (random.nextFloat() < SPARKLE_CHANCE) sparkle(level, state, random);
                    if (++n >= CropRipeness.MAX_BOXES) break outer;
                }
            }
        }
    }

    /** 작물 모양 안 무작위 위치(윗부분)에서 반짝이 하나. */
    private static void sparkle(ClientLevel level, BlockState state, RandomSource random) {
        VoxelShape shape = state.getShape(level, CURSOR);
        AABB b = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
        double x = CURSOR.getX() + b.minX + random.nextDouble() * (b.maxX - b.minX);
        double y = CURSOR.getY() + b.minY + (0.5 + random.nextDouble() * 0.5) * (b.maxY - b.minY);
        double z = CURSOR.getZ() + b.minZ + random.nextDouble() * (b.maxZ - b.minZ);
        level.addParticle(ParticleTypes.HAPPY_VILLAGER, x, y, z, 0, 0.02, 0);
    }

    static boolean isMature(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) return crop.isMaxAge(state);
        if (block instanceof NetherWartBlock) return CropRipeness.isMature(state.getValue(NetherWartBlock.AGE), 3);
        if (block instanceof CocoaBlock) return CropRipeness.isMature(state.getValue(CocoaBlock.AGE), CocoaBlock.MAX_AGE);
        if (block instanceof SweetBerryBushBlock) return CropRipeness.isMature(state.getValue(SweetBerryBushBlock.AGE), SweetBerryBushBlock.MAX_AGE);
        if (!state.is(BlockTags.CROPS)) return false;
        if (CropRipeness.excludedFromFallback(BuiltInRegistries.BLOCK.getKey(block).getPath())) return false;
        for (Property<?> p : state.getProperties()) {
            if (p instanceof IntegerProperty ip && "age".equals(ip.getName())) {
                int max = 0;
                for (int v : ip.getPossibleValues()) max = Math.max(max, v);
                return CropRipeness.isMature(state.getValue(ip), max);
            }
        }
        return false;
    }
}
