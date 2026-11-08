package org.gettserver.gettcore.pet.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PetDexScreenTest {
    @Test
    void pageCountZeroIsOne() {
        assertEquals(1, PetDexPaging.pageCount(0, 20));
    }

    @Test
    void pageCountTwentyOneIsTwo() {
        assertEquals(2, PetDexPaging.pageCount(21, 20));
    }
}
