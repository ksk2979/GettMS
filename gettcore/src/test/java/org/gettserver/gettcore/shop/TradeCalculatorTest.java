package org.gettserver.gettcore.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

class TradeCalculatorTest {
    private static final ResourceLocation CARROT = ResourceLocation.withDefaultNamespace("carrot");

    @Test
    void matchesRejectsRenamed() {
        ItemStack s = new ItemStack(Items.CARROT);
        assertTrue(TradeCalculator.matches(s, CARROT));
        s.set(DataComponents.CUSTOM_NAME, Component.literal("x"));
        assertFalse(TradeCalculator.matches(s, CARROT));
    }

    @Test
    void matchesRejectsDamaged() {
        ItemStack s = new ItemStack(Items.IRON_PICKAXE);
        ResourceLocation id = ResourceLocation.withDefaultNamespace("iron_pickaxe");
        assertTrue(TradeCalculator.matches(s, id));
        s.setDamageValue(1);
        assertFalse(TradeCalculator.matches(s, id));
    }

    @Test
    void countSellableSumsStacks() {
        List<ItemStack> inv = List.of(new ItemStack(Items.CARROT, 64), new ItemStack(Items.DIAMOND, 3),
                new ItemStack(Items.CARROT, 10), ItemStack.EMPTY);
        assertEquals(74, TradeCalculator.countSellable(inv, CARROT));
    }

    @Test
    void maxBuyableLimitedByBalance() {
        assertEquals(2, TradeCalculator.maxBuyable(250, 100, 64, 10));
    }

    @Test
    void maxBuyableLimitedByCapacity() {
        assertEquals(3, TradeCalculator.maxBuyable(100000, 100, 3, 10));
    }

    @Test
    void maxBuyableZeroWhenBroke() {
        assertEquals(0, TradeCalculator.maxBuyable(0, 100, 64, 10));
    }

    @Test
    void capacityCountsPartialStacks() {
        List<ItemStack> inv = List.of(ItemStack.EMPTY, new ItemStack(Items.CARROT, 60), new ItemStack(Items.DIAMOND, 1));
        assertEquals(68, TradeCalculator.capacityFor(inv, Items.CARROT, 64));
    }

    @Test
    void totalSaturates() {
        assertEquals(500, TradeCalculator.total(100, 5));
        assertEquals(Long.MAX_VALUE, TradeCalculator.total(Long.MAX_VALUE / 2, 5));
    }
}
