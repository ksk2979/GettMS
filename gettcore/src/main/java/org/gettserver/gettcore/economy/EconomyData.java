package org.gettserver.gettcore.economy;

import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.network.BalanceSyncPayload;

public class EconomyData extends SavedData {
    private static final String NAME = "gettcore_economy";
    private static final SavedData.Factory<EconomyData> FACTORY = new SavedData.Factory<>(EconomyData::new, EconomyData::load);

    private final Ledger ledger = new Ledger();
    private MinecraftServer server;

    public static EconomyData get(MinecraftServer server) {
        EconomyData data = server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
        data.server = server;
        return data;
    }

    public Ledger ledger() {
        return ledger;
    }

    public void changed(UUID id) {
        setDirty();
        if (server == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null) {
            PacketDistributor.sendToPlayer(player, new BalanceSyncPayload(ledger.get(id)));
        }
    }

    public static EconomyData load(CompoundTag tag, HolderLookup.Provider registries) {
        EconomyData data = new EconomyData();
        ListTag list = tag.getList("accounts", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID id = NbtUtils.loadUUID(entry.get("uuid"));
            long balance = entry.getLong("balance");
            if (balance > 0) data.ledger.set(id, balance);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Long> e : ledger.snapshot().entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.put("uuid", NbtUtils.createUUID(e.getKey()));
            entry.putLong("balance", e.getValue());
            list.add(entry);
        }
        tag.put("accounts", list);
        return tag;
    }
}
