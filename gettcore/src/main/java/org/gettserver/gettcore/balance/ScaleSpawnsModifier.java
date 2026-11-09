package org.gettserver.gettcore.balance;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.MobSpawnSettingsBuilder;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.gettserver.gettcore.GettCore;

/**
 * {@code gettcore:scale_spawns}: multiplies one mob's natural spawn weight in every biome (0 = removed). Runs in the
 * MODIFY phase, after every mod's add/remove, so it also covers spawns other mods added. Unknown entity = no-op
 * (logged once): the entity is decoded as a plain id, so a removed mod never breaks loading.
 */
public record ScaleSpawnsModifier(ResourceLocation entity, double multiplier) implements BiomeModifier {
    public static final MapCodec<ScaleSpawnsModifier> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            ResourceLocation.CODEC.fieldOf("entity").forGetter(ScaleSpawnsModifier::entity),
            Codec.DOUBLE.fieldOf("multiplier").forGetter(ScaleSpawnsModifier::multiplier)
    ).apply(i, ScaleSpawnsModifier::new));

    private static final java.util.Set<ResourceLocation> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final DeferredRegister<MapCodec<? extends BiomeModifier>> SERIALIZERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, GettCore.MOD_ID);

    static {
        SERIALIZERS.register("scale_spawns", () -> CODEC);
        SERIALIZERS.register("add_spawns", () -> SafeAddSpawnsModifier.CODEC);
    }

    public static void register(IEventBus modBus) {
        SERIALIZERS.register(modBus);
    }

    @Override
    public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (phase != Phase.MODIFY) return;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(entity).orElse(null);
        if (type == null) {
            if (WARNED.add(entity)) {
                GettCore.LOGGER.warn("gett_balance spawn tweak for unknown entity {} ignored (mod removed?)"
                        + " — remove it in the editor: 등장 tab, '원본 없음'", entity);
            }
            return;
        }
        MobSpawnSettingsBuilder spawns = builder.getMobSpawnSettings();
        for (MobCategory category : MobCategory.values()) {
            List<SpawnerData> list = spawns.getSpawner(category);
            for (int i = list.size() - 1; i >= 0; i--) {
                SpawnerData d = list.get(i);
                if (d.type != type) continue;
                int w = SpawnBalance.scaledWeight(d.getWeight().asInt(), multiplier);
                if (w <= 0) list.remove(i);
                else list.set(i, new SpawnerData(d.type, w, d.minCount, d.maxCount));
            }
        }
        if (multiplier <= 0) spawns.removeSpawnCost(type);
    }

    @Override
    public MapCodec<? extends BiomeModifier> codec() {
        return CODEC;
    }
}
