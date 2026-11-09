package org.gettserver.gettcore.economy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Pure counting/taking plan of {@link StoneCounter} (ItemStack needs registries, so the int[] form is tested). */
class StoneCounterTest {

    @Test
    void countsAll() {
        assertEquals(55, StoneCounter.total(new int[]{10, 0, 40, 5}));
        assertEquals(0, StoneCounter.total(new int[]{}));
    }

    @Test
    void consumesAcrossStacks() {
        // 앞 칸부터: 10 + 0 + 35 → 남는 건 3번째 칸 5개, 4번째 칸 5개.
        assertArrayEquals(new int[]{10, 0, 35, 0}, StoneCounter.takePlan(new int[]{10, 0, 40, 5}, 45));
    }

    @Test
    void insufficientTakesNothing() {
        assertNull(StoneCounter.takePlan(new int[]{10, 0, 40, 5}, 60));
    }

    @Test
    void zeroIsNoop() {
        assertArrayEquals(new int[]{0, 0}, StoneCounter.takePlan(new int[]{3, 4}, 0));
    }
}
