package org.gettserver.gettcore.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public record DialogDefinition(String id, String name, Optional<ResourceLocation> portrait, String start,
                               Map<String, DialogNode> nodes) {
    public static final Codec<DialogDefinition> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(DialogDefinition::id),
            Codec.STRING.fieldOf("name").forGetter(DialogDefinition::name),
            ResourceLocation.CODEC.optionalFieldOf("portrait").forGetter(DialogDefinition::portrait),
            Codec.STRING.fieldOf("start").forGetter(DialogDefinition::start),
            Codec.unboundedMap(Codec.STRING, DialogNode.CODEC).fieldOf("nodes").forGetter(DialogDefinition::nodes)
    ).apply(i, DialogDefinition::new));
}
