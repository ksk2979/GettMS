package org.gettserver.gettcore.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.gettserver.gettcore.enchant.SenseClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 감지(투구 특수 능력): 범위 안의 적대 몹을 이 클라이언트에서만 발광 윤곽선으로. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "shouldEntityAppearGlowing", at = @At("RETURN"), cancellable = true)
    private void gettcore$sense(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && SenseClient.glows(entity)) cir.setReturnValue(true);
    }
}
