package org.gettserver.gettcore.pet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * Identifies one owned pet kind: the species id plus its (e.g. coat) variant. For Naturalist animals the variant is
 * the full variant key from {@code getVariantString()} (e.g. {@code naturalist:white_tiger}); mobs without variants
 * store {@link #DEFAULT_VARIANT}.
 */
public record PetKey(String speciesId, String variant) {
    /** Stored for mobs that have no data-driven variant. */
    public static final String DEFAULT_VARIANT = "default";

    public static final Codec<PetKey> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("species").forGetter(PetKey::speciesId),
            Codec.STRING.optionalFieldOf("variant", "").forGetter(PetKey::variant)
    ).apply(i, PetKey::new));

    public static final StreamCodec<ByteBuf, PetKey> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PetKey::speciesId,
            ByteBufCodecs.STRING_UTF8, PetKey::variant,
            PetKey::new);

    /** True when {@code variant} names a real variant key that can be applied with {@code setVariantString}. */
    public static boolean hasVariant(String variant) {
        return !variant.isBlank() && !DEFAULT_VARIANT.equals(variant);
    }

    /**
     * The variant's path for display ({@code gettcore.variant.<path>}), or empty when there is nothing worth showing:
     * a blank or {@link #DEFAULT_VARIANT} variant, or the species' own base coat (variant path equal to the wild
     * species path; for {@code gettcore:boss_<x>} the wild path is {@code <x>}).
     */
    public static Optional<String> variantPath(String speciesId, String variant) {
        if (!hasVariant(variant)) return Optional.empty();
        ResourceLocation v = ResourceLocation.tryParse(variant);
        String path = v != null ? v.getPath() : variant;
        ResourceLocation species = ResourceLocation.tryParse(speciesId);
        if (species != null && path.equals(wildPath(species))) return Optional.empty();
        return Optional.of(path);
    }

    /** {@link #variantPath(String, String)} of this key. */
    public Optional<String> variantPath() {
        return variantPath(speciesId, variant);
    }

    private static String wildPath(ResourceLocation species) {
        String p = species.getPath();
        return "gettcore".equals(species.getNamespace()) && p.startsWith("boss_") ? p.substring(5) : p;
    }
}
