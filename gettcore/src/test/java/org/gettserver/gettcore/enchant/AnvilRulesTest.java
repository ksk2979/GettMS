package org.gettserver.gettcore.enchant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;
import org.gettserver.gettcore.enchant.SpecialEnchants.Rolled;
import org.junit.jupiter.api.Test;

class AnvilRulesTest {
    private static Optional<Rolled> r(Ability a, int level) {
        return Optional.of(new Rolled(a, level));
    }

    // (a) 수확II + 같은 도구 수확II → 수확III: 합치기 금지
    @Test
    void mergingSameAbilityIsRejected() {
        assertFalse(AnvilRules.allowed(r(Ability.HARVEST, 2), r(Ability.HARVEST, 2), true, r(Ability.HARVEST, 3)));
        assertTrue(AnvilRules.shouldCancel(r(Ability.HARVEST, 2), r(Ability.HARVEST, 2), true, false));
    }

    // (b) 수확II + 다이아몬드(수리) → 수확II 유지: 허용
    @Test
    void repairWithMaterialIsAllowed() {
        assertTrue(AnvilRules.allowed(r(Ability.HARVEST, 2), Optional.empty(), false, r(Ability.HARVEST, 2)));
        assertFalse(AnvilRules.shouldCancel(r(Ability.HARVEST, 2), Optional.empty(), false, false));
    }

    // (c) 능력 없는 도구 + 수확I 도구 → 수확I: 옮기기 금지
    @Test
    void transferToBareToolIsRejected() {
        assertFalse(AnvilRules.allowed(Optional.empty(), r(Ability.HARVEST, 1), true, r(Ability.HARVEST, 1)));
        assertTrue(AnvilRules.shouldCancel(Optional.empty(), r(Ability.HARVEST, 1), true, false));
    }

    // (d) 광맥I + 바닐라 책 → 광맥I 유지: 허용
    @Test
    void vanillaBookIsAllowed() {
        assertTrue(AnvilRules.allowed(r(Ability.VEIN, 1), Optional.empty(), false, r(Ability.VEIN, 1)));
        assertFalse(AnvilRules.shouldCancel(r(Ability.VEIN, 1), Optional.empty(), false, false));
    }

    // 특수 능력이 든 책은 덮어쓰기·추가 모두 금지
    @Test
    void bookWithSpecialIsRejected() {
        assertTrue(AnvilRules.shouldCancel(r(Ability.VEIN, 1), r(Ability.AREA, 1), false, true));
        assertTrue(AnvilRules.shouldCancel(Optional.empty(), r(Ability.VEIN, 1), false, true));
    }

    // 출력의 특수 능력이 바뀌면(덮어쓰기) 금지
    @Test
    void outputMustKeepLeftSpecial() {
        assertFalse(AnvilRules.allowed(r(Ability.VEIN, 1), Optional.empty(), false, r(Ability.VEIN, 2)));
        assertFalse(AnvilRules.allowed(r(Ability.VEIN, 1), Optional.empty(), false, Optional.empty()));
    }
}
