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

    @Test
    void rowsForCountOnPage() {
        assertEquals(1, PetDexPaging.rowsFor(0, 5, 4));
        assertEquals(1, PetDexPaging.rowsFor(1, 5, 4));
        assertEquals(1, PetDexPaging.rowsFor(5, 5, 4));
        assertEquals(2, PetDexPaging.rowsFor(6, 5, 4));
        assertEquals(4, PetDexPaging.rowsFor(20, 5, 4));
        assertEquals(4, PetDexPaging.rowsFor(25, 5, 4));
    }

    @Test
    void countOnPage() {
        assertEquals(0, PetDexPaging.countOnPage(0, 0, 20));
        assertEquals(20, PetDexPaging.countOnPage(23, 0, 20));
        assertEquals(3, PetDexPaging.countOnPage(23, 1, 20));
    }

    @Test
    void rowsThatFitScreenHeight() {
        // cell 48, gap 4: n rows need 52n - 4 pixels
        assertEquals(4, PetDexPaging.rowsThatFit(500, 48, 4, 4));
        assertEquals(4, PetDexPaging.rowsThatFit(204, 48, 4, 4));
        assertEquals(3, PetDexPaging.rowsThatFit(203, 48, 4, 4));
        assertEquals(1, PetDexPaging.rowsThatFit(10, 48, 4, 4));
        assertEquals(1, PetDexPaging.rowsThatFit(-50, 48, 4, 4));
    }
}
