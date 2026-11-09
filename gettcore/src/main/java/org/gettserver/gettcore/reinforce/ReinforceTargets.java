package org.gettserver.gettcore.reinforce;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 강화 대상 분류의 진입점. NTGL 총 판정은 {@link #ntglWeapon} 으로 주입된다(NTGL 이 없거나 브리지가 아직 없으면 항상
 * false → 총은 대상이 아님). 서버·클라이언트 모두 이 판정을 쓴다.
 */
public final class ReinforceTargets {
    /** NTGL 무기 판정. 브리지(R3)가 NTGL 이 있을 때 채운다. */
    public static volatile Predicate<ItemStack> ntglWeapon = s -> false;

    /** Curios 칸의 겉날개 조회. Curios 가 있을 때 {@code CuriosBridge} 가 채운다(없으면 항상 빈 스택). */
    public static volatile Function<LivingEntity, ItemStack> curiosElytra = e -> ItemStack.EMPTY;

    private ReinforceTargets() {}

    /** 강화 대상 겉날개인지. */
    public static boolean isElytra(ItemStack stack) {
        return target(stack).orElse(null) == ReinforceRules.Target.ELYTRA;
    }

    /** 착용한 겉날개: 흉갑 칸이 겉날개면 그것, 아니면 Curios 칸(등)의 겉날개, 없으면 빈 스택. */
    public static ItemStack wornElytra(LivingEntity entity) {
        ItemStack chest = entity.getItemBySlot(EquipmentSlot.CHEST);
        if (isElytra(chest)) return chest;
        return curiosElytra.apply(entity);
    }

    public static Optional<ReinforceRules.Target> target(ItemStack stack) {
        return ReinforceRules.target(stack, ntglWeapon);
    }

    /** 주손 장비가 방패 효과를 막는지(총·활·석궁·삼지창). */
    public static boolean blocksShield(ItemStack mainHand) {
        return ReinforceRules.blocksShield(mainHand, ntglWeapon);
    }
}
