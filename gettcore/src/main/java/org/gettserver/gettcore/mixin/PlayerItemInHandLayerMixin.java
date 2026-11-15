package org.gettserver.gettcore.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.gettserver.gettcore.web.client.WebCombatClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 거미줄 발사기: 슬링샷을 모으는 동안에는 왼손(보조 손)에 든 아이템을 그리지 않는다(두 손으로 줄을 당기는 자세). */
@Mixin(PlayerItemInHandLayer.class)
public abstract class PlayerItemInHandLayerMixin {
    @Inject(method = "renderArmWithItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/world/entity/HumanoidArm;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true)
    private void gettcore$hideOffhandWhileSlinging(LivingEntity entity, ItemStack stack, ItemDisplayContext context,
                                                   HumanoidArm arm, PoseStack pose, MultiBufferSource buffers,
                                                   int light, CallbackInfo ci) {
        if (arm != entity.getMainArm() && WebCombatClient.slinging(entity.getId())) ci.cancel();
    }
}
