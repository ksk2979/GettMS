package org.gettserver.gettcore.craft;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.gettserver.gettcore.craft.CraftConfig.Ingredient;
import org.gettserver.gettcore.craft.CraftConfig.Recipe;
import org.junit.jupiter.api.Test;

/** 제작: 재료를 어느 칸에서 얼마나 쓸지, 제작법 검사. */
class CraftRulesTest {
    @Test
    void takesFromSeveralStacksInTheOrderGiven() {
        int[][] take = CraftRules.allocate(new int[] {3, 10, 5}, new boolean[][] {{true, false, true}}, new int[] {6});
        assertArrayEquals(new int[] {3, 0, 3}, take[0]);
    }

    @Test
    void failsWhenThereIsNotEnough() {
        assertNull(CraftRules.allocate(new int[] {3, 2}, new boolean[][] {{true, true}}, new int[] {6}));
        assertNull(CraftRules.allocate(new int[] {64}, new boolean[][] {{false}}, new int[] {1}));
    }

    @Test
    void thePickierIngredientChoosesFirstAndItemsAreNotUsedTwice() {
        // stack 0: a plain musket, stack 1: a musket +5. Ingredient 0 takes any musket, ingredient 1 only the +5 one.
        boolean[][] accepts = {{true, true}, {false, true}};
        int[][] take = CraftRules.allocate(new int[] {1, 1}, accepts, new int[] {1, 1});
        assertArrayEquals(new int[] {1, 0}, take[0]);
        assertArrayEquals(new int[] {0, 1}, take[1]);
        // only the +5 one in the inventory: it cannot be both
        assertNull(CraftRules.allocate(new int[] {0, 1}, accepts, new int[] {1, 1}));
    }

    @Test
    void availableCountsEveryAcceptingStack() {
        assertEquals(8, CraftRules.available(new int[] {3, 10, 5}, new boolean[] {true, false, true}));
    }

    static Recipe recipe(String id, String result, Ingredient... ings) {
        return new Recipe(id, "", result, 1, 0, List.of(ings));
    }

    @Test
    void sanitizeDropsBrokenRecipesAndKeepsTheRest() {
        Ingredient iron = new Ingredient("minecraft:iron_ingot", 16, 0);
        CraftConfig c = new CraftConfig(List.of(
                recipe("gun", "preset:hunting_rifle", iron, new Ingredient("preset:musket", 1, 5)),
                recipe("gun", "minecraft:stick", iron),
                recipe("", "minecraft:stick", iron),
                recipe("no_ingredients", "minecraft:stick"),
                recipe("bad_result", "not an id", iron),
                recipe("bad_count", "minecraft:stick", new Ingredient("minecraft:stick", 0, 0)),
                recipe("bad_level", "minecraft:stick", new Ingredient("minecraft:stick", 1, 100)),
                new Recipe("negative_gold", "", "minecraft:stick", 1, -1, List.of(iron)),
                new Recipe("ok2", "총", "minecraft:stick", 4, 100, List.of(iron))));
        assertEquals(7, c.errors().size());
        List<String> warnings = new ArrayList<>();
        CraftConfig clean = c.sanitized(warnings);
        assertEquals(List.of("gun", "ok2"), clean.recipes().stream().map(Recipe::id).toList());
        assertEquals(7, warnings.size());
        assertTrue(warnings.get(0).startsWith("crafting.json: recipes[1] "));
        assertEquals(List.of("", "총"), clean.categories());
        assertTrue(clean.recipe("ok2").isPresent());
    }

    @Test
    void jsonRoundTripAndDefaults() {
        CraftConfig c = CraftConfig.fromJsonString(
                "{\"recipes\":[{\"id\":\"a\",\"result\":\"minecraft:stick\",\"ingredients\":[{\"item\":\"minecraft:oak_log\"}]}]}");
        Recipe r = c.recipes().get(0);
        assertEquals(1, r.count());
        assertEquals(0, r.gold());
        assertEquals("", r.category());
        assertEquals(new Ingredient("minecraft:oak_log", 1, 0), r.ingredients().get(0));
        assertEquals(c, CraftConfig.fromJsonString(CraftConfig.toJsonString(c)));
        assertEquals(CraftConfig.DEFAULT, CraftConfig.fromJsonString("{}"));
    }
}
