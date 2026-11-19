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
    void staffModifierIsMainhandSpellPower() {
        ModifierSpec m = single(ReinforceEffects.modifiers(Target.STAFF, 3, null, D));
        assertEquals(Stat.SPELL_POWER, m.stat());
        assertEquals("gettcore:reinforce_staff", m.id().toString());
        assertEquals(Operation.ADD_MULTIPLIED_TOTAL, m.operation());
        assertEquals(EquipmentSlotGroup.MAINHAND, m.group());
        assertEquals(0.3, m.amount(), EPS);
        assertEquals(30, ReinforceEffects.tooltipValue(Target.STAFF, 3, D), EPS);
        assertEquals("gettcore.tooltip.reinforce.staff", ReinforceEffects.tooltipKey(Target.STAFF));
    }

    @Test
    void staffPercentIsReadSanitizedAndWrittenBack() {
        var codec = ReinforceConfig.CODEC;
        ReinforceConfig c = codec.parse(com.mojang.serialization.JsonOps.INSTANCE,
                JsonParser.parseString("{\"staffSpellPowerPercentPerLevel\":4,\"damageCurve\":[{\"level\":10,\"percent\":50}]}"))
                .getOrThrow();
        assertEquals(4, c.staffSpellPowerPercentPerLevel(), EPS);
        assertEquals(1, c.damageCurve().size());
        assertEquals(c, codec.parse(com.mojang.serialization.JsonOps.INSTANCE,
                codec.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, c).getOrThrow()).getOrThrow());
        assertEquals(4, c.sanitized(new java.util.ArrayList<>()).staffSpellPowerPercentPerLevel(), EPS);
        assertEquals(12, ReinforceRules.staffPercent(3, c.withDamageCurve(List.of())), EPS);

        List<String> warnings = new java.util.ArrayList<>();
        ReinforceConfig bad = codec.parse(com.mojang.serialization.JsonOps.INSTANCE,
                JsonParser.parseString("{\"staffSpellPowerPercentPerLevel\":-1}")).getOrThrow().sanitized(warnings);
        assertEquals(10, bad.staffSpellPowerPercentPerLevel(), EPS);
        assertTrue(warnings.get(0).contains("staffSpellPowerPercentPerLevel"), warnings.toString());
    }

    @Test
    void shieldHasNoItemModifiers() {
        // 방패 이동속도는 다른 손(총·원거리 무기)을 봐야 해서 플레이어 transient 수정자로 붙는다.
        assertEquals(List.of(), ReinforceEffects.modifiers(Target.SHIELD, 20, null, D));
        assertEquals("gettcore:reinforce_shield_speed", ReinforceEffects.SHIELD_SPEED_ID.toString());
    }

    @Test
    void shieldSpeedFollowsEffectiveShieldLevel() {
        assertEquals(0.34, ReinforceEffects.shieldSpeed(20, D), EPS);
        assertEquals(0.017, ReinforceEffects.shieldSpeed(1, D), EPS);
        assertEquals(0, ReinforceEffects.shieldSpeed(0, D), EPS);
        assertEquals(0, ReinforceEffects.shieldSpeed(-3, D), EPS);
    }

    @Test
    void armorModifierPerSlot() {
        record Case(EquipmentSlot slot, String name, EquipmentSlotGroup group) {}
        for (Case c : List.of(
                new Case(EquipmentSlot.HEAD, "head", EquipmentSlotGroup.HEAD),
                new Case(EquipmentSlot.CHEST, "chest", EquipmentSlotGroup.CHEST),
                new Case(EquipmentSlot.LEGS, "legs", EquipmentSlotGroup.LEGS),
                new Case(EquipmentSlot.FEET, "feet", EquipmentSlotGroup.FEET))) {
            List<ModifierSpec> specs = ReinforceEffects.modifiers(Target.ARMOR, 10, c.slot(), D);
            assertEquals(2, specs.size(), specs.toString());
            ModifierSpec health = specs.get(0);
            assertEquals(Stat.MAX_HEALTH, health.stat());
            assertEquals("gettcore:reinforce_health_" + c.name(), health.id().toString());
            assertEquals(Operation.ADD_VALUE, health.operation());
            assertEquals(c.group(), health.group());
            assertEquals(5.0, health.amount(), EPS);
            ModifierSpec defense = specs.get(1);
            assertEquals(Stat.ARMOR, defense.stat());
            assertEquals("gettcore:reinforce_armor_" + c.name(), defense.id().toString());
            assertEquals(Operation.ADD_VALUE, defense.operation());
            assertEquals(c.group(), defense.group());
            assertEquals(0.5, defense.amount(), EPS);
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
        // 기본: 0.5/단계, 상한 60. 네 부위 +30 = 60 → 보정 0(하트 정확히 4줄).
        assertEquals(0, ReinforceEffects.healthCapAdjust(new int[]{30, 30, 30, 30}, D), EPS);
        // 50강 표로 올려도 ×4 = 100 → -40.
        assertEquals(-40, ReinforceEffects.healthCapAdjust(new int[]{50, 50, 50, 50}, D), EPS);
        // 50+50+30+0 = 25+25+15 = 65 → -5.
        assertEquals(-5, ReinforceEffects.healthCapAdjust(new int[]{50, 50, 30, 0}, D), EPS);
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
        assertEquals(5.1, ReinforceEffects.tooltipValue(Target.SHIELD, 3, D), EPS);
        assertEquals(1.5, ReinforceEffects.tooltipValue(Target.ARMOR, 3, D), EPS);
        assertEquals("1.5", ReinforceEffects.fmt(ReinforceEffects.tooltipValue(Target.ARMOR, 3, D)));
        assertEquals("30", ReinforceEffects.fmt(ReinforceEffects.tooltipValue(Target.GUN, 3, D)));
    }

    @Test
    void rangedCurveOverridesTheSharedCurveForRangedOnly() {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        ReinforceConfig c = ReinforceConfig.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString("{\"damageCurve\":[{\"level\":10,\"percent\":50}],"
                        + "\"rangedDamageCurve\":[{\"level\":10,\"percent\":20},{\"level\":99,\"percent\":1}]}"))
                .getOrThrow().sanitized(warnings);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("rangedDamageCurve"));
        assertEquals(20, ReinforceEffects.tooltipValue(Target.RANGED, 10, c), EPS);
        assertEquals(1.1, ReinforceRules.rangedMultiplier(5, c), EPS);
        assertEquals(50, ReinforceEffects.tooltipValue(Target.MELEE, 10, c), EPS);
        assertEquals(50, ReinforceEffects.tooltipValue(Target.GUN, 10, c), EPS);
        // the ranged curve survives a round trip; without it the shared curve applies
        ReinforceConfig back = ReinforceConfig.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                ReinforceConfig.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, c).getOrThrow()).getOrThrow();
        assertEquals(c, back);
        assertEquals(50, ReinforceEffects.tooltipValue(Target.RANGED, 10, c.withRangedDamageCurve(java.util.List.of())), EPS);
    }

    @Test
    void damageCurveReplacesThePerLevelPercents() {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        // out of order, one bad point: sorted and dropped by sanitize
        ReinforceConfig c = ReinforceConfig.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString("{\"damageCurve\":[{\"level\":20,\"percent\":120},"
                        + "{\"level\":10,\"percent\":50},{\"level\":30,\"percent\":200},{\"level\":0,\"percent\":9}]}"))
                .getOrThrow().sanitized(warnings);
        assertEquals(1, warnings.size());
        assertEquals(3, c.damageCurve().size());
        assertEquals(0, ReinforceEffects.tooltipValue(Target.GUN, 0, c), EPS);
        assertEquals(5, ReinforceEffects.tooltipValue(Target.GUN, 1, c), EPS);
        assertEquals(50, ReinforceEffects.tooltipValue(Target.MELEE, 10, c), EPS);
        assertEquals(57, ReinforceEffects.tooltipValue(Target.RANGED, 11, c), EPS);
        assertEquals(120, ReinforceEffects.tooltipValue(Target.GUN, 20, c), EPS);
        assertEquals(200, ReinforceEffects.tooltipValue(Target.GUN, 30, c), EPS);
        assertEquals(208, ReinforceEffects.tooltipValue(Target.GUN, 31, c), EPS); // keeps the last slope
        assertEquals(3.0, ReinforceRules.gunMultiplier(30, c), EPS);
        assertEquals(1.2, ReinforceRules.meleeFraction(20, c), EPS);
        // shield and armor are not on the curve
        assertEquals(5.1, ReinforceEffects.tooltipValue(Target.SHIELD, 3, c), EPS);
        // survives a round trip; without a curve nothing changes
        assertEquals(c, ReinforceConfig.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                ReinforceConfig.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, c).getOrThrow()).getOrThrow());
        assertEquals(30, ReinforceEffects.tooltipValue(Target.GUN, 3, D), EPS);
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
            assertTrue(lang.has(ReinforceEffects.SHIELD_NOTE_KEY), file + " missing shield note");
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
