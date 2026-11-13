package org.gettserver.gettcore.detector;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class DetectorScanTest {
    private static final BlockPos CENTER = new BlockPos(0, 64, 0);

    private static LongList five() {
        LongList l = new LongArrayList();
        for (int d : new int[] {9, 2, 7, 1, 5}) l.add(BlockPos.asLong(d, 64, 0));
        return l;
    }

    @Test
    void capKeepsNearest() {
        long[] r = DetectorScan.nearest(five(), CENTER, 2);
        assertArrayEquals(new long[] {BlockPos.asLong(1, 64, 0), BlockPos.asLong(2, 64, 0)}, r);
    }

    @Test
    void underCapKeepsAll() {
        assertEquals(5, DetectorScan.nearest(five(), CENTER, 10).length);
    }
}
