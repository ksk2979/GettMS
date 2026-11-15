package org.gettserver.gettcore.farm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 작물 씨앗 드롭 1개 제한(순수 계산). */
class SeedDropsTest {
    @Test
    void seedStackBecomesOneAndCropStaysAsIs() {
        // wheat x1 + seeds x3 (fortune) -> wheat x1 + seeds x1
        assertArrayEquals(new int[]{1, 1}, SeedDrops.capped(Arrays.asList(null, "wheat_seeds"), new int[]{1, 3}));
        // carrots are not seeds: untouched
        assertArrayEquals(new int[]{5}, SeedDrops.capped(Arrays.asList((String) null), new int[]{5}));
    }

    @Test
    void laterStacksOfTheSameSeedAreRemoved() {
        assertArrayEquals(new int[]{1, 2, 0, 0},
                SeedDrops.capped(Arrays.asList("s", null, "s", "s"), new int[]{1, 2, 1, 2}));
    }

    @Test
    void eachSeedKindKeepsOne() {
        assertArrayEquals(new int[]{1, 1}, SeedDrops.capped(List.of("a", "b"), new int[]{4, 2}));
    }

    @Test
    void singleSeedAndNoDropsAreUnchanged() {
        assertArrayEquals(new int[]{1}, SeedDrops.capped(List.of("s"), new int[]{1}));
        assertArrayEquals(new int[]{}, SeedDrops.capped(List.<String>of(), new int[]{}));
    }
}
