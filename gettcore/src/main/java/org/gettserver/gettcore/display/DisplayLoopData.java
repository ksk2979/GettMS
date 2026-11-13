package org.gettserver.gettcore.display;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** The displays (Dream Displays ids) whose video repeats. Every other display stops at the end of its video. */
public class DisplayLoopData extends SavedData {
    private static final String NAME = "gettcore_display_loops";
    private static final SavedData.Factory<DisplayLoopData> FACTORY =
            new SavedData.Factory<>(DisplayLoopData::new, DisplayLoopData::load);

    private final Set<UUID> loops = new HashSet<>();

    public static DisplayLoopData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public boolean loops(UUID id) {
        return loops.contains(id);
    }

    public List<UUID> all() {
        return List.copyOf(loops);
    }

    public void set(UUID id, boolean on) {
        if (on ? loops.add(id) : loops.remove(id)) setDirty();
    }

    public static DisplayLoopData load(CompoundTag tag, HolderLookup.Provider registries) {
        DisplayLoopData data = new DisplayLoopData();
        ListTag list = tag.getList("loops", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            try {
                data.loops.add(UUID.fromString(list.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // a hand-edited entry that is not a UUID
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (UUID id : loops) list.add(StringTag.valueOf(id.toString()));
        tag.put("loops", list);
        return tag;
    }
}
