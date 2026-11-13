package org.gettserver.gettcore.detector.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.gettserver.gettcore.GettCore;

/** 캐시된 윤곽선 선분을 카메라 기준으로 그린다. 깊이 검사가 없어 벽 너머로도 보인다. */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class OutlineRenderer {
    private OutlineRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        int[] edges = DetectorHighlights.edges();
        if (edges.length == 0) return;

        // 이 단계에서 RenderSystem 의 모델뷰 행렬에는 이미 카메라 회전이 들어 있으므로, 포즈에는 이동만 넣는다.
        Vec3 cam = event.getCamera().getPosition();
        PoseStack.Pose pose = event.getPoseStack().last();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer out = buffers.getBuffer(OutlineRenderType.OUTLINE);
        for (int i = 0; i < edges.length; i += 6) {
            float nx = edges[i + 3] - edges[i];
            float ny = edges[i + 4] - edges[i + 1];
            float nz = edges[i + 5] - edges[i + 2];
            out.addVertex(pose, (float) (edges[i] - cam.x), (float) (edges[i + 1] - cam.y), (float) (edges[i + 2] - cam.z))
                    .setColor(255, 255, 255, 255)
                    .setNormal(pose, nx, ny, nz);
            out.addVertex(pose, (float) (edges[i + 3] - cam.x), (float) (edges[i + 4] - cam.y), (float) (edges[i + 5] - cam.z))
                    .setColor(255, 255, 255, 255)
                    .setNormal(pose, nx, ny, nz);
        }
        buffers.endBatch(OutlineRenderType.OUTLINE);
    }
}
