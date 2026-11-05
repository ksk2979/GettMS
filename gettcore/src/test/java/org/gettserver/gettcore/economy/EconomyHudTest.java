package org.gettserver.gettcore.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EconomyHudTest {
    @Test
    void formatsThousands() {
        assertEquals("483,649", EconomyHud.formatGold(483649));
        assertEquals("0", EconomyHud.formatGold(0));
    }
}
