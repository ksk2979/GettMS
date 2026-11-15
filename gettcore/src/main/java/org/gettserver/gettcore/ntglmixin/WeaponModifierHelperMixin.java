package org.gettserver.gettcore.ntglmixin;

import com.nukateam.ntgl.common.data.WeaponData;
import com.nukateam.ntgl.common.util.util.WeaponModifierHelper;
import org.gettserver.gettcore.GettCore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NTGL 3.2.0: {@code isOneHanded} 는 무기가 아닌 아이템도 "한손"으로 치고, {@code AimingHandler.isAiming} 은 한손 총을
 * 든 채 보조 손에 한손 아이템이 있으면 조준을 막는다(javap 확인). 손목에 찬 거미줄 발사기는 손을 차지하지 않으므로
 * 한손 아이템으로 치지 않게 해, 왼손에 발사기를 차고도 리볼버 같은 한손 총을 조준할 수 있게 한다.
 */
@Pseudo
@Mixin(value = WeaponModifierHelper.class, remap = false)
public abstract class WeaponModifierHelperMixin {
    @Inject(method = "isOneHanded(Lcom/nukateam/ntgl/common/data/WeaponData;)Z", at = @At("HEAD"), cancellable = true)
    private static void gettcore$wristShooterTakesNoHand(WeaponData data, CallbackInfoReturnable<Boolean> cir) {
        if (data != null && data.weapon != null && data.weapon.is(GettCore.WEB_SHOOTER.get())) cir.setReturnValue(false);
    }
}
