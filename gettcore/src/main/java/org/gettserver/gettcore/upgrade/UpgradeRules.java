package org.gettserver.gettcore.upgrade;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 업그레이드 판정(순수 함수). 아이템은 id 문자열로 다룬다. */
public final class UpgradeRules {
    private UpgradeRules() {}

    /** 재료 {@code held}개·골드 {@code balance}로 {@code step}을 할 수 있는지(다음 단계가 없으면 false). */
    public static boolean canUpgrade(Optional<UpgradeConfig.Step> step, int held, long balance) {
        return step.isPresent() && held >= step.get().count() && balance >= step.get().gold();
    }

    /** {@code start}에서 {@code next}(from → to)를 따라가면 {@code target}에 닿는지. 순환 중이어도 끝난다. */
    static boolean reaches(Map<String, String> next, String start, String target) {
        Set<String> seen = new HashSet<>();
        for (String cur = start; cur != null && seen.add(cur); cur = next.get(cur)) {
            if (cur.equals(target)) return true;
        }
        return false;
    }

    /**
     * 손상 비율 유지: 최대 내구도 {@code oldMax}에서 {@code oldDamage}만큼 닳은 장비가 최대 내구도 {@code newMax}인
     * 장비가 되면 같은 비율만큼 닳는다(반올림, 부서지지는 않게 newMax - 1 까지). 내구도가 없는 쪽이 있으면 0.
     */
    public static int damageAfter(int oldDamage, int oldMax, int newMax) {
        if (oldDamage <= 0 || oldMax <= 0 || newMax <= 0) return 0;
        double ratio = Math.min(1.0, (double) oldDamage / oldMax);
        long d = Math.round(ratio * newMax);
        return (int) Math.max(0, Math.min(newMax - 1L, d));
    }
}
