package org.gettserver.gettcore.enchant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The enchant apply gate: supported tool, one stone and enough gold. */
class EnchantServiceTest {

    @Test
    void needsSupportedTool() {
        assertFalse(EnchantService.canApply(false, 5, 10_000, 1000));
    }

    @Test
    void needsAStone() {
        assertFalse(EnchantService.canApply(true, 0, 10_000, 1000));
    }

    @Test
    void needsEnoughGold() {
        assertFalse(EnchantService.canApply(true, 1, 999, 1000));
        assertTrue(EnchantService.canApply(true, 1, 1000, 1000));
    }

    @Test
    void freeWhenCostIsZero() {
        assertTrue(EnchantService.canApply(true, 1, 0, 0));
    }
}
