package org.gettserver.gettcore.skill.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ShoutFadeTest {
    @Test void fullyVisibleFirst15Ticks() {
        assertEquals(1.0f, ShoutFade.alpha(0, 0), 1e-6);
        assertEquals(1.0f, ShoutFade.alpha(14, 0.99f), 1e-6);
        assertEquals(1.0f, ShoutFade.alpha(15, 0), 1e-6);
    }

    @Test void fadesOverLast5() {
        assertEquals(0.5f, ShoutFade.alpha(17, 0.5f), 1e-6);
        assertEquals(0.2f, ShoutFade.alpha(19, 0), 1e-6);
    }

    @Test void goneAt20() {
        assertEquals(0.0f, ShoutFade.alpha(ShoutFade.LIFE_TICKS, 0), 1e-6);
        assertEquals(0.0f, ShoutFade.alpha(25, 0.5f), 1e-6);
    }
}
