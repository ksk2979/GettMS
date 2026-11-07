package org.gettserver.gettcore.quest;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.gettserver.gettcore.GettCore;

/** Per-player quest progress, per line ({@code gettcore_quests.dat} in the overworld). Callers setDirty after edits. */
public class QuestData extends SavedData {
    private static final String NAME = "gettcore_quests";
    private static final SavedData.Factory<QuestData> FACTORY = new SavedData.Factory<>(QuestData::new, QuestData::load);

    private final Map<UUID, Map<String, QuestProgress>> players = new HashMap<>();

    public static QuestData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** Mutable progress of one player in one line (created empty on first use). */
    public QuestProgress progress(UUID player, String lineId) {
        return players.computeIfAbsent(player, k -> new HashMap<>()).computeIfAbsent(lineId, k -> new QuestProgress());
    }

    /** Deletes every line's progress of a player. */
    public void reset(UUID player) {
        players.remove(player);
        setDirty();
    }

    public static QuestData load(CompoundTag tag, HolderLookup.Provider registries) {
        QuestData data = new QuestData();
        CompoundTag all = tag.getCompound("players");
        for (String key : all.getAllKeys()) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                GettCore.LOGGER.warn("gettcore_quests: bad player key {}", key);
                continue;
            }
            CompoundTag lines = all.getCompound(key);
            for (String lineId : lines.getAllKeys()) {
                Tag t = lines.get(lineId);
                QuestProgress.CODEC.parse(NbtOps.INSTANCE, t)
                        .resultOrPartial(err -> GettCore.LOGGER.warn("gettcore_quests: {} {}: {}", key, lineId, err))
                        .ifPresent(p -> data.players.computeIfAbsent(id, k -> new HashMap<>()).put(lineId, p));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        for (Map.Entry<UUID, Map<String, QuestProgress>> e : players.entrySet()) {
            CompoundTag lines = new CompoundTag();
            for (Map.Entry<String, QuestProgress> l : e.getValue().entrySet()) {
                QuestProgress.CODEC.encodeStart(NbtOps.INSTANCE, l.getValue())
                        .resultOrPartial(err -> GettCore.LOGGER.error("gettcore_quests save: {}", err))
                        .ifPresent(t -> lines.put(l.getKey(), t));
            }
            all.put(e.getKey().toString(), lines);
        }
        tag.put("players", all);
        return tag;
    }
}
