package org.gettserver.gettcore.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import org.gettserver.gettcore.web.client.WebPose;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 거미줄 발사기: 줄이 나가 있는 플레이어의 팔을 줄 쪽으로 뻗는다(소매는 이 뒤에 팔을 따라간다). */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin<T extends LivingEntity> {
    @Shadow @Final public ModelPart rightArm;
    @Shadow @Final public ModelPart leftArm;

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void gettcore$webArm(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
                                 float headPitch, CallbackInfo ci) {
        WebPose.apply(entity, rightArm, leftArm);
    }
}
