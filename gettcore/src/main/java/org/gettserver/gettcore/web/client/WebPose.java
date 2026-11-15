package org.gettserver.gettcore.web.client;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 발사기를 쓰는 동안의 팔 자세({@code HumanoidModelMixin} 이 부른다): 줄이 나가 있으면 그 손을 줄 쪽으로, 슬링샷을
 * 모으는 중이면 두 손을 양쪽 고정점으로, 벽에 붙어 있으면 두 팔을 위로.
 */
public final class WebPose {
    /** 팔이 몸을 뚫지 않게 몸 정면에서 좌우로 이 각도까지만 돌린다. */
    private static final float MAX_YAW = 110f;

    /** 벽에 붙었을 때 팔 각도(라디안): 정면(-90°)보다 더 올려 머리 위 앞쪽. */
    private static final float CLING_ARM = -2.5f;

    private WebPose() {}

    public static void apply(LivingEntity entity, ModelPart rightArm, ModelPart leftArm) {
        if (!(entity instanceof Player player)) return;
        WebClient.Web web = WebClient.web(player.getId());
        if (web != null) {
            aim(player, web.offhand, web.anchor, rightArm, leftArm);
            return;
        }
        Vec3[] sling = WebCombatClient.sling(player.getId());
        if (sling != null) {
            aim(player, false, WebRenderer.slingAnchor(player, false, sling), rightArm, leftArm);
            aim(player, true, WebRenderer.slingAnchor(player, true, sling), rightArm, leftArm);
            return;
        }
        if (WallCling.isClinging(player.getId())) {
            rightArm.xRot = CLING_ARM;
            leftArm.xRot = CLING_ARM;
            rightArm.yRot = 0;
            leftArm.yRot = 0;
            rightArm.zRot = 0.15f;
            leftArm.zRot = -0.15f;
        }
    }

    /** Points the arm of that hand from its shoulder at {@code target}. */
    private static void aim(Player player, boolean offhand, Vec3 target, ModelPart rightArm, ModelPart leftArm) {
        ModelPart arm = WebRenderer.side(player, offhand) > 0 ? rightArm : leftArm;
        Vec3 shoulder = WebRenderer.shoulder(player, offhand, 1.0f);
        Vec3 d = target.subtract(shoulder);
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        float yawTo = (float) (Mth.atan2(-d.x, d.z) * Mth.RAD_TO_DEG);
        float relYaw = Mth.clamp(Mth.wrapDegrees(yawTo - player.yBodyRot), -MAX_YAW, MAX_YAW);
        float pitchUp = (float) Mth.atan2(d.y, horizontal);
        // xRot -90° points the arm straight ahead; more negative raises it.
        arm.xRot = -Mth.HALF_PI - pitchUp;
        arm.yRot = relYaw * Mth.DEG_TO_RAD;
        arm.zRot = 0;
    }
}
