package org.gettserver.gettcore.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;

public record DialogNode(String text, List<DialogChoice> choices) {
    public static final Codec<DialogNode> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("text").forGetter(DialogNode::text),
            DialogChoice.CODEC.listOf().optionalFieldOf("choices", List.of()).forGetter(DialogNode::choices)
    ).apply(i, DialogNode::new));
}
