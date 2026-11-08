package org.gettserver.gettcore.reinforce;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import org.gettserver.gettcore.GettCore;

/**
 * 강화 규칙(순수 함수). 단계 N 은 {@code gettcore:reinforce_level} 컴포넌트 값(없으면 0). NTGL 무기 판정은 호출자가
 * {@code Predicate<ItemStack>} 로 주입한다(이 클래스는 NTGL 타입을 모른다).
 */
public final class ReinforceRules {

    public enum Target { GUN, MELEE, SHIELD, ARMOR }

    private ReinforceRules() {}

    /**
     * 강화 대상 분류. 총(주입된 NTGL 판정)이 먼저, 그 다음 SwordItem/AxeItem/{@code #minecraft:swords}/
     * {@code #minecraft:axes} → MELEE, ShieldItem → SHIELD, ArmorItem → ARMOR. 그 밖(빈 스택·도구·활·석궁)은 empty.
     */
    public static Optional<Target> target(ItemStack stack, Predicate<ItemStack> isNtglWeapon) {
        if (stack.isEmpty()) return Optional.empty();
        Item item = stack.getItem();
        return classify(isNtglWeapon.test(stack),
                item instanceof SwordItem || item instanceof AxeItem
                        || stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES),
                item instanceof ShieldItem,
                item instanceof ArmorItem);
    }

    /** {@link #target} 의 우선순위만 떼어 낸 부분(레지스트리 없이 테스트). */
    static Optional<Target> classify(boolean gun, boolean melee, boolean shield, boolean armor) {
        if (gun) return Optional.of(Target.GUN);
        if (melee) return Optional.of(Target.MELEE);
        if (shield) return Optional.of(Target.SHIELD);
        if (armor) return Optional.of(Target.ARMOR);
        return Optional.empty();
    }

    /**
     * 주손 장비가 방패 효과를 막는지: 총(주입된 판정 = 분류 GUN)이거나 활·석궁({@link ProjectileWeaponItem})·삼지창이면
     * 양손 무기로 보고 방패(이동속도·단계 능력)는 아무것도 주지 않는다.
     */
    public static boolean blocksShield(ItemStack mainHand, Predicate<ItemStack> isGun) {
        if (mainHand.isEmpty()) return false;
        Item item = mainHand.getItem();
        return blocksShield(isGun.test(mainHand), item instanceof ProjectileWeaponItem || item instanceof TridentItem);
    }

    /** {@link #blocksShield(ItemStack, Predicate)} 의 판정만 떼어 낸 부분(레지스트리 없이 테스트). */
    static boolean blocksShield(boolean gun, boolean ranged) {
        return gun || ranged;
    }

    /** 강화 단계(컴포넌트 없으면 0). */
    public static int level(ItemStack stack) {
        return Math.max(0, stack.getOrDefault(GettCore.REINFORCE_LEVEL.get(), 0));
    }

    /** 강화 단계를 쓴다. 0 이하이면 컴포넌트를 지워 0 단계 = 컴포넌트 없음을 유지한다. */
    public static void setLevel(ItemStack stack, int level) {
        if (level <= 0) {
            stack.remove(GettCore.REINFORCE_LEVEL.get());
        } else {
            stack.set(GettCore.REINFORCE_LEVEL.get(), level);
        }
    }

    /** 단계 {@code level} 에서 한 번 더 강화할 수 있는지(최대 단계 미만). */
    public static boolean canReinforce(int level, ReinforceConfig config) {
        return level >= 0 && level < config.maxLevel();
    }

    /** {@code level} → {@code level+1} 강화에 드는 강화석 수. 강화할 수 없는 단계면 0. */
    public static int stoneCost(int level, ReinforceConfig config) {
        if (!canReinforce(level, config)) return 0;
        return at(config.stoneCost(), level, 1);
    }

    /** {@code level} → {@code level+1} 강화 성공률(%). 강화할 수 없는 단계면 0. */
    public static double successPercent(int level, ReinforceConfig config) {
        if (!canReinforce(level, config)) return 0;
        return at(config.successPercent(), level, 0.0);
    }

    /** 굴림: {@code r} 은 [0, 1) 균등 난수. {@code r*100 < 성공률} 이면 성공(100% 는 항상, 0% 는 절대 성공 못 함). */
    public static boolean roll(int level, ReinforceConfig config, double r) {
        return canReinforce(level, config) && r * 100 < successPercent(level, config);
    }

    /** 총 피해 배율 1 + N × gunDamagePercentPerLevel/100. */
    public static double gunMultiplier(int level, ReinforceConfig config) {
        return 1 + Math.max(0, level) * config.gunDamagePercentPerLevel() / 100;
    }

    /** 근접 공격력 ADD_MULTIPLIED_TOTAL 값 N × meleeDamagePercentPerLevel/100 (기본 10%/단계 → 0.1×N). */
    public static double meleeFraction(int level, ReinforceConfig config) {
        return Math.max(0, level) * config.meleeDamagePercentPerLevel() / 100;
    }

    /** 방패 이동속도 ADD_MULTIPLIED_TOTAL 값 N × shieldSpeedPercentPerLevel/100. */
    public static double shieldSpeedFraction(int level, ReinforceConfig config) {
        return Math.max(0, level) * config.shieldSpeedPercentPerLevel() / 100;
    }

    /** 방어구 한 부위의 최대 체력 증가 N × armorHealthPerLevel. */
    public static double armorHealth(int level, ReinforceConfig config) {
        return Math.max(0, level) * config.armorHealthPerLevel();
    }

    /** 네 부위 합 {@code sum} 이 상한을 넘는 만큼의 음수 보정(≤ 0). 상한 0 이면 상한 없음 → 0. */
    public static double healthCapAdjust(double sum, ReinforceConfig config) {
        double cap = config.armorHealthMax();
        if (cap <= 0) return 0;
        return Math.min(0, cap - sum);
    }

    private static <T> T at(List<T> list, int index, T fallback) {
        if (list.isEmpty()) return fallback;
        return list.get(Math.min(index, list.size() - 1));
    }
}
