package org.gettserver.gettcore.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.gettserver.gettcore.GettCore;

/** 귀환서 시전 중인 플레이어 발밑에 회전하는 마법진 텍스처를 그린다. */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class MagicCircleRenderer {
    private static final ResourceLocation TEXTURE = GettCore.id("textures/effect/magic_circle.png");
    private static final float RADIUS = 1.7f;
    private static final float GROW_TICKS = 10f;
    private static final float SPIN_DEG_PER_TICK = 2.0f;

    private MagicCircleRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Map<Integer, Long> casts = ClientReturnCasts.all();
        if (casts.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        long now = mc.level.getGameTime();
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RenderType type = RenderType.entityTranslucentEmissive(TEXTURE);
        VertexConsumer vc = buffers.getBuffer(type);

        var gone = new ArrayList<Integer>();
        for (var e : casts.entrySet()) {
            Entity entity = mc.level.getEntity(e.getKey());
            if (entity == null || entity.isRemoved()) {
                gone.add(e.getKey());
                continue;
            }
            float age = (now - e.getValue()) + pt;
            float grow = easeOut(Mth.clamp(age / GROW_TICKS, 0f, 1f));
            float scale = RADIUS * (0.3f + 0.7f * grow);
            float pulse = 0.85f + 0.15f * Mth.sin(age * 0.25f);
            int alpha = (int) (255 * grow * pulse);
            Vec3 p = entity.getPosition(pt);

            pose.pushPose();
            pose.translate(p.x - cam.x, p.y - cam.y + 0.03, p.z - cam.z);
            pose.mulPose(Axis.YP.rotationDegrees(age * SPIN_DEG_PER_TICK));
            pose.scale(scale, 1f, scale);
            quad(vc, pose, alpha);
            // 안쪽에 반대로 도는 작은 원을 하나 더 겹쳐 입체감을 준다.
            pose.mulPose(Axis.YP.rotationDegrees(-age * SPIN_DEG_PER_TICK * 2.5f));
            pose.scale(0.55f, 1f, 0.55f);
            pose.translate(0, 0.02, 0);
            quad(vc, pose, alpha / 2);
            pose.popPose();
        }
        gone.forEach(ClientReturnCasts::remove);
        buffers.endBatch(type);
    }

    private static void quad(VertexConsumer vc, PoseStack pose, int alpha) {
        PoseStack.Pose last = pose.last();
        vertex(vc, last, -1, 0, -1, 0, 0, alpha);
        vertex(vc, last, -1, 0, 1, 0, 1, alpha);
        vertex(vc, last, 1, 0, 1, 1, 1, alpha);
        vertex(vc, last, 1, 0, -1, 1, 0, alpha);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, float u, float v, int alpha) {
        vc.addVertex(pose, x, y, z)
                .setColor(255, 255, 255, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, 0f, 1f, 0f);
    }

    private static float easeOut(float t) {
        return 1f - (1f - t) * (1f - t);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientReturnCasts.clear();
    }
}
