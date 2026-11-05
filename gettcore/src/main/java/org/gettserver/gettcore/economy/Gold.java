package org.gettserver.gettcore.economy;

import java.util.Locale;

public final class Gold {
    private Gold() {}

    public static String format(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }
}
