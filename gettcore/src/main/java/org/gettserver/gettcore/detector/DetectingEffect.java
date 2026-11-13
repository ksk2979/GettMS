package org.gettserver.gettcore.detector;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/** 탐지 표시 남은 시간을 HUD 효과 목록에 보이기 위한 효과. 속성·틱 동작 없음. */
public final class DetectingEffect extends MobEffect {
    public DetectingEffect() {
        super(MobEffectCategory.BENEFICIAL, 0xFFFFFF);
    }
}
