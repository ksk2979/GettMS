package org.gettserver.gettcore.balance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.MobSpawnSettingsBuilder;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.gettserver.gettcore.GettCore;

/**
 * {@code gettcore:add_spawns}: what our spawn rule copies are written as. Does exactly what
 * {@code neoforge:add_spawns} does, but can never stop a world from loading.
 *
 * <p>Why: biome modifiers are a datapack registry, decoded while the world loads, before any server event. A
 * {@code neoforge:add_spawns} copy whose mod was removed names an entity that no longer exists (vanilla's
 * SpawnerData codec fails) and biomes that no longer exist (an id the biome registry never binds fails the registry
 * check "Unbound values"); either makes the whole registry load fail and the world will not open. This type decodes
 * only plain data (entity ids as ResourceLocations, biomes as raw JSON) and resolves both at {@link #modify} time,
 * against the finished, frozen registries — a missing entity / biome / tag is then skipped with a warning.
 */
public final class SafeAddSpawnsModifier implements BiomeModifier {
    public record Spawner(ResourceLocation type, int weight, int minCount, int maxCount) {}

    static final Codec<Spawner> SPAWNER = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("type").forGetter(Spawner::type),
            Codec.INT.fieldOf("weight").forGetter(Spawner::weight),
            Codec.INT.fieldOf("minCount").forGetter(Spawner::minCount),
            Codec.INT.fieldOf("maxCount").forGetter(Spawner::maxCount)
    ).apply(i, Spawner::new));

    /** A single spawner object or a list, like the original files. */
    static final Codec<List<Spawner>> SPAWNERS = Codec.either(SPAWNER.listOf(), SPAWNER).xmap(
            e -> e.map(Function.identity(), List::of), Either::left);

    public static final MapCodec<SafeAddSpawnsModifier> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            ExtraCodecs.JSON.fieldOf("biomes").forGetter(SafeAddSpawnsModifier::biomes),
            SPAWNERS.fieldOf("spawners").forGetter(SafeAddSpawnsModifier::spawners)
    ).apply(i, SafeAddSpawnsModifier::new));

    private final JsonElement biomes;
    private final List<Spawner> spawners;
    /** Resolved on first use (registries are final by then). */
    private volatile Resolved resolved;

    private record Resolved(HolderSet<Biome> biomes, List<SpawnerData> spawners) {}

    public SafeAddSpawnsModifier(JsonElement biomes, List<Spawner> spawners) {
        this.biomes = biomes;
        this.spawners = List.copyOf(spawners);
    }

    public JsonElement biomes() {
        return biomes;
    }

    public List<Spawner> spawners() {
        return spawners;
    }

    @Override
    public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (phase != Phase.ADD) return;
        Resolved r = resolve();
        if (r.spawners().isEmpty() || !r.biomes().contains(biome)) return;
        MobSpawnSettingsBuilder spawns = builder.getMobSpawnSettings();
        for (SpawnerData d : r.spawners()) spawns.addSpawn(d.type.getCategory(), d);
    }

    private Resolved resolve() {
        Resolved r = resolved;
        if (r != null) return r;
        synchronized (this) {
            if (resolved != null) return resolved;
            Consumer<String> warn = m -> GettCore.LOGGER.warn("gett_balance spawn rule copy: {} — skipped"
                    + " (a mod was removed or updated? delete the copy in the editor: 등장 tab, '원본 없음')", m);
            List<SpawnerData> sp = resolveSpawners(spawners, id -> BuiltInRegistries.ENTITY_TYPE.getOptional(id), warn);
            HolderSet<Biome> bs = HolderSet.direct();
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) bs = resolveBiomes(biomes, server.registryAccess(), warn);
            else warn.accept("no server to resolve biomes " + biomes);
            resolved = new Resolved(bs, sp);
            return resolved;
        }
    }

    /** Spawners whose entity exists and whose numbers are valid; the others are reported and left out. */
    static List<SpawnerData> resolveSpawners(List<Spawner> spawners,
                                             Function<ResourceLocation, Optional<EntityType<?>>> entities,
                                             Consumer<String> warn) {
        List<SpawnerData> out = new ArrayList<>();
        for (Spawner s : spawners) {
            Optional<EntityType<?>> type = entities.apply(s.type());
            if (type.isEmpty()) {
                warn.accept("unknown entity " + s.type());
                continue;
            }
            if (s.weight() < 0 || s.minCount() < 1 || s.maxCount() < s.minCount()) {
                warn.accept("invalid numbers for " + s.type() + " (weight " + s.weight() + ", count "
                        + s.minCount() + "~" + s.maxCount() + ")");
                continue;
            }
            out.add(new SpawnerData(type.get(), s.weight(), s.minCount(), s.maxCount()));
        }
        return out;
    }

    /**
     * The biome set, decoded against the frozen registries (a missing id or tag is an error there, not a dangling
     * holder). If the whole value fails and it is a list, each entry is tried alone so the rest still applies.
     */
    static HolderSet<Biome> resolveBiomes(JsonElement json, HolderLookup.Provider registries, Consumer<String> warn) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        DataResult<HolderSet<Biome>> all = Biome.LIST_CODEC.parse(ops, json);
        if (all.result().isPresent()) return all.result().get();
        List<Holder<Biome>> kept = new ArrayList<>();
        if (json != null && json.isJsonArray()) {
            for (JsonElement e : json.getAsJsonArray()) {
                JsonArray one = new JsonArray();
                one.add(e);
                DataResult<HolderSet<Biome>> r = Biome.LIST_CODEC.parse(ops, one);
                if (r.result().isPresent()) r.result().get().forEach(kept::add);
                else warn.accept("unknown biome " + e);
            }
        } else {
            warn.accept("unknown biomes " + json + " (" + all.error().map(DataResult.Error::message).orElse("?") + ")");
        }
        return HolderSet.direct(kept);
    }

    @Override
    public MapCodec<? extends BiomeModifier> codec() {
        return CODEC;
    }
}
