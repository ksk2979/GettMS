package org.gettserver.gettcore.web.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.web.WebHands;

/**
 * 거미줄 발사기는 손에 쥐는 대신 손목에 찬 모습으로 그린다: 팔 끝을 감싼 띠와 그 위의 발사 장치. 팔 파트의 좌표계
 * (어깨가 원점, 손끝이 +y 10)에서 그리므로 1인칭 팔과 3인칭 팔({@link WristBandLayer}) 양쪽에 같이 쓴다.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class WristBand {
    private static final ResourceLocation TEXTURE = GettCore.id("textures/entity/web_shooter_band.png");
    /** [right arm?][slim?] */
    private static final ModelPart[][] PARTS = new ModelPart[2][2];

    private WristBand() {}

    private static ModelPart part(boolean right, boolean slim) {
        ModelPart p = PARTS[right ? 1 : 0][slim ? 1 : 0];
        if (p == null) {
            p = bake(right, slim);
            PARTS[right ? 1 : 0][slim ? 1 : 0] = p;
        }
        return p;
    }

    /** The arm box is (x0, -2, -2) sized (w, 12, 4); the band wraps its lower end, the device sits on the front. */
    private static ModelPart bake(boolean right, boolean slim) {
        int w = slim ? 3 : 4;
        float x0 = right ? (slim ? -2f : -3f) : -1f;
        MeshDefinition mesh = new MeshDefinition();
        mesh.getRoot().addOrReplaceChild("band", CubeListBuilder.create()
                .texOffs(0, 0).addBox(x0, 5f, -2f, w, 4f, 4f, new CubeDeformation(0.4f))
                .texOffs(0, 8).addBox(x0 + 0.5f, 5.5f, -3.3f, w - 1, 3f, 1f)
                .texOffs(16, 8).addBox(x0 + w / 2f - 0.5f, 8.5f, -3.2f, 1f, 1f, 1f), PartPose.ZERO);
        return LayerDefinition.create(mesh, 32, 16).bakeRoot();
    }

    /** Draws the band; {@code pose} must already be in the arm part's space. */
    static void render(PoseStack pose, MultiBufferSource buffers, int light, boolean right, boolean slim) {
        part(right, slim).render(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), light,
                OverlayTexture.NO_OVERLAY);
    }

    static boolean slim(Player player) {
        return player instanceof AbstractClientPlayer p && p.getSkin().model() == PlayerSkin.Model.SLIM;
    }

    /**
     * 1인칭: 아이템 대신 맨팔과 손목 장치를 그린다. 주 손에 원거리·양손 무기를 들었으면 왼손 발사기는 그리지 않는다
     * (그 무기가 두 손을 다 쓴다). 슬링샷을 모으는 동안에는 왼손에 든 것 대신 맨팔을 그린다.
     */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        Minecraft mc = Minecraft.getInstance();
        AbstractClientPlayer player = mc.player;
        if (player == null) return;
        boolean shooter = event.getItemStack().is(GettCore.WEB_SHOOTER.get());
        boolean off = event.getHand() == InteractionHand.OFF_HAND;
        boolean slingArm = off && WebCombatClient.slinging(player.getId());
        if (!shooter && !slingArm) return;
        event.setCanceled(true);
        if (player.isInvisible()) return;
        if (off && !slingArm && WebHands.mainBlocksOffhand(player)) return;
        HumanoidArm side = event.getHand() == InteractionHand.MAIN_HAND ? player.getMainArm()
                : player.getMainArm().getOpposite();
        boolean right = side == HumanoidArm.RIGHT;
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        mc.getEntityRenderDispatcher().getItemInHandRenderer().renderPlayerArm(pose, event.getMultiBufferSource(),
                event.getPackedLight(), slingArm ? 0f : event.getEquipProgress(),
                slingArm ? 0f : event.getSwingProgress(), side);
        PlayerRenderer renderer = (PlayerRenderer) mc.getEntityRenderDispatcher().<AbstractClientPlayer>getRenderer(player);
        PlayerModel<AbstractClientPlayer> model = renderer.getModel();
        if (shooter) {
            (right ? model.rightArm : model.leftArm).translateAndRotate(pose);
            render(pose, event.getMultiBufferSource(), event.getPackedLight(), right, slim(player));
        }
        pose.popPose();
    }
}
