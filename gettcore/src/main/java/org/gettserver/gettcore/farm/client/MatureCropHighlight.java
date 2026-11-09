package org.gettserver.gettcore.farm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
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
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.gettserver.gettcore.GettCore;

/** 주변(가로 8칸, 위아래 3칸)의 다 자란 작물에 연두색 테두리를 그린다. 항상 켜져 있음. */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class MatureCropHighlight {
    private static final double INSET = 0.02;
    private static final float R = 0.45f, G = 1.0f, B = 0.2f, A = 1.0f;

    private static final BlockPos.MutableBlockPos CURSOR = new BlockPos.MutableBlockPos();
    /** minX, minY, minZ, maxX, maxY, maxZ per box (world coords), reused between scans. */
    private static final double[] BOXES = new double[CropRipeness.MAX_BOXES * 6];
    private static int count;
    private static long ticks;

    private MatureCropHighlight() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) {
            count = 0;
            return;
        }
        if (!CropRipeness.shouldScan(ticks++)) return;
        scan(level, player.blockPosition());
    }

    private static void scan(ClientLevel level, BlockPos feet) {
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
                    VoxelShape shape = state.getShape(level, CURSOR);
                    int i = n * 6;
                    if (shape.isEmpty()) {
                        BOXES[i] = 0; BOXES[i + 1] = 0; BOXES[i + 2] = 0;
                        BOXES[i + 3] = 1; BOXES[i + 4] = 1; BOXES[i + 5] = 1;
                    } else {
                        AABB b = shape.bounds();
                        BOXES[i] = b.minX; BOXES[i + 1] = b.minY; BOXES[i + 2] = b.minZ;
                        BOXES[i + 3] = b.maxX; BOXES[i + 4] = b.maxY; BOXES[i + 5] = b.maxZ;
                    }
                    BOXES[i] += CURSOR.getX(); BOXES[i + 3] += CURSOR.getX();
                    BOXES[i + 1] += CURSOR.getY(); BOXES[i + 4] += CURSOR.getY();
                    BOXES[i + 2] += CURSOR.getZ(); BOXES[i + 5] += CURSOR.getZ();
                    if (++n >= CropRipeness.MAX_BOXES) break outer;
                }
            }
        }
        count = n;
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

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        int n = count;
        if (n == 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        for (int k = 0; k < n; k++) {
            int i = k * 6;
            LevelRenderer.renderLineBox(pose, vc,
                    BOXES[i] + INSET, BOXES[i + 1] + INSET, BOXES[i + 2] + INSET,
                    BOXES[i + 3] - INSET, BOXES[i + 4] - INSET, BOXES[i + 5] - INSET,
                    R, G, B, A);
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
