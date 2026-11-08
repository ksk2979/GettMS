package org.gettserver.gettcore.reinforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import org.gettserver.gettcore.reinforce.ReinforceEffects.ModifierSpec;
import org.gettserver.gettcore.reinforce.ReinforceEffects.Stat;
import org.gettserver.gettcore.reinforce.ReinforceRules.Target;
import org.junit.jupiter.api.Test;

/** ReinforceEffects 의 순수 부분: 수정자 선택, 체력 상한 합산, 툴팁 수치·키. (이벤트·NTGL 브리지는 게임 필요) */
class ReinforceEffectsTest {
    private static ModifierSpec single(List<ModifierSpec> specs) {
        assertEquals(1, specs.size(), specs.toString());
        return specs.get(0);
    }

    private static final ReinforceConfig D = ReinforceConfig.DEFAULT;
    private static final double EPS = 1e-9;

    @Test
    void meleeModifierIsMainhandMultipliedTotal() {
        ModifierSpec m = single(ReinforceEffects.modifiers(Target.MELEE, 3, null, D));
        assertEquals(Stat.ATTACK_DAMAGE, m.stat());
        assertEquals("gettcore:reinforce_melee", m.id().toString());
        assertEquals(Operation.ADD_MULTIPLIED_TOTAL, m.operation());
        assertEquals(EquipmentSlotGroup.MAINHAND, m.group());
        assertEquals(0.3, m.amount(), EPS);
    }

    @Test
    void shieldModifiersArePerHandMultipliedTotal() {
        List<ModifierSpec> ms = ReinforceEffects.modifiers(Target.SHIELD, 20, null, D);
        assertEquals(2, ms.size());
        assertEquals("gettcore:reinforce_shield_speed_mainhand", ms.get(0).id().toString());
        assertEquals(EquipmentSlotGroup.MAINHAND, ms.get(0).group());
        assertEquals("gettcore:reinforce_shield_speed_offhand", ms.get(1).id().toString());
        assertEquals(EquipmentSlotGroup.OFFHAND, ms.get(1).group());
        for (ModifierSpec m : ms) {
            assertEquals(Stat.MOVEMENT_SPEED, m.stat());
            assertEquals(Operation.ADD_MULTIPLIED_TOTAL, m.operation());
            assertEquals(0.4, m.amount(), EPS);
        }
    }

    @Test
    void armorModifierPerSlot() {
        record Case(EquipmentSlot slot, String id, EquipmentSlotGroup group) {}
        for (Case c : List.of(
                new Case(EquipmentSlot.HEAD, "gettcore:reinforce_health_head", EquipmentSlotGroup.HEAD),
                new Case(EquipmentSlot.CHEST, "gettcore:reinforce_health_chest", EquipmentSlotGroup.CHEST),
                new Case(EquipmentSlot.LEGS, "gettcore:reinforce_health_legs", EquipmentSlotGroup.LEGS),
                new Case(EquipmentSlot.FEET, "gettcore:reinforce_health_feet", EquipmentSlotGroup.FEET))) {
            ModifierSpec m = single(ReinforceEffects.modifiers(Target.ARMOR, 10, c.slot(), D));
            assertEquals(Stat.MAX_HEALTH, m.stat());
            assertEquals(c.id(), m.id().toString());
            assertEquals(Operation.ADD_VALUE, m.operation());
            assertEquals(c.group(), m.group());
            assertEquals(15.0, m.amount(), EPS);
        }
    }

    @Test
    void noModifierForGunZeroLevelZeroValueOrMissingSlot() {
        assertEquals(List.of(), ReinforceEffects.modifiers(Target.GUN, 5, null, D));
        assertEquals(List.of(), ReinforceEffects.modifiers(Target.MELEE, 0, null, D));
        assertEquals(List.of(), ReinforceEffects.modifiers(Target.MELEE, -1, null, D));
        assertEquals(List.of(), ReinforceEffects.modifiers(null, 3, null, D));
        assertEquals(List.of(), ReinforceEffects.modifiers(Target.ARMOR, 3, null, D));
        ReinforceConfig zero = new ReinforceConfig(D.maxLevel(), D.successPercent(), D.stoneCost(),
                D.gunDamagePercentPerLevel(), 0, D.shieldSpeedPercentPerLevel(), D.armorHealthPerLevel(),
                D.armorHealthMax());
        assertEquals(List.of(), ReinforceEffects.modifiers(Target.MELEE, 3, null, zero));
    }

    @Test
    void healthCapAcrossFourPieces() {
        // 기본: 1.5/단계, 상한 60. 네 부위 +10 = 60 → 보정 0(정확히 하트 30칸).
        assertEquals(0, ReinforceEffects.healthCapAdjust(new int[]{10, 10, 10, 10}, D), EPS);
        // +20 ×4 = 120 → -60.
        assertEquals(-60, ReinforceEffects.healthCapAdjust(new int[]{20, 20, 20, 20}, D), EPS);
        // 20+20+5+0 = 30+30+7.5 = 67.5 → -7.5.
        assertEquals(-7.5, ReinforceEffects.healthCapAdjust(new int[]{20, 20, 5, 0}, D), EPS);
        assertEquals(0, ReinforceEffects.healthCapAdjust(new int[]{0, 0, 0, 0}, D), EPS);
        // 상한 0 = 상한 없음.
        ReinforceConfig noCap = new ReinforceConfig(D.maxLevel(), D.successPercent(), D.stoneCost(),
                D.gunDamagePercentPerLevel(), D.meleeDamagePercentPerLevel(), D.shieldSpeedPercentPerLevel(),
                D.armorHealthPerLevel(), 0);
        assertEquals(0, ReinforceEffects.healthCapAdjust(new int[]{20, 20, 20, 20}, noCap), EPS);
    }

    @Test
    void tooltipValuesAreCurrentLevelTotals() {
        assertEquals(30, ReinforceEffects.tooltipValue(Target.GUN, 3, D), EPS);
        assertEquals(30, ReinforceEffects.tooltipValue(Target.MELEE, 3, D), EPS);
        assertEquals(6, ReinforceEffects.tooltipValue(Target.SHIELD, 3, D), EPS);
        assertEquals(4.5, ReinforceEffects.tooltipValue(Target.ARMOR, 3, D), EPS);
        assertEquals("4.5", ReinforceEffects.fmt(ReinforceEffects.tooltipValue(Target.ARMOR, 3, D)));
        assertEquals("30", ReinforceEffects.fmt(ReinforceEffects.tooltipValue(Target.GUN, 3, D)));
    }

    @Test
    void fmtDropsTrailingZero() {
        assertEquals("10", ReinforceEffects.fmt(10.0));
        assertEquals("0", ReinforceEffects.fmt(0.0));
        assertEquals("1.5", ReinforceEffects.fmt(1.5));
        assertEquals("2.25", ReinforceEffects.fmt(2.25));
        assertEquals("0.3", ReinforceEffects.fmt(0.1 * 3));
    }

    @Test
    void tooltipKeysExistInBothLanguages() throws Exception {
        for (String file : List.of("ko_kr.json", "en_us.json")) {
            JsonObject lang = lang(file);
            for (Target t : Target.values()) {
                String key = ReinforceEffects.tooltipKey(t);
                assertTrue(lang.has(key), file + " missing " + key);
            }
        }
        assertEquals("gettcore.tooltip.reinforce.gun", ReinforceEffects.tooltipKey(Target.GUN));
    }

    private static JsonObject lang(String file) throws Exception {
        try (InputStream in = ReinforceEffectsTest.class.getResourceAsStream("/assets/gettcore/lang/" + file)) {
            assertNotNull(in, file);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
