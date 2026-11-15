package org.gettserver.gettcore.web.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.web.WebRules;

/** 거미줄(손 → 줄 끝) 선과 착지 구르기 회전을 그린다. */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class WebRenderer {
    private static final int R = 240, G = 240, B = 240, A = 255;
    /** Players whose pose was pushed in Pre and must be popped in Post. */
    private static final Set<Integer> ROLLING = new HashSet<>();

    private WebRenderer() {}

    /** +1 when the web leaves the right hand, -1 for the left. */
    static int side(Player player, boolean offhand) {
        int main = player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
        return offhand ? -main : main;
    }

    /** Where the web leaves the hand (same idea as the vanilla fishing line). */
    static Vec3 handPos(Minecraft mc, Player player, boolean offhand, float partialTick) {
        WebClient.Web web = WebClient.web(player.getId());
        return handPos(mc, player, offhand, partialTick, web == null ? null : web.anchor);
    }

    /** As above, with the arm stretched towards {@code toward} (null = hanging at the shoulder). */
    static Vec3 handPos(Minecraft mc, Player player, boolean offhand, float partialTick, Vec3 toward) {
        int i = side(player, offhand);
        if (mc.options.getCameraType().isFirstPerson() && player == mc.player) {
            double fov = 960.0 / mc.options.fov().get();
            Vec3 offset = mc.gameRenderer.getMainCamera().getNearPlane().getPointOnPlane(i * 0.5f, -0.3f).scale(fov);
            return player.getEyePosition(partialTick).add(offset);
        }
        // Third person: the wrist of the arm stretched towards the anchor (see WebPose).
        Vec3 shoulder = shoulder(player, offhand, partialTick);
        if (toward == null) return shoulder;
        Vec3 d = toward.subtract(shoulder);
        double len = d.length();
        return len < 1.0e-3 ? shoulder : shoulder.add(d.scale(WRIST / len * player.getScale()));
    }

    /** 어깨에서 손목 장치까지의 거리(칸): 팔 12픽셀 중 9픽셀쯤. */
    private static final double WRIST = 0.56;

    /** The shoulder joint of the arm that wears the shooter. */
    static Vec3 shoulder(Player player, boolean offhand, float partialTick) {
        int i = side(player, offhand);
        float yaw = Mth.lerp(partialTick, player.yBodyRotO, player.yBodyRot) * Mth.DEG_TO_RAD;
        float scale = player.getScale();
        double side = i * 0.3125 * scale;
        double y = (player.isCrouching() ? 1.175 : 1.375) * scale;
        // right of a player facing yaw is (-cos, 0, -sin)
        return player.getPosition(partialTick).add(-Mth.cos(yaw) * side, y, -Mth.sin(yaw) * side);
    }

    /** The end of the web right now: on its way to the anchor, or at it. */
    static Vec3 tip(WebClient.Web web, Vec3 hand, long now, float partialTick) {
        double p = WebRules.progress(now - web.start + partialTick, web.flight);
        return p >= 1 ? web.anchor : hand.lerp(web.anchor, p);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Map<Integer, WebClient.Web> webs = WebClient.webs();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (webs.isEmpty() && WebCombatClient.shots().isEmpty() && WebCombatClient.slings().isEmpty()) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        long now = mc.level.getGameTime();
        Vec3 cam = event.getCamera().getPosition();
        PoseStack.Pose pose = event.getPoseStack().last();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer out = buffers.getBuffer(RenderType.lines());
        for (Map.Entry<Integer, WebClient.Web> e : webs.entrySet()) {
            if (!(mc.level.getEntity(e.getKey()) instanceof Player player)) continue;
            WebClient.Web web = e.getValue();
            Vec3 from = handPos(mc, player, web.offhand, partial);
            line(out, pose, cam, from, tip(web, from, now, partial));
        }
        // 날아가는 탄: 손목에서 목표까지 가는 짧은 줄기(묶기 탄은 더 길다).
        for (WebCombatClient.Shot shot : WebCombatClient.shots()) {
            if (!(mc.level.getEntity(shot.shooter()) instanceof Player player)) continue;
            Vec3 from = handPos(mc, player, shot.offhand(), partial, shot.end());
            double total = from.distanceTo(shot.end());
            if (total < 1.0e-3) continue;
            double head = WebRules.progress(now - shot.start() + partial, shot.flight());
            double tail = Math.max(0, head - (shot.bind() ? 2.5 : 1.2) / total);
            line(out, pose, cam, from.lerp(shot.end(), tail), from.lerp(shot.end(), head));
        }
        // 슬링샷: 두 손목에서 양쪽 고정점까지.
        for (Map.Entry<Integer, Vec3[]> e : WebCombatClient.slings().entrySet()) {
            if (!(mc.level.getEntity(e.getKey()) instanceof Player player)) continue;
            for (int hand = 0; hand < 2; hand++) {
                boolean offhand = hand == 1;
                Vec3 anchor = slingAnchor(player, offhand, e.getValue());
                line(out, pose, cam, handPos(mc, player, offhand, partial, anchor), anchor);
            }
        }
        buffers.endBatch(RenderType.lines());
    }

    /** The slingshot anchor of one hand: the right arm takes the right one ({@code anchors[0]}). */
    static Vec3 slingAnchor(Player player, boolean offhand, Vec3[] anchors) {
        return anchors[side(player, offhand) > 0 ? 0 : 1];
    }

    private static void line(VertexConsumer out, PoseStack.Pose pose, Vec3 cam, Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 1.0e-3) return;
        float nx = (float) (d.x / len), ny = (float) (d.y / len), nz = (float) (d.z / len);
        out.addVertex(pose, (float) (from.x - cam.x), (float) (from.y - cam.y), (float) (from.z - cam.z))
                .setColor(R, G, B, A).setNormal(pose, nx, ny, nz);
        out.addVertex(pose, (float) (to.x - cam.x), (float) (to.y - cam.y), (float) (to.z - cam.z))
                .setColor(R, G, B, A).setNormal(pose, nx, ny, nz);
    }

    /** 착지 구르기: 몸 가운데를 축으로 앞으로 한 바퀴. */
    @SubscribeEvent
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        Player player = event.getEntity();
        Long start = WebClient.rollStart(player.getId());
        if (start == null) return;
        float partial = event.getPartialTick();
        float angle = WebRules.rollDegrees(player.level().getGameTime() - start + partial, WebClient.ROLL_TICKS);
        if (angle == 0) return;
        float yaw = Mth.lerp(partial, player.yBodyRotO, player.yBodyRot);
        float half = player.getBbHeight() / 2f;
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(0, half, 0);
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));
        pose.mulPose(Axis.XP.rotationDegrees(angle));
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.translate(0, -half, 0);
        ROLLING.add(player.getId());
    }

    @SubscribeEvent
    public static void onRenderPlayerPost(RenderPlayerEvent.Post event) {
        if (ROLLING.remove(event.getEntity().getId())) event.getPoseStack().popPose();
    }
}
