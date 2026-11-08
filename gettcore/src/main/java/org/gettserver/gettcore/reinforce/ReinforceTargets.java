package org.gettserver.gettcore.reinforce;

import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;

/**
 * 강화 대상 분류의 진입점. NTGL 총 판정은 {@link #ntglWeapon} 으로 주입된다(NTGL 이 없거나 브리지가 아직 없으면 항상
 * false → 총은 대상이 아님). 서버·클라이언트 모두 이 판정을 쓴다.
 */
public final class ReinforceTargets {
    /** NTGL 무기 판정. 브리지(R3)가 NTGL 이 있을 때 채운다. */
    public static volatile Predicate<ItemStack> ntglWeapon = s -> false;

    private ReinforceTargets() {}

    public static Optional<ReinforceRules.Target> target(ItemStack stack) {
        return ReinforceRules.target(stack, ntglWeapon);
    }
}
