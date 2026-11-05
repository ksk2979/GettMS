package org.gettserver.gettcore.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerTest {
    @Test
    void depositAddsToZero() {
        Ledger l = new Ledger();
        UUID u = UUID.randomUUID();
        assertEquals(Ledger.Result.OK, l.deposit(u, 100));
        assertEquals(100, l.get(u));
    }

    @Test
    void withdrawInsufficient() {
        Ledger l = new Ledger();
        UUID u = UUID.randomUUID();
        assertEquals(Ledger.Result.INSUFFICIENT, l.withdraw(u, 1));
        assertEquals(0, l.get(u));
    }

    @Test
    void rejectsZeroAndNegative() {
        Ledger l = new Ledger();
        UUID u = UUID.randomUUID();
        assertEquals(Ledger.Result.INVALID_AMOUNT, l.deposit(u, 0));
        assertEquals(Ledger.Result.INVALID_AMOUNT, l.deposit(u, -5));
    }

    @Test
    void rejectsAboveMax() {
        Ledger l = new Ledger();
        assertEquals(Ledger.Result.INVALID_AMOUNT, l.deposit(UUID.randomUUID(), Ledger.MAX_AMOUNT + 1));
    }

    @Test
    void overflowGuard() {
        Ledger l = new Ledger();
        UUID u = UUID.randomUUID();
        assertEquals(Ledger.Result.OK, l.set(u, Ledger.MAX_AMOUNT));
        assertEquals(Ledger.Result.OVERFLOW, l.deposit(u, Ledger.MAX_AMOUNT));
        assertEquals(Ledger.MAX_AMOUNT, l.get(u));
    }

    @Test
    void topSortsDescending() {
        Ledger l = new Ledger();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        l.deposit(a, 10);
        l.deposit(b, 300);
        l.deposit(c, 50);
        List<Map.Entry<UUID, Long>> top = l.top(2);
        assertEquals(2, top.size());
        assertEquals(b, top.get(0).getKey());
        assertEquals(c, top.get(1).getKey());
    }
}
