package org.gettserver.gettcore.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Unbreakable;
import org.junit.jupiter.api.Test;

/** {@link UpgradeService#upgraded}: data carried over, item-specific components from the new item. */
class UpgradedStackTest {

    @Test
    void carriesPatchAndKeepsWearRatio() {
        ItemStack old = new ItemStack(Items.IRON_SWORD);
        old.set(DataComponents.CUSTOM_NAME, Component.literal("엑스칼리버"));
        old.set(DataComponents.REPAIR_COST, 3);
        old.setDamageValue(125); // iron sword max 250 -> half worn
        ItemStack out = UpgradeService.upgraded(old, Items.DIAMOND_SWORD);
        assertTrue(out.is(Items.DIAMOND_SWORD));
        assertEquals("엑스칼리버", out.get(DataComponents.CUSTOM_NAME).getString());
        assertEquals(3, out.get(DataComponents.REPAIR_COST));
        assertEquals(1561, out.getMaxDamage());
        assertEquals(781, out.getDamageValue());
        // the new item's own attribute defaults apply
        assertEquals(Items.DIAMOND_SWORD.components().get(DataComponents.ATTRIBUTE_MODIFIERS),
                out.get(DataComponents.ATTRIBUTE_MODIFIERS));
    }

    @Test
    void dropsItemSpecificOverridesAndKeepsUnbreakable() {
        ItemStack old = new ItemStack(Items.IRON_SWORD);
        old.set(DataComponents.UNBREAKABLE, new Unbreakable(false));
        old.set(DataComponents.MAX_DAMAGE, 9999);
        old.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        ItemStack out = UpgradeService.upgraded(old, Items.DIAMOND_SWORD);
        assertEquals(new Unbreakable(false), out.get(DataComponents.UNBREAKABLE));
        assertEquals(1561, out.getMaxDamage());
        assertEquals(0, out.getDamageValue());
        assertTrue(out.getComponentsPatch().isEmpty() || out.getComponentsPatch().entrySet().stream()
                .noneMatch(e -> e.getKey() == DataComponents.ATTRIBUTE_MODIFIERS || e.getKey() == DataComponents.MAX_DAMAGE));
    }

    @Test
    void materialCountUsesThePlainItem() {
        UpgradeConfig.Step step = new UpgradeConfig.Step("minecraft:iron_sword", "minecraft:diamond_sword",
                "minecraft:diamond", 3, 0);
        List<ItemStack> inv = List.of(new ItemStack(Items.DIAMOND, 2), new ItemStack(Items.CARROT, 5),
                new ItemStack(Items.DIAMOND, 1), ItemStack.EMPTY);
        assertEquals(3, UpgradeService.held(inv, Optional.of(step)));
        assertEquals(0, UpgradeService.held(inv, Optional.empty()));
        UpgradeConfig config = new UpgradeConfig(List.of(step,
                new UpgradeConfig.Step("minecraft:diamond_sword", "nope:missing", "minecraft:diamond", 1, 0)));
        assertTrue(UpgradeService.nextStep(new ItemStack(Items.IRON_SWORD), config).isPresent());
        assertTrue(UpgradeService.nextStep(new ItemStack(Items.DIAMOND_SWORD), config).isEmpty(),
                "result not installed -> no step");
        assertTrue(UpgradeService.nextStep(ItemStack.EMPTY, config).isEmpty());
    }
}
