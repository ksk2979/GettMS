package org.gettserver.gettcore.pet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.gettserver.gettcore.GettCore;
import org.jetbrains.annotations.Nullable;

/** Per-player pet collection: owned kinds in acquisition order plus the active one ({@code gettcore_pets.dat}, overworld). */
public class PetData extends SavedData {
    private static final String NAME = "gettcore_pets";
    private static final SavedData.Factory<PetData> FACTORY = new SavedData.Factory<>(PetData::new, PetData::load);

    private static final class Entry {
        final List<PetKey> owned = new ArrayList<>();
        @Nullable PetKey active;
    }

    private final Map<UUID, Entry> players = new HashMap<>();

    public static PetData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** Unmodifiable view of the player's owned kinds, in acquisition order. */
    public List<PetKey> owned(UUID player) {
        Entry e = players.get(player);
        return e == null ? List.of() : Collections.unmodifiableList(e.owned);
    }

    /** Adds a kind; false if the player already owns it. */
    public boolean add(UUID player, PetKey key) {
        Entry e = players.computeIfAbsent(player, k -> new Entry());
        if (e.owned.contains(key)) {
            return false;
        }
        e.owned.add(key);
        setDirty();
        return true;
    }

    public Optional<PetKey> active(UUID player) {
        Entry e = players.get(player);
        return e == null ? Optional.empty() : Optional.ofNullable(e.active);
    }

    public void setActive(UUID player, @Nullable PetKey key) {
        players.computeIfAbsent(player, k -> new Entry()).active = key;
        setDirty();
    }

    /** Clears every player's active pet (server start: summoned entities never survive a restart). */
    public void clearAllActive() {
        boolean changed = false;
        for (Entry e : players.values()) {
            if (e.active != null) {
                e.active = null;
                changed = true;
            }
        }
        if (changed) {
            setDirty();
        }
    }

    public static PetData load(CompoundTag tag, @Nullable HolderLookup.Provider registries) {
        PetData data = new PetData();
        CompoundTag all = tag.getCompound("players");
        for (String key : all.getAllKeys()) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                GettCore.LOGGER.warn("gettcore_pets: bad player key {}", key);
                continue;
            }
            CompoundTag src = all.getCompound(key);
            Entry e = new Entry();
            for (Tag t : src.getList("owned", Tag.TAG_COMPOUND)) {
                PetKey.CODEC.parse(NbtOps.INSTANCE, t)
                        .resultOrPartial(err -> GettCore.LOGGER.warn("gettcore_pets: {} owned: {}", key, err))
                        .ifPresent(k -> {
                            if (!e.owned.contains(k)) {
                                e.owned.add(k);
                            }
                        });
            }
            if (src.contains("active", Tag.TAG_COMPOUND)) {
                PetKey.CODEC.parse(NbtOps.INSTANCE, src.get("active"))
                        .resultOrPartial(err -> GettCore.LOGGER.warn("gettcore_pets: {} active: {}", key, err))
                        .ifPresent(k -> e.active = k);
            }
            data.players.put(id, e);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, @Nullable HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        for (Map.Entry<UUID, Entry> p : players.entrySet()) {
            CompoundTag out = new CompoundTag();
            ListTag owned = new ListTag();
            for (PetKey k : p.getValue().owned) {
                PetKey.CODEC.encodeStart(NbtOps.INSTANCE, k)
                        .resultOrPartial(err -> GettCore.LOGGER.error("gettcore_pets save: {}", err))
                        .ifPresent(owned::add);
            }
            out.put("owned", owned);
            PetKey active = p.getValue().active;
            if (active != null) {
                PetKey.CODEC.encodeStart(NbtOps.INSTANCE, active)
                        .resultOrPartial(err -> GettCore.LOGGER.error("gettcore_pets save: {}", err))
                        .ifPresent(t -> out.put("active", t));
            }
            all.put(p.getKey().toString(), out);
        }
        tag.put("players", all);
        return tag;
    }
}
