package org.gettserver.gettcore.economy;

/** Client-side cache of the local player's balance. Plain data; safe to load on servers. */
public final class ClientBalance {
    private static volatile long balance;

    private ClientBalance() {}

    public static void set(long value) {
        balance = value;
    }

    public static long get() {
        return balance;
    }
}
