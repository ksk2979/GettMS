package org.gettserver.gettcore.enchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.gettserver.gettcore.enchant.SpecialEnchants.LevelWeights;
import org.junit.jupiter.api.Test;

class EnchantConfigTest {

    private static EnchantConfig parse(String json) {
        return EnchantConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    @Test
    void parsesDefaultsFile() throws Exception {
        try (InputStream in = EnchantConfigTest.class.getResourceAsStream("/gettcore_defaults/enchant.json")) {
            assertNotNull(in, "bundled enchant.json");
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            EnchantConfig parsed = EnchantConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            assertEquals(EnchantConfig.DEFAULT, parsed);
            List<String> errors = new ArrayList<>();
            assertEquals(EnchantConfig.DEFAULT, parsed.sanitized(errors));
            assertTrue(errors.isEmpty(), errors.toString());
        }
    }

    @Test
    void defaultRoundTrips() {
        var encoded = EnchantConfig.CODEC.encodeStart(JsonOps.INSTANCE, EnchantConfig.DEFAULT).getOrThrow();
        assertEquals(EnchantConfig.DEFAULT, EnchantConfig.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void invalidFieldFallsBackAlone() {
        EnchantConfig c = parse("{\"harvest\":{\"radius\":[1,2]},\"vein\":{\"maxBlocks\":[8,16,32]}}");
        List<String> errors = new ArrayList<>();
        EnchantConfig s = c.sanitized(errors);
        assertEquals(EnchantConfig.DEFAULT.harvest(), s.harvest());
        assertEquals(32, s.vein().three());
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("harvest"));
    }

    @Test
    void zeroWeightsReported() {
        EnchantConfig c = parse("{\"levelWeights\":{\"1\":0,\"2\":0,\"3\":0}}");
        List<String> errors = new ArrayList<>();
        EnchantConfig s = c.sanitized(errors);
        assertEquals(LevelWeights.DEFAULT, s.levelWeights());
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("levelWeights"));
    }

    @Test
    void negativeScalarFallsBack() {
        EnchantConfig c = parse("{\"sweep\":{\"damagePercent\":-5},\"homing\":{\"range\":12}}");
        List<String> errors = new ArrayList<>();
        EnchantConfig s = c.sanitized(errors);
        assertEquals(EnchantConfig.DEFAULT.sweepDamagePercent(), s.sweepDamagePercent());
        assertEquals(12, s.homingRange());
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("sweep.damagePercent"));
    }

    @Test
    void valueReturnsPrimaryTieredNumber() {
        EnchantConfig d = EnchantConfig.DEFAULT;
        assertEquals(2, d.value(SpecialEnchants.Ability.HARVEST, 2));
        assertEquals(100, d.value(SpecialEnchants.Ability.EXECUTE, 3));
        assertEquals(10, d.value(SpecialEnchants.Ability.VOLLEY, 1));
        assertEquals(8, d.value(SpecialEnchants.Ability.HOMING, 1));
        assertEquals(0, d.value(SpecialEnchants.Ability.SMELT, 1));
    }

