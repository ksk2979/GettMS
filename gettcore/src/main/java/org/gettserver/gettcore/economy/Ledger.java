package org.gettserver.gettcore.economy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Ledger {
    public static final long MAX_AMOUNT = 999_999_999_999L;

    public enum Result { OK, INVALID_AMOUNT, INSUFFICIENT, OVERFLOW }

    private final Map<UUID, Long> balances = new HashMap<>();

    public long get(UUID id) {
        return balances.getOrDefault(id, 0L);
    }

    private static boolean invalid(long amount) {
        return amount < 1 || amount > MAX_AMOUNT;
    }

    public Result deposit(UUID id, long amount) {
        if (invalid(amount)) return Result.INVALID_AMOUNT;
        long next = get(id) + amount;
        if (next > MAX_AMOUNT) return Result.OVERFLOW;
        balances.put(id, next);
        return Result.OK;
    }

    public Result withdraw(UUID id, long amount) {
        if (invalid(amount)) return Result.INVALID_AMOUNT;
        long cur = get(id);
        if (cur < amount) return Result.INSUFFICIENT;
        balances.put(id, cur - amount);
        return Result.OK;
    }

    public Result set(UUID id, long amount) {
        if (invalid(amount)) return Result.INVALID_AMOUNT;
        balances.put(id, amount);
        return Result.OK;
    }

    public List<Map.Entry<UUID, Long>> top(int n) {
        List<Map.Entry<UUID, Long>> list = new ArrayList<>(balances.entrySet());
        list.sort(Map.Entry.<UUID, Long>comparingByValue().reversed());
        return new ArrayList<>(list.subList(0, Math.max(0, Math.min(n, list.size()))));
    }

    public Map<UUID, Long> snapshot() {
        return new HashMap<>(balances);
    }
}
