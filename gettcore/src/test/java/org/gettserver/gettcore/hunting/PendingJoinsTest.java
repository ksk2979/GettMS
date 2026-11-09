package org.gettserver.gettcore.hunting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/** 데이터가 읽히기 전에 들어온 변종(스폰 청크 등)은 줄 세워 두었다가 읽힌 뒤 추적한다. */
class PendingJoinsTest {
    @Test
    void joinsBeforeDataLoadAreReplayedOnceInOrder() {
        PendingJoins q = new PendingJoins();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        q.add(a, Level.OVERWORLD, true);
        q.add(b, Level.NETHER, false);
        q.add(a, Level.OVERWORLD, true);   // same entity again: once
        assertEquals(2, q.size());
        List<PendingJoins.Pending> out = q.drain();
        assertEquals(List.of(new PendingJoins.Pending(a, Level.OVERWORLD, true),
                new PendingJoins.Pending(b, Level.NETHER, false)), out);
        assertTrue(q.drain().isEmpty());
    }

    @Test
    void leavingBeforeDataLoadForgetsTheEntity() {
        PendingJoins q = new PendingJoins();
        UUID a = UUID.randomUUID();
        q.add(a, Level.OVERWORLD, true);
        q.remove(a);
        assertTrue(q.drain().isEmpty());
        q.add(a, Level.OVERWORLD, true);
        q.clear();
        assertEquals(0, q.size());
    }
}
