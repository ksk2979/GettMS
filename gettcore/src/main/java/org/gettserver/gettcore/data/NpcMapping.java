package org.gettserver.gettcore.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;

/** NPC identity to dialog id. */
public record NpcMapping(Map<String, String> byUuid, Map<String, String> byName) {
    public static final NpcMapping EMPTY = new NpcMapping(Map.of(), Map.of());

    public static final Codec<NpcMapping> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("byUuid", Map.of()).forGetter(NpcMapping::byUuid),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("byName", Map.of()).forGetter(NpcMapping::byName)
    ).apply(i, NpcMapping::new));
}
