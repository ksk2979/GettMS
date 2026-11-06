package org.gettserver.gettcore.enchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import org.gettserver.gettcore.enchant.effects.EffectContext;
import org.junit.jupiter.api.Test;

class EffectContextTest {
    /** 0..2 범위 3×3×3 격자. */
    private static final Predicate<BlockPos> GRID = p ->
            p.getX() >= 0 && p.getX() <= 2 && p.getY() >= 0 && p.getY() <= 2 && p.getZ() >= 0 && p.getZ() <= 2;

    @Test
    void extraBreakKeepsOnePointForOriginalBlock() {
        // 추가 파괴(1) + 원래 블록(1) 뒤에도 1 이상 남아야 한다
        assertTrue(EffectContext.canAffordExtra(3, 1));
        assertFalse(EffectContext.canAffordExtra(2, 1));
        assertFalse(EffectContext.canAffordExtra(1, 1));
        assertTrue(EffectContext.canAffordExtra(5, 2));
        assertFalse(EffectContext.canAffordExtra(4, 2));
        assertFalse(EffectContext.canAffordExtra(2, 0)); // 소모 0이어도 최소 1로 본다
        assertTrue(EffectContext.canAffordExtra(3, 0));
    }

    @Test
    void bfsStopsAtMax() {
        BlockPos start = new BlockPos(1, 1, 1);
        List<BlockPos> found = EffectContext.bfs(GRID, start, 8, true);
        assertEquals(8, found.size());
        assertFalse(found.contains(start));
        assertEquals(8, new HashSet<>(found).size());
        assertTrue(found.stream().allMatch(GRID));
    }

    @Test
    void bfsCoversWholeGridWithoutStart() {
        BlockPos start = new BlockPos(0, 0, 0);
        assertEquals(26, EffectContext.bfs(GRID, start, 100, true).size());
        assertEquals(26, EffectContext.bfs(GRID, start, 100, false).size());
    }

    @Test
    void bfsSixNeighbourhoodSkipsDiagonals() {
        // 시작점과 대각선 한 칸만 같은 블록: 6방향이면 못 찾고 26방향이면 찾는다.
        BlockPos start = BlockPos.ZERO;
        BlockPos diag = new BlockPos(1, 1, 0);
        Predicate<BlockPos> same = p -> p.equals(start) || p.equals(diag);
        assertEquals(List.of(), EffectContext.bfs(same, start, 10, false));
        assertEquals(List.of(diag), EffectContext.bfs(same, start, 10, true));
    }

    @Test
    void bfsZeroMaxIsEmpty() {
        assertEquals(List.of(), EffectContext.bfs(GRID, new BlockPos(1, 1, 1), 0, true));
    }

    @Test
    void chanceBoundaries() {
        RandomSource r = RandomSource.create(42L);
        for (int i = 0; i < 1000; i++) {
            assertFalse(EffectContext.chance(0, r));
            assertTrue(EffectContext.chance(100, r));
        }
    }
}
