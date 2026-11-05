package org.gettserver.gettcore.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record DialogChoice(String label, ChoiceAction action) {
    public static final Codec<DialogChoice> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("label").forGetter(DialogChoice::label),
            ChoiceAction.CODEC.fieldOf("action").forGetter(DialogChoice::action)
    ).apply(i, DialogChoice::new));
}
