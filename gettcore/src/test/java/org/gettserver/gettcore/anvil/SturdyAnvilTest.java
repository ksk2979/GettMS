package org.gettserver.gettcore.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SturdyAnvilTest {
    @Test
    void sturdyAnvilNeverBreaks() {
        assertEquals(0f, SturdyAnvil.breakChance(true, 0.12f));
    }

    @Test
    void normalAnvilKeepsVanillaChance() {
        assertEquals(0.12f, SturdyAnvil.breakChance(false, 0.12f));
    }
}
