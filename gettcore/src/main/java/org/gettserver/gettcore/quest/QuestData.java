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
    private final Map<UUID, QuestPrefs> prefs = new HashMap<>();

    public static QuestData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** Mutable progress of one player in one line (created empty on first use). */
    public QuestProgress progress(UUID player, String lineId) {
        return players.computeIfAbsent(player, k -> new HashMap<>()).computeIfAbsent(lineId, k -> new QuestProgress());
    }

    /** Mutable preferences of a player (created empty on first use). */
    public QuestPrefs prefs(UUID player) {
        return prefs.computeIfAbsent(player, k -> new QuestPrefs());
    }

    /** Deletes every line's progress and the preferences of a player. */
    public void reset(UUID player) {
        players.remove(player);
        prefs.remove(player);
        setDirty();
    }

    /** Deletes one line's progress of a player and drops the line from their order/hidden prefs. */
    public void resetLine(UUID player, String lineId) {
        Map<String, QuestProgress> lines = players.get(player);
        if (lines != null) lines.remove(lineId);
        QuestPrefs p = prefs.get(player);
        if (p != null) {
            p.order().remove(lineId);
            p.hidden().remove(lineId);
        }
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
        CompoundTag pt = tag.getCompound("prefs");
        for (String key : pt.getAllKeys()) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                GettCore.LOGGER.warn("gettcore_quests: bad prefs key {}", key);
                continue;
            }
            QuestPrefs.CODEC.parse(NbtOps.INSTANCE, pt.get(key))
                    .resultOrPartial(err -> GettCore.LOGGER.warn("gettcore_quests: prefs {}: {}", key, err))
                    .ifPresent(p -> data.prefs.put(id, p));
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
        CompoundTag pt = new CompoundTag();
        for (Map.Entry<UUID, QuestPrefs> e : prefs.entrySet()) {
            QuestPrefs.CODEC.encodeStart(NbtOps.INSTANCE, e.getValue())
                    .resultOrPartial(err -> GettCore.LOGGER.error("gettcore_quests save prefs: {}", err))
                    .ifPresent(t -> pt.put(e.getKey().toString(), t));
        }
        tag.put("prefs", pt);
        return tag;
    }
}
