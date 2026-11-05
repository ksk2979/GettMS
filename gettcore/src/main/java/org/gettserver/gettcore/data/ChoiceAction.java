package org.gettserver.gettcore.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.util.StringRepresentable;

public record ChoiceAction(Type type, Optional<String> target) {
    public enum Type implements StringRepresentable {
        NEXT("next"), OPEN_SHOP_BUY("open_shop_buy"), OPEN_SHOP_SELL("open_shop_sell"), CLOSE("close");

        private final String serialized;

        Type(String serialized) {
            this.serialized = serialized;
        }

        @Override
        public String getSerializedName() {
            return serialized;
        }
    }

    public static final Codec<ChoiceAction> CODEC = RecordCodecBuilder.create(i -> i.group(
            StringRepresentable.fromEnum(Type::values).fieldOf("type").forGetter(ChoiceAction::type),
            Codec.STRING.optionalFieldOf("target").forGetter(ChoiceAction::target)
    ).apply(i, ChoiceAction::new));
}
