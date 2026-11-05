package org.gettserver.gettcore.economy;

public final class Gold {
    private Gold() {}

    public static String format(long amount) {
        return String.format("%,d", amount);
    }
}
