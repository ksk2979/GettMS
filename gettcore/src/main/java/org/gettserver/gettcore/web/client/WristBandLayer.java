package org.gettserver.gettcore.web.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;

/** 3인칭: 거미줄 발사기를 든 손의 손목에 띠와 장치를 그린다(아이템 자체는 손에서 보이지 않는다). */
public class WristBandLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    public WristBandLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        InteractionHand hand = WebClient.shooterHand(player);
        if (hand == null || player.isInvisible()) return;
        HumanoidArm side = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        boolean right = side == HumanoidArm.RIGHT;
        pose.pushPose();
        (right ? getParentModel().rightArm : getParentModel().leftArm).translateAndRotate(pose);
        WristBand.render(pose, buffers, light, right, WristBand.slim(player));
        pose.popPose();
    }
}
