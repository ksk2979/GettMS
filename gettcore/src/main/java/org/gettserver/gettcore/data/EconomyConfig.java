package org.gettserver.gettcore.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record EconomyConfig(String currencyName, int hudOffsetX, int hudOffsetY, int xpOrbAmount,
                            int returnCastSeconds, PriceDynamicsConfig priceDynamics) {
    public static final EconomyConfig DEFAULT = new EconomyConfig("골드", 0, 0, 7, 3, PriceDynamicsConfig.DISABLED);

    public static final Codec<EconomyConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("currencyName", DEFAULT.currencyName()).forGetter(EconomyConfig::currencyName),
            Codec.INT.optionalFieldOf("hudOffsetX", DEFAULT.hudOffsetX()).forGetter(EconomyConfig::hudOffsetX),
            Codec.INT.optionalFieldOf("hudOffsetY", DEFAULT.hudOffsetY()).forGetter(EconomyConfig::hudOffsetY),
            Codec.INT.optionalFieldOf("xpOrbAmount", DEFAULT.xpOrbAmount()).forGetter(EconomyConfig::xpOrbAmount),
            Codec.INT.optionalFieldOf("returnCastSeconds", DEFAULT.returnCastSeconds()).forGetter(EconomyConfig::returnCastSeconds),
            PriceDynamicsConfig.CODEC.optionalFieldOf("priceDynamics", PriceDynamicsConfig.DISABLED).forGetter(EconomyConfig::priceDynamics)
    ).apply(i, EconomyConfig::new));
}
