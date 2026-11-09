package org.gettserver.gettcore.balance;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.neoforge.common.ItemAbilities;
import org.gettserver.gettcore.reinforce.ReinforceTargets;

/** Registry side of {@link RecipeBalance#classify}: what a result item is. Server thread (registries loaded). */
public final class RecipeItems {
    private RecipeItems() {}

    /** Equipment kind of an item id, or null (unknown item, not equipment). Vanilla items are filtered by the caller. */
    public static String kindOf(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) return null;
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) return null;
        try {
            ItemStack stack = new ItemStack(item);
            if (stack.isEmpty()) return null;
            ItemAttributeModifiers mods = stack.getAttributeModifiers();
            boolean damage = mods.modifiers().stream().anyMatch(e -> e.attribute().is(Attributes.ATTACK_DAMAGE));
            boolean armor = mods.modifiers().stream().anyMatch(e -> e.attribute().is(Attributes.ARMOR));
            return RecipeBalance.classify(new RecipeBalance.Facts(
                    ReinforceTargets.ntglWeapon.test(stack),
                    item instanceof ShieldItem,
                    stack.canPerformAction(ItemAbilities.SHIELD_BLOCK),
                    item instanceof ArmorItem,
                    item instanceof ProjectileWeaponItem || item instanceof TridentItem,
                    damage,
                    armor));
        } catch (RuntimeException e) {
            return null;   // a mod item that cannot be inspected outside a world: not listed
        }
    }
}