    @Test
    void armorDefaultsWeightsAndSanitize() {
        ArmorConfig d = EnchantConfig.DEFAULT.armor();
        assertEquals(new LevelWeights(75, 20, 5), d.levelWeights());
        assertEquals(new EnchantConfig.Tiered(5, 10, 20), d.swift());
        assertEquals(new EnchantConfig.Tiered(2, 3, 5), d.undying().invulnerableSeconds());
        assertEquals(new EnchantConfig.Tiered(300, 300, 300), d.undying().cooldownSeconds());
        assertEquals(d, parse("{\"armor\":{}}").armor());
        assertEquals(40, d.weight(SpecialEnchants.Tool.BOOTS, SpecialEnchants.Ability.SWIFT));
        assertEquals(10, d.weight(SpecialEnchants.Tool.BOOTS, SpecialEnchants.Ability.LANDING));
        assertEquals(0, d.weight(SpecialEnchants.Tool.BOOTS, SpecialEnchants.Ability.UNDYING));

        // 일부만 적으면 나머지는 기본 가중치, 음수는 기본값 + 오류 한 줄
        ArmorConfig c = parse("{\"armor\":{\"abilityWeights\":{\"helmet\":{\"nightvision\":0,\"sense\":-4}},"
                + "\"undying\":{\"cooldownSeconds\":120},\"swift\":{\"percent\":[1,2]},"
                + "\"levelWeights\":{\"1\":0,\"2\":0,\"3\":0}}}").armor();
        List<String> errors = new ArrayList<>();
        ArmorConfig s = c.sanitized(errors);
        assertEquals(0, s.weight(SpecialEnchants.Tool.HELMET, SpecialEnchants.Ability.NIGHTVISION));
        assertEquals(20, s.weight(SpecialEnchants.Tool.HELMET, SpecialEnchants.Ability.SENSE));
        assertEquals(30, s.weight(SpecialEnchants.Tool.HELMET, SpecialEnchants.Ability.GOGGLES));
        assertEquals(new EnchantConfig.Tiered(120, 120, 120), s.undying().cooldownSeconds());
        assertEquals(d.swift(), s.swift());
        assertEquals(d.levelWeights(), s.levelWeights());
        assertEquals(3, errors.size(), errors.toString());

        // 설명 줄 수치: 활력은 하트 칸, 불굴 쿨타임은 분
        EnchantConfig all = EnchantConfig.DEFAULT;
        assertEquals(2.0, all.descArgs(SpecialEnchants.Ability.VITALITY, 2)[0]);
        assertEquals(5.0, all.descArgs(SpecialEnchants.Ability.UNDYING, 3)[0]);
        assertEquals(5.0, all.descArgs(SpecialEnchants.Ability.UNDYING, 3)[1]);
        assertEquals(3, all.descArgs(SpecialEnchants.Ability.PROSPECT, 1).length);
    }

    @Test
    void prospectDefaultsAndSanitize() {
        EnchantConfig d = EnchantConfig.DEFAULT;
        assertEquals(new EnchantConfig.Tiered(8, 12, 16), d.prospectRange());
        assertEquals(new EnchantConfig.Tiered(30, 35, 45), d.prospectDurationSeconds());
        assertEquals(new EnchantConfig.Tiered(70, 65, 60), d.prospectCooldownSeconds());
        assertEquals(d, parse("{\"prospect\":{}}"));
        EnchantConfig c = parse("{\"prospect\":{\"range\":[1,2]}}");
        List<String> errors = new ArrayList<>();
        EnchantConfig s = c.sanitized(errors);
        assertEquals(d.prospectRange(), s.prospectRange());
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("prospect.range"), errors.toString());
        assertEquals(12, s.value(SpecialEnchants.Ability.PROSPECT, 2));

        List<String> scalarErrors = new ArrayList<>();
        EnchantConfig neg = parse("{\"prospect\":{\"durationSeconds\":-1,\"cooldownSeconds\":-5}}")
                .sanitized(scalarErrors);
        assertEquals(d.prospectDurationSeconds(), neg.prospectDurationSeconds());
        assertEquals(d.prospectCooldownSeconds(), neg.prospectCooldownSeconds());
        assertEquals(2, scalarErrors.size(), scalarErrors.toString());
        assertTrue(scalarErrors.get(0).contains("prospect.durationSeconds"));
        assertTrue(scalarErrors.get(1).contains("prospect.cooldownSeconds"));

        // 예전 형식(숫자 하나)은 세 레벨 모두 그 값; 새 형식은 레벨별 3개
        EnchantConfig old = parse("{\"prospect\":{\"durationSeconds\":20,\"cooldownSeconds\":[90,80,0]}}");
        assertEquals(new EnchantConfig.Tiered(20, 20, 20), old.prospectDurationSeconds());
        assertEquals(new EnchantConfig.Tiered(90, 80, 0), old.prospectCooldownSeconds());
        assertEquals(old, EnchantConfig.CODEC.parse(JsonOps.INSTANCE,
                EnchantConfig.CODEC.encodeStart(JsonOps.INSTANCE, old).getOrThrow()).getOrThrow());
    }
}
