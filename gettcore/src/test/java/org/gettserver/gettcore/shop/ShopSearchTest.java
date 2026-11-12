package org.gettserver.gettcore.shop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShopSearchTest {

    @Test
    void emptyQueryMatchesEverything() {
        assertTrue(ShopScreen.matchesQuery("안산암 합금", "andesite_alloy", ""));
    }

    @Test
    void matchesDisplayNameOrRegistryPath() {
        assertTrue(ShopScreen.matchesQuery("안산암 합금", "andesite_alloy", "합금"));
        assertTrue(ShopScreen.matchesQuery("Andesite Alloy", "andesite_alloy", "alloy"));
        assertTrue(ShopScreen.matchesQuery("안산암 합금", "andesite_alloy", "andesite"));
        assertFalse(ShopScreen.matchesQuery("안산암 합금", "andesite_alloy", "톱니"));
    }
}
