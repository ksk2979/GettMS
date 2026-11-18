package org.gettserver.gettcore.craft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.craft.CraftConfig.Ingredient;
import org.gettserver.gettcore.craft.CraftConfig.Recipe;
import org.gettserver.gettcore.preset.ItemPresets;

/**
 * A recipe against the 36 main inventory slots, the same way on the server (which takes the items) and on the
 * client (which only shows what is missing). A preset ingredient matches stacks with exactly its components; a plain
 * id matches any stack of that item; {@code minReinforce} asks for at least that reinforce level. Stacks with a
 * lower reinforce level are spent first, so a "+10" is not used up where a plain one would do.
 */
public final class CraftInventory {
    /** What a recipe needs from an inventory: the slots looked at and, when it can be made, what to take. */
    public record Plan(List<Integer> slots, int[] have, int[][] take) {
        public boolean craftable() {
            return take != null;
        }
    }

    private CraftInventory() {}

    public static boolean matches(ItemStack stack, Ingredient ingredient) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = ResourceLocation.tryParse(ingredient.item());
        if (id == null) return false;
        if (!ItemPresets.matches(stack, id, (s, i) -> BuiltInRegistries.ITEM.getKey(s.getItem()).equals(i))) return false;
        return ingredient.minReinforce() <= 0 || level(stack) >= ingredient.minReinforce();
    }

    private static int level(ItemStack stack) {
        return stack.getOrDefault(GettCore.REINFORCE_LEVEL.get(), 0);
    }

    public static Plan plan(Recipe recipe, NonNullList<ItemStack> items) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) if (!items.get(i).isEmpty()) slots.add(i);
        slots.sort(Comparator.comparingInt((Integer i) -> level(items.get(i))).thenComparingInt(i -> i));
        int[] counts = new int[slots.size()];
        for (int s = 0; s < counts.length; s++) counts[s] = items.get(slots.get(s)).getCount();
        List<Ingredient> ings = recipe.ingredients();
        boolean[][] accepts = new boolean[ings.size()][counts.length];
        int[] need = new int[ings.size()];
        int[] have = new int[ings.size()];
        for (int g = 0; g < ings.size(); g++) {
            need[g] = ings.get(g).count();
            for (int s = 0; s < counts.length; s++) accepts[g][s] = matches(items.get(slots.get(s)), ings.get(g));
            have[g] = CraftRules.available(counts, accepts[g]);
        }
        return new Plan(slots, have, CraftRules.allocate(counts, accepts, need));
    }

    /** Takes what a craftable plan says out of the inventory. */
    public static void consume(Plan plan, NonNullList<ItemStack> items) {
        for (int[] row : plan.take()) {
            for (int s = 0; s < row.length; s++) {
                if (row[s] > 0) items.get(plan.slots().get(s)).shrink(row[s]);
            }
        }
    }
}
