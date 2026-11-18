package org.gettserver.gettcore.nick;

import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.gettserver.gettcore.GettCore;

/** The nicknames of this world ({@code gettcore_nicknames.dat} in the overworld). Callers setDirty after edits. */
public class NicknameData extends SavedData {
    private static final String NAME = "gettcore_nicknames";
    private static final SavedData.Factory<NicknameData> FACTORY =
            new SavedData.Factory<>(NicknameData::new, NicknameData::load);

    private final NicknameBook book = new NicknameBook();

    public static NicknameData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public NicknameBook book() {
        return book;
    }

    public static NicknameData load(CompoundTag tag, HolderLookup.Provider registries) {
        NicknameData data = new NicknameData();
        CompoundTag all = tag.getCompound("nicknames");
        for (String key : all.getAllKeys()) {
            try {
                data.book.restore(UUID.fromString(key), all.getString(key));
            } catch (IllegalArgumentException e) {
                GettCore.LOGGER.warn("gettcore_nicknames: bad player key {}", key);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        for (Map.Entry<UUID, String> e : book.all().entrySet()) all.putString(e.getKey().toString(), e.getValue());
        tag.put("nicknames", all);
        return tag;
    }
}
