package org.gettserver.gettcore.party;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.gettserver.gettcore.GettCore;

/** The parties of this world ({@code gettcore_parties.dat} in the overworld). Callers setDirty after edits. */
public class PartyData extends SavedData {
    private static final String NAME = "gettcore_parties";
    private static final SavedData.Factory<PartyData> FACTORY = new SavedData.Factory<>(PartyData::new, PartyData::load);

    private final PartyBook book = new PartyBook();

    public static PartyData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public PartyBook book() {
        return book;
    }

    public static PartyData load(CompoundTag tag, HolderLookup.Provider registries) {
        PartyData data = new PartyData();
        ListTag parties = tag.getList("parties", Tag.TAG_COMPOUND);
        for (int i = 0; i < parties.size(); i++) {
            CompoundTag p = parties.getCompound(i);
            List<PartyBook.Member> members = new ArrayList<>();
            ListTag list = p.getList("members", Tag.TAG_COMPOUND);
            for (int j = 0; j < list.size(); j++) {
                CompoundTag m = list.getCompound(j);
                try {
                    members.add(new PartyBook.Member(UUID.fromString(m.getString("id")), m.getString("name")));
                } catch (IllegalArgumentException e) {
                    GettCore.LOGGER.warn("gettcore_parties: bad member id {}", m.getString("id"));
                }
            }
            data.book.restore(p.getString("name"), members);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag parties = new ListTag();
        for (PartyBook.Party party : book.parties()) {
            CompoundTag p = new CompoundTag();
            p.putString("name", party.name());
            ListTag list = new ListTag();
            for (PartyBook.Member m : party.members()) {
                CompoundTag c = new CompoundTag();
                c.putString("id", m.id().toString());
                c.putString("name", m.name());
                list.add(c);
            }
            p.put("members", list);
            parties.add(p);
        }
        tag.put("parties", parties);
        return tag;
    }
}
