package org.gettserver.gettcore.equipment;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import org.gettserver.gettcore.equipment.EquipmentConfig.Values;
import org.gettserver.gettcore.equipment.EquipmentRules.Mod;
import org.gettserver.gettcore.equipment.EquipmentRules.Stat;
import org.junit.jupiter.api.Test;

/** equipment.json 설정과 수정자 덮어쓰기 순수 규칙. */
class EquipmentTest {
    private static final double EPS = 1e-9;
    private static final ResourceLocation BASE_DMG = ResourceLocation.withDefaultNamespace("base_attack_damage");
    private static final ResourceLocation BASE_SPD = ResourceLocation.withDefaultNamespace("base_attack_speed");
    private static final Mod REINFORCE = new Mod(Stat.ATTACK_DAMAGE,
            ResourceLocation.parse("gettcore:reinforce_melee"), 0.1, Operation.ADD_MULTIPLIED_TOTAL,
            EquipmentSlotGroup.MAINHAND);

    private static EquipmentConfig parse(String json) {
        return EquipmentConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    private static Values v(Double dmg, Double spd, Double armor, Double tough, Double kb) {
        return new Values(Optional.ofNullable(dmg), Optional.ofNullable(spd), Optional.ofNullable(armor),
                Optional.ofNullable(tough), Optional.ofNullable(kb));
    }

    private static Mod add(Stat s, String id, double amount, EquipmentSlotGroup g) {
        return new Mod(s, ResourceLocation.parse(id), amount, Operation.ADD_VALUE, g);
    }

    /** 다이아 검: 공격력 +6(툴팁 7), 공격 속도 −2.4(툴팁 1.6). */
    private static List<Mod> diamondSword() {
        return List.of(new Mod(Stat.ATTACK_DAMAGE, BASE_DMG, 6, Operation.ADD_VALUE, EquipmentSlotGroup.MAINHAND),
                new Mod(Stat.ATTACK_SPEED, BASE_SPD, -2.4, Operation.ADD_VALUE, EquipmentSlotGroup.MAINHAND));
    }

    private static EquipmentSlotGroup mainhand(Stat s) {
        return EquipmentSlotGroup.MAINHAND;
    }

    @Test
    void parsesDefaultsFile() throws Exception {
        try (InputStream in = EquipmentTest.class.getResourceAsStream("/gettcore_defaults/equipment.json")) {
            assertNotNull(in, "bundled equipment.json");
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            EquipmentConfig parsed = EquipmentConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            assertEquals(EquipmentConfig.DEFAULT, parsed);
            List<String> warnings = new ArrayList<>();
            assertEquals(EquipmentConfig.DEFAULT, parsed.sanitized(warnings));
            assertTrue(warnings.isEmpty(), warnings.toString());
        }
    }

    @Test
    void missingValuesStayAbsentAndRoundTrip() {
        EquipmentConfig c = parse("{\"items\":{\"minecraft:diamond_sword\":{\"attackDamage\":9,\"attackSpeed\":1.2}}}");
        Values s = c.items().get("minecraft:diamond_sword");
        assertEquals(Optional.of(9.0), s.attackDamage());
        assertEquals(Optional.of(1.2), s.attackSpeed());
        assertEquals(Optional.empty(), s.armor());
        assertEquals(Optional.empty(), s.toughness());
        assertEquals(Optional.empty(), s.knockbackResistance());
        var json = EquipmentConfig.CODEC.encodeStart(JsonOps.INSTANCE, c).getOrThrow();
        assertEquals("{\"items\":{\"minecraft:diamond_sword\":{\"attackDamage\":9.0,\"attackSpeed\":1.2}}}",
                json.toString());
        assertEquals(c, EquipmentConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        assertEquals(EquipmentConfig.DEFAULT, parse("{}"));
        // The sync payload carries the same values.
        assertEquals(c, EquipmentConfig.fromJsonString(EquipmentConfig.toJsonString(c)));
    }

    @Test
    void sanitizeClampsDropsBadKeysAndEmptyRows() {
        EquipmentConfig c = new EquipmentConfig(Map.of(
                "minecraft:diamond_sword", v(-3.0, Double.NaN, null, null, null),
                "minecraft:netherite_chestplate", v(null, null, 99.0, 50.0, 2.0),
                "Not An Id", v(5.0, null, null, null, null),
                "minecraft:stick", v(null, null, null, null, null)));
        List<String> warnings = new ArrayList<>();
        EquipmentConfig s = c.sanitized(warnings);
        assertEquals(2, s.items().size(), s.toString());
        Values sword = s.items().get("minecraft:diamond_sword");
        assertEquals(Optional.of(0.0), sword.attackDamage());
        assertEquals(Optional.of(0.0), sword.attackSpeed());
        Values chest = s.items().get("minecraft:netherite_chestplate");
        assertEquals(Optional.of(EquipmentConfig.MAX_ARMOR), chest.armor());
        assertEquals(Optional.of(EquipmentConfig.MAX_TOUGHNESS), chest.toughness());
        assertEquals(Optional.of(1.0), chest.knockbackResistance());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Not An Id")), warnings.toString());
        assertTrue(warnings.stream().allMatch(w -> w.startsWith("equipment.json: ")), warnings.toString());
        List<String> none = new ArrayList<>();
        assertEquals(s, s.sanitized(none));
        assertTrue(none.isEmpty(), none.toString());
        // The editor reports the same out-of-range values as errors (not silently clamped).
        List<String> errors = c.rangeErrors();
        assertTrue(errors.contains("minecraft:diamond_sword attackDamage -3.0 must be 0..2048"), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("minecraft:diamond_sword attackSpeed NaN")),
                errors.toString());
        assertTrue(errors.contains("minecraft:netherite_chestplate knockbackResistance 2.0 must be 0..1"),
                errors.toString());
        assertEquals(5, errors.size(), errors.toString());
    }

    @Test
    void tooltipValueToModifierAmount() {
        assertEquals(6.0, EquipmentRules.amount(Stat.ATTACK_DAMAGE, 7.0), EPS);
        assertEquals(-2.4, EquipmentRules.amount(Stat.ATTACK_SPEED, 1.6), EPS);
        assertEquals(8.0, EquipmentRules.amount(Stat.ARMOR, 8.0), EPS);
        assertEquals(3.0, EquipmentRules.amount(Stat.ARMOR_TOUGHNESS, 3.0), EPS);
        assertEquals(0.1, EquipmentRules.amount(Stat.KNOCKBACK_RESISTANCE, 0.1), EPS);
    }

    @Test
    void overrideReplacesVanillaModifierWithBaseIdSoTooltipShowsTheValue() {
        List<Mod> out = EquipmentRules.apply(diamondSword(), v(9.0, 1.2, null, null, null), EquipmentTest::mainhand);
        assertEquals(List.of(new Mod(Stat.ATTACK_DAMAGE, BASE_DMG, 8, Operation.ADD_VALUE, EquipmentSlotGroup.MAINHAND),
                new Mod(Stat.ATTACK_SPEED, BASE_SPD, 1.2 - 4, Operation.ADD_VALUE, EquipmentSlotGroup.MAINHAND)), out);
        assertEquals(9.0, EquipmentRules.finalValue(Stat.ATTACK_DAMAGE, out, EquipmentSlot.MAINHAND), EPS);
        assertEquals(1.2, EquipmentRules.finalValue(Stat.ATTACK_SPEED, out, EquipmentSlot.MAINHAND), EPS);
    }

    @Test
    void noOverrideLeavesModifiersUntouched() {
        assertEquals(diamondSword(), EquipmentRules.apply(diamondSword(), v(null, null, null, null, null),
                EquipmentTest::mainhand));
        assertEquals(diamondSword(), EquipmentRules.apply(diamondSword(), null, EquipmentTest::mainhand));
        // Only the given stat changes.
        List<Mod> out = EquipmentRules.apply(diamondSword(), v(null, 2.0, null, null, null), EquipmentTest::mainhand);
        assertEquals(diamondSword().get(0), out.get(0));
        assertEquals(2.0, EquipmentRules.finalValue(Stat.ATTACK_SPEED, out, EquipmentSlot.MAINHAND), EPS);
    }

    @Test
    void reinforceStacksOnTopRegardlessOfHandlerOrder() {
        Values o = v(10.0, null, null, null, null);
        List<Mod> overrideFirst = new ArrayList<>(EquipmentRules.apply(diamondSword(), o, EquipmentTest::mainhand));
        overrideFirst.add(REINFORCE);
        List<Mod> reinforceFirst = new ArrayList<>(diamondSword());
        reinforceFirst.add(REINFORCE);
        reinforceFirst = EquipmentRules.apply(reinforceFirst, o, EquipmentTest::mainhand);
        assertEquals(new HashSet<>(overrideFirst), new HashSet<>(reinforceFirst));
        assertTrue(reinforceFirst.contains(REINFORCE), "reinforce modifier kept");
        assertEquals(10.0 * 1.1, EquipmentRules.finalValue(Stat.ATTACK_DAMAGE, reinforceFirst,
                EquipmentSlot.MAINHAND), EPS);
        // Applying twice changes nothing.
        assertEquals(new HashSet<>(reinforceFirst), new HashSet<>(EquipmentRules.apply(reinforceFirst, o,
                EquipmentTest::mainhand)));
    }

    @Test
    void modItemWithOwnIdsAndExtraModifiersIsNotCountedTwice() {
        List<Mod> modded = List.of(
                add(Stat.ATTACK_DAMAGE, "epic_knights:weapon_damage", 5, EquipmentSlotGroup.MAINHAND),
                add(Stat.ATTACK_DAMAGE, "othermod:bonus", 1, EquipmentSlotGroup.ANY),
                add(Stat.ATTACK_SPEED, "epic_knights:weapon_speed", -2.8, EquipmentSlotGroup.MAINHAND));
        List<Mod> out = EquipmentRules.apply(modded, v(8.0, null, null, null, null), EquipmentTest::mainhand);
        assertEquals(2, out.size(), out.toString());
        assertEquals(8.0, EquipmentRules.finalValue(Stat.ATTACK_DAMAGE, out, EquipmentSlot.MAINHAND), EPS);
        assertEquals(1.2, EquipmentRules.finalValue(Stat.ATTACK_SPEED, out, EquipmentSlot.MAINHAND), EPS);
    }

    @Test
    void armorKeepsItsOwnSlotGroupWithPerGroupIds() {
        // A mod trinket that puts armor on the chest group although it is no chestplate.
        List<Mod> trinket = List.of(add(Stat.ARMOR, "trinkets:armor", 2, EquipmentSlotGroup.CHEST),
                add(Stat.KNOCKBACK_RESISTANCE, "trinkets:kb", 0.05, EquipmentSlotGroup.CHEST));
        List<Mod> out = EquipmentRules.apply(trinket, v(null, null, 6.0, 2.0, 0.2),
                s -> EquipmentSlotGroup.MAINHAND);
        for (Mod m : out) assertEquals(EquipmentSlotGroup.CHEST, m.group(), m.toString());
        assertEquals(new Mod(Stat.ARMOR, ResourceLocation.parse("gettcore:equip_armor_chest"), 6, Operation.ADD_VALUE,
                EquipmentSlotGroup.CHEST), out.get(0));
        // Toughness had no modifier of its own: the item's other stats show where it is worn, so the chest group
        // wins over the caller's fallback.
        assertEquals(2.0, EquipmentRules.finalValue(Stat.ARMOR_TOUGHNESS, out, EquipmentSlot.CHEST), EPS);
        assertEquals(0.2, EquipmentRules.finalValue(Stat.KNOCKBACK_RESISTANCE, out, EquipmentSlot.CHEST), EPS);
        assertEquals(0.0, EquipmentRules.finalValue(Stat.ARMOR, out, EquipmentSlot.MAINHAND), EPS);
        // Different pieces never share an id on the player.
        assertNotEquals(EquipmentRules.overrideId(Stat.ARMOR, EquipmentSlotGroup.HEAD),
                EquipmentRules.overrideId(Stat.ARMOR, EquipmentSlotGroup.CHEST));
        // Attack values outside the main hand cannot use the shared base id.
        assertEquals("gettcore:equip_attack_damage_offhand",
                EquipmentRules.overrideId(Stat.ATTACK_DAMAGE, EquipmentSlotGroup.OFFHAND).toString());
        assertEquals(BASE_DMG, EquipmentRules.overrideId(Stat.ATTACK_DAMAGE, EquipmentSlotGroup.MAINHAND));
    }

    @Test
    void statWithoutAnyModifierUsesFallbackGroup() {
        List<Mod> out = EquipmentRules.apply(List.of(), v(null, null, 5.0, null, null),
                s -> EquipmentSlotGroup.OFFHAND);
        assertEquals(List.of(new Mod(Stat.ARMOR, ResourceLocation.parse("gettcore:equip_armor_offhand"), 5,
                Operation.ADD_VALUE, EquipmentSlotGroup.OFFHAND)), out);
    }

    @Test
    void diffRemovesThenAddsOnlyWhatChanged() {
        List<Mod> before = new ArrayList<>(diamondSword());
        before.add(REINFORCE);
        List<Mod> after = EquipmentRules.apply(before, v(9.0, null, null, null, null), EquipmentTest::mainhand);
        EquipmentRules.Diff d = EquipmentRules.diff(before, after);
        assertEquals(List.of(diamondSword().get(0)), d.removed());
        assertEquals(List.of(new Mod(Stat.ATTACK_DAMAGE, BASE_DMG, 8, Operation.ADD_VALUE,
                EquipmentSlotGroup.MAINHAND)), d.added());
        EquipmentRules.Diff none = EquipmentRules.diff(before, before);
        assertTrue(none.removed().isEmpty() && none.added().isEmpty());
    }

    @Test
    void fallbackGroupByItemKind() {
        assertEquals(EquipmentSlotGroup.LEGS, EquipmentRules.fallbackGroup(EquipmentSlot.LEGS, false));
        assertEquals(EquipmentSlotGroup.OFFHAND, EquipmentRules.fallbackGroup(null, true));
        assertEquals(EquipmentSlotGroup.MAINHAND, EquipmentRules.fallbackGroup(null, false));
    }

    @Test
    void everyPossibleOverrideIdIsListedForRefresh() {
        List<ResourceLocation> ids = EquipmentRules.possibleOverrideIds(Stat.ATTACK_DAMAGE);
        assertTrue(ids.contains(BASE_DMG), ids.toString());
        for (EquipmentSlotGroup g : EquipmentSlotGroup.values()) {
            assertTrue(ids.contains(EquipmentRules.overrideId(Stat.ATTACK_DAMAGE, g)), g.toString());
        }
        assertTrue(EquipmentRules.possibleOverrideIds(Stat.ARMOR).stream().noneMatch(BASE_DMG::equals));
    }

    @Test
    void finalValueFollowsVanillaFormula() {
        List<Mod> mods = List.of(add(Stat.ARMOR, "a:a", 4, EquipmentSlotGroup.CHEST),
                new Mod(Stat.ARMOR, ResourceLocation.parse("a:b"), 0.5, Operation.ADD_MULTIPLIED_BASE,
                        EquipmentSlotGroup.CHEST),
                new Mod(Stat.ARMOR, ResourceLocation.parse("a:c"), 1.0, Operation.ADD_MULTIPLIED_TOTAL,
                        EquipmentSlotGroup.ARMOR));
        assertEquals((4 + 4 * 0.5) * 2, EquipmentRules.finalValue(Stat.ARMOR, mods, EquipmentSlot.CHEST), EPS);
    }
}
