package org.gettserver.gettcore.enchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import net.minecraft.util.RandomSource;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;
import org.gettserver.gettcore.enchant.SpecialEnchants.Candidate;
import org.gettserver.gettcore.enchant.SpecialEnchants.LevelWeights;
import org.gettserver.gettcore.enchant.SpecialEnchants.Pick;
import org.gettserver.gettcore.enchant.SpecialEnchants.Rolled;
import org.gettserver.gettcore.enchant.SpecialEnchants.Tool;
import org.junit.jupiter.api.Test;

/** 뽑기 목록 설정(pool): 기본 묶음, 검증, 가중치 뽑기, 묶음 인식. */
class PoolConfigTest {

    private static EnchantConfig parse(String json) {
        return EnchantConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    private static int total(Tool tool) {
        return EnchantService.candidates(EnchantConfig.DEFAULT, tool).stream().mapToInt(Candidate::weight).sum();
    }

    @Test
    void defaultBundlesAndWeightSums() {
        PoolConfig d = PoolConfig.DEFAULT;
        assertEquals(4, d.bundles(Tool.HOE).size());
        assertEquals(4, d.bundles(Tool.AXE).size());
        assertEquals(4, d.bundles(Tool.FISHING_ROD).size());
        assertEquals(4, d.bundles(Tool.BOW).size());
        assertEquals(5, d.bundles(Tool.PICKAXE).size());
        assertEquals(5, d.bundles(Tool.SWORD).size());
        assertTrue(d.bundles(Tool.HELMET).isEmpty());
        assertEquals(40, total(Tool.HOE));
        assertEquals(40, total(Tool.AXE));
        assertEquals(40, total(Tool.FISHING_ROD));
        assertEquals(40, total(Tool.BOW));
        assertEquals(52, total(Tool.PICKAXE));
        assertEquals(63, total(Tool.SWORD));
        assertEquals(40, total(Tool.BOOTS)); // 방어구는 동률 10씩, 묶음 없음
        assertFalse(d.levelReroll().enabled());
        assertEquals(1000, d.levelReroll().goldCost());
        assertEquals(1, d.levelReroll().stoneCost());
    }

    @Test
    void defaultsSurviveSanitizeUnchanged() {
        List<String> errors = new ArrayList<>();
        assertEquals(PoolConfig.DEFAULT, PoolConfig.DEFAULT.sanitized(errors));
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void missingToolKeyMeansDefaultsAndEmptyListMeansNone() {
        List<String> errors = new ArrayList<>();
        PoolConfig p = parse("{\"pool\":{\"bundles\":{\"hoe\":[]}}}").pool().sanitized(errors);
        assertTrue(p.bundles(Tool.HOE).isEmpty());
        assertEquals(PoolConfig.DEFAULT.bundles(Tool.AXE), p.bundles(Tool.AXE));
        assertEquals(PoolConfig.SINGLE_WEIGHT, p.weight(Tool.HOE, Ability.HARVEST));
        assertTrue(errors.isEmpty(), errors.toString());
        // 파일에 pool 자체가 없어도 기본 목록
        assertEquals(PoolConfig.DEFAULT, parse("{}").pool().sanitized(errors));
    }

    @Test
    void invalidBundlesAreDropped() {
        List<String> errors = new ArrayList<>();
        PoolConfig p = parse("{\"pool\":{\"weights\":{\"hoe\":{\"harvest\":-5,\"bounty\":0}},\"bundles\":{\"hoe\":["
                + "{\"id\":\"ok\",\"abilities\":[\"harvest\",\"bounty\"],\"weight\":7,\"name\":\" 풍년 \"},"
                + "{\"id\":\"unknown\",\"abilities\":[\"harvest\",\"nope\"]},"
                + "{\"id\":\"wrong_tool\",\"abilities\":[\"harvest\",\"vein\"]},"
                + "{\"id\":\"single\",\"abilities\":[\"harvest\"]},"
                + "{\"id\":\"twice\",\"abilities\":[\"harvest\",\"harvest\"]},"
                + "{\"id\":\"ok\",\"abilities\":[\"harvest\",\"replant\"]},"
                + "{\"id\":\"same_set\",\"abilities\":[\"bounty\",\"harvest\"]},"
                + "{\"id\":\"negative\",\"abilities\":[\"replant\",\"bounty\"],\"weight\":-1}"
                + "],\"helmet\":[]},\"levelReroll\":{\"enabled\":true,\"goldCost\":-1}}}").pool().sanitized(errors);
        List<PoolConfig.Bundle> hoe = p.bundles(Tool.HOE);
        assertEquals(2, hoe.size(), hoe.toString());
        assertEquals("ok", hoe.get(0).id());
        assertEquals("풍년", hoe.get(0).name());
        assertEquals(7, hoe.get(0).weight());
        assertEquals(0, hoe.get(1).weight());
        assertEquals(PoolConfig.SINGLE_WEIGHT, p.weight(Tool.HOE, Ability.HARVEST));
        assertEquals(0, p.weight(Tool.HOE, Ability.BOUNTY));
        assertTrue(p.levelReroll().enabled());
        assertEquals(1000, p.levelReroll().goldCost());
        // 음수 가중치 1 + 버린 묶음 6 + 음수 묶음 가중치 1 + 방어구 키 1 + 비용 1
        assertEquals(10, errors.size(), errors.toString());
    }

    @Test
    void codecRoundTrip() {
        EnchantConfig d = EnchantConfig.DEFAULT;
        var json = EnchantConfig.CODEC.encodeStart(JsonOps.INSTANCE, d).getOrThrow();
        assertEquals(d, EnchantConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
    }

    @Test
    void pickFollowsWeightsAndAppliesOneLevelToTheBundle() {
        RandomSource r = RandomSource.create(5);
        List<Candidate> hoe = EnchantService.candidates(EnchantConfig.DEFAULT, Tool.HOE);
        int n = 40000;
        int master = 0;
        int singles = 0;
        for (int i = 0; i < n; i++) {
            Pick pick = SpecialEnchants.pick(hoe, LevelWeights.DEFAULT, r);
            List<Rolled> parts = pick.parts();
            if (pick.candidate().bundle() == null) {
                singles++;
                assertEquals(1, parts.size());
                continue;
            }
            int level = SpecialEnchants.bundleLevel(parts);
            for (Rolled part : parts) {
                assertEquals(part.ability().maxLevel() < 3 ? 1 : level, part.level(), parts.toString());
            }
            if (pick.candidate().bundle().id().equals("master_farmer")) {
                master++;
                assertEquals(3, parts.size());
            }
        }
        assertEquals(0.025, master / (double) n, 0.005);
        assertEquals(0.75, singles / (double) n, 0.01);
    }

    @Test
    void unleveledBundleHasNoLevel() {
        RandomSource r = RandomSource.create(9);
        PoolConfig.Bundle areaSmelt = PoolConfig.DEFAULT.bundles(Tool.PICKAXE).stream()
                .filter(b -> b.id().equals("area_smelt")).findFirst().orElseThrow();
        Candidate only = new Candidate(areaSmelt.abilityList(), 1, areaSmelt);
        for (int i = 0; i < 50; i++) {
            List<Rolled> parts = SpecialEnchants.pick(List.of(only), LevelWeights.DEFAULT, r).parts();
            assertEquals(List.of(new Rolled(Ability.AREA, 1), new Rolled(Ability.SMELT, 1)), parts);
            assertEquals(0, SpecialEnchants.bundleLevel(parts));
        }
    }

    @Test
    void zeroWeightSumPicksUniformlyAmongSinglesAndBundles() {
        RandomSource r = RandomSource.create(2);
        List<Candidate> all = SpecialEnchants.candidates(Tool.HOE, a -> 0,
                List.of(new PoolConfig.Bundle("b", List.of("harvest", "replant"), 0, "")));
        Map<Boolean, Integer> seen = new java.util.HashMap<>();
        for (int i = 0; i < 400; i++) {
            seen.merge(SpecialEnchants.pick(all, LevelWeights.DEFAULT, r).candidate().bundle() != null, 1,
                    Integer::sum);
        }
        assertEquals(0.25, seen.get(true) / 400.0, 0.08);
    }

    @Test
    void bundleIsRecognisedOnlyByTheExactAbilitySet() {
        List<PoolConfig.Bundle> hoe = PoolConfig.DEFAULT.bundles(Tool.HOE);
        assertEquals("master_farmer", SpecialEnchants.bundleOf(
                EnumSet.of(Ability.HARVEST, Ability.REPLANT, Ability.BOUNTY), hoe).orElseThrow().id());
        assertEquals("harvest_bounty", SpecialEnchants.bundleOf(
                EnumSet.of(Ability.BOUNTY, Ability.HARVEST), hoe).orElseThrow().id());
        assertTrue(SpecialEnchants.bundleOf(EnumSet.of(Ability.HARVEST), hoe).isEmpty());
        assertTrue(SpecialEnchants.bundleOf(EnumSet.of(Ability.HARVEST, Ability.VEIN), hoe).isEmpty());
        // 설정에서 묶음을 지우면 이름을 못 찾는다(능력은 도구에 그대로 남는다)
        assertTrue(SpecialEnchants.bundleOf(EnumSet.of(Ability.HARVEST, Ability.BOUNTY), List.of()).isEmpty());
    }

    @Test
    void withLevelKeepsAbilitiesAndOneLevelAbilities() {
        List<Rolled> parts = List.of(new Rolled(Ability.HARVEST, 1), new Rolled(Ability.REPLANT, 1),
                new Rolled(Ability.BOUNTY, 1));
        assertEquals(List.of(new Rolled(Ability.HARVEST, 3), new Rolled(Ability.REPLANT, 1),
                new Rolled(Ability.BOUNTY, 3)), SpecialEnchants.withLevel(parts, 3));
        Map<Ability, Integer> none = new EnumMap<>(Ability.class);
        assertTrue(none.isEmpty());
    }

    @Test
    void rerollGate() {
        assertFalse(EnchantService.canReroll(false, true, 5, 1, 9999, 1000)); // 꺼져 있으면 항상 불가
        assertFalse(EnchantService.canReroll(true, false, 5, 1, 9999, 1000)); // 3레벨 능력이 없으면 불가
        assertFalse(EnchantService.canReroll(true, true, 0, 1, 9999, 1000));
        assertFalse(EnchantService.canReroll(true, true, 1, 1, 999, 1000));
        assertTrue(EnchantService.canReroll(true, true, 1, 1, 1000, 1000));
        assertTrue(EnchantService.canReroll(true, true, 0, 0, 0, 0));
    }

    @Test
    void tooltipLineLookupNeedsTheWholeLine() {
        List<String> lines = List.of("다이아몬드 도끼", "벌목 재심기", "벌목 II", "목재 보너스 II", "벌목");
        assertEquals(2, AbilityTooltip.findLine(lines, "벌목", "II"));
        assertEquals(1, AbilityTooltip.findLine(lines, "벌목 재심기", "I"));
        assertEquals(3, AbilityTooltip.findLine(lines, "목재 보너스", "II"));
        assertEquals(-1, AbilityTooltip.findLine(lines, "광맥", "I"));
        // 아이템 이름 줄(0번)은 보지 않는다
        assertEquals(-1, AbilityTooltip.findLine(List.of("벌목"), "벌목", "I"));
    }
}
