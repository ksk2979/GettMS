package org.gettserver.gettcore.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record PriceDynamicsConfig(boolean enabled, double sellDropPercentPerUnit, double recoverPercentPerHour,
                                  double minRatio, double maxRatio) {
    public static final PriceDynamicsConfig DISABLED = new PriceDynamicsConfig(false, 1.0, 10.0, 0.5, 2.0);

    public static final Codec<PriceDynamicsConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("enabled", DISABLED.enabled()).forGetter(PriceDynamicsConfig::enabled),
            Codec.DOUBLE.optionalFieldOf("sellDropPercentPerUnit", DISABLED.sellDropPercentPerUnit()).forGetter(PriceDynamicsConfig::sellDropPercentPerUnit),
            Codec.DOUBLE.optionalFieldOf("recoverPercentPerHour", DISABLED.recoverPercentPerHour()).forGetter(PriceDynamicsConfig::recoverPercentPerHour),
            Codec.DOUBLE.optionalFieldOf("minRatio", DISABLED.minRatio()).forGetter(PriceDynamicsConfig::minRatio),
            Codec.DOUBLE.optionalFieldOf("maxRatio", DISABLED.maxRatio()).forGetter(PriceDynamicsConfig::maxRatio)
    ).apply(i, PriceDynamicsConfig::new));
}
