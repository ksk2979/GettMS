package org.gettserver.gettcore.pet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.Locale;

/** Capture ball grade. JSON uses the lowercase name ({@code "iron"}, ...). */
public enum CaptureTier {
    IRON, GOLD, DIAMOND, NETHERITE;

    public static final Codec<CaptureTier> CODEC = Codec.STRING.comapFlatMap(s -> {
        for (CaptureTier t : values()) {
            if (t.key().equals(s)) return DataResult.success(t);
        }
        return DataResult.error(() -> "Unknown capture tier: " + s);
    }, CaptureTier::key);

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
