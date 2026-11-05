package org.gettserver.gettcore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SmokeTest {
    @Test
    void modIdIsGettcore() {
        assertEquals("gettcore", GettCore.MOD_ID);
    }
}
