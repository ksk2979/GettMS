package org.gettserver.gettcore.quest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KillCreditTest {
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID K = UUID.randomUUID();

    @Test
    void allHittersWithinWindowAreCredited() {
        KillCredit c = new KillCredit();
        c.hit(1, A, 0);
        c.hit(1, B, 10_000);
        assertEquals(Set.of(A, B), c.onDeath(1, null, 20_000));
    }

    @Test
    void hitOlderThanWindowIsNotCredited() {
        KillCredit c = new KillCredit();
        c.hit(1, A, 0);
        assertEquals(Set.of(), c.onDeath(1, null, 30_001));
    }

    @Test
    void killerAlwaysCredited() {
        assertEquals(Set.of(K), new KillCredit().onDeath(1, K, 0));
    }

    @Test
    void samePlayerOnce() {
        KillCredit c = new KillCredit();
        c.hit(1, A, 0);
        c.hit(1, A, 100);
        c.hit(1, A, 200);
        assertEquals(Set.of(A), c.onDeath(1, A, 300));
    }

    @Test
    void deathClearsVictim() {
        KillCredit c = new KillCredit();
        c.hit(1, A, 0);
        c.onDeath(1, null, 10);
        assertEquals(0, c.size());
    }

    @Test
    void pruneDropsStale() {
        KillCredit c = new KillCredit();
        c.hit(1, A, 0);
        c.hit(2, B, 40_000);
        c.prune(40_000);
        assertEquals(1, c.size());
        c.forget(2);
        assertEquals(0, c.size());
    }
}
