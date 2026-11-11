package org.gettserver.gettcore.upgrade;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.gettserver.gettcore.GettCore;

/**
 * Traveler's Backpack without a compile dependency. All of its backpacks keep one item id per look and carry the
 * tier (0 leather … 4 netherite) in the {@code travelersbackpack:tier} component, so a tier step cannot be an
 * item-to-item upgrade. Raising a tier goes through the mod's own {@code BackpackUpgradeRecipe.upgradeInventory}
 * (tier, slot counts, tank capacity from its config), exactly what its smithing recipe does. Without the mod nothing
 * here is a backpack.
 */
public final class BackpackBridge {
    static final String[] TIER_NAMES = {"leather", "iron", "gold", "diamond", "netherite"};
    public static final int MAX_TIER = TIER_NAMES.length - 1;

    private static final ResourceLocation TIER_ID = ResourceLocation.fromNamespaceAndPath("travelersbackpack", "tier");
    private static final String ITEM_CLASS = "com.tiviacz.travelersbackpack.items.TravelersBackpackItem";
    private static final String RECIPE_CLASS = "com.tiviacz.travelersbackpack.common.recipes.BackpackUpgradeRecipe";
    private static final String TIERS_CLASS = "com.tiviacz.travelersbackpack.inventory.Tiers";

    private static boolean resolved;
    private static Class<?> itemClass;
    private static Object recipe;
    private static Method upgradeInventory;
    private static Method tierOf;

    private BackpackBridge() {}

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> item = Class.forName(ITEM_CLASS);
            Class<?> recipeClass = Class.forName(RECIPE_CLASS);
            Class<?> tiers = Class.forName(TIERS_CLASS);
            Method of = tiers.getMethod("of", int.class);
            Method upgrade = recipeClass.getMethod("upgradeInventory", ItemStack.class, of.getReturnType());
            Constructor<?> ctor = recipeClass.getConstructor(Ingredient.class, Ingredient.class, Ingredient.class,
                    ItemStack.class);
            recipe = ctor.newInstance(Ingredient.EMPTY, Ingredient.EMPTY, Ingredient.EMPTY, ItemStack.EMPTY);
            tierOf = of;
            upgradeInventory = upgrade;
            itemClass = item;
        } catch (ClassNotFoundException e) {
            // Traveler's Backpack is not installed.
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            GettCore.LOGGER.warn("gettcore: Traveler's Backpack found but its tier upgrade is not reachable", e);
        }
    }

    public static boolean isBackpack(ItemStack stack) {
        if (stack.isEmpty()) return false;
        resolve();
        return itemClass != null && itemClass.isInstance(stack.getItem());
    }

    /** Current tier, 0 (leather) when the stack does not say. */
    public static int tier(ItemStack stack) {
        DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(TIER_ID);
        if (type == null) return 0;
        return stack.get(type) instanceof Integer i ? Math.max(0, Math.min(MAX_TIER, i)) : 0;
    }

    /** A copy of {@code backpack} one tier up, contents and upgrades kept; EMPTY when that is not possible. */
    public static ItemStack upgraded(ItemStack backpack) {
        if (!isBackpack(backpack)) return ItemStack.EMPTY;
        int tier = tier(backpack);
        if (tier >= MAX_TIER) return ItemStack.EMPTY;
        try {
            ItemStack out = backpack.copy();
            upgradeInventory.invoke(recipe, out, tierOf.invoke(null, tier + 1));
            return tier(out) == tier + 1 ? out : ItemStack.EMPTY;
        } catch (ReflectiveOperationException | RuntimeException e) {
            GettCore.LOGGER.error("gettcore: backpack tier upgrade failed", e);
            return ItemStack.EMPTY;
        }
    }

    /** Display name of a tier ("철", "금", …). */
    public static Component tierName(int tier) {
        return Component.translatable("gettcore.backpack_tier." + TIER_NAMES[Math.max(0, Math.min(MAX_TIER, tier))]);
    }
}
