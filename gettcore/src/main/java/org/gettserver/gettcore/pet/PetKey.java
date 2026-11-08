package org.gettserver.gettcore.pet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Identifies one owned pet kind: the species id plus its (e.g. coat) variant. */
public record PetKey(String speciesId, String variant) {
    public static final Codec<PetKey> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("species").forGetter(PetKey::speciesId),
            Codec.STRING.optionalFieldOf("variant", "").forGetter(PetKey::variant)
    ).apply(i, PetKey::new));

    public static final StreamCodec<ByteBuf, PetKey> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PetKey::speciesId,
            ByteBufCodecs.STRING_UTF8, PetKey::variant,
            PetKey::new);
}
