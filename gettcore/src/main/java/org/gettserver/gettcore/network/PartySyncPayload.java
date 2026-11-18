package org.gettserver.gettcore.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/**
 * Server to client: the party the player is in, sent whenever it or a member's health changes. {@code name} "" with
 * no members = not in a party. The first member is the leader.
 */
public record PartySyncPayload(String name, List<Entry> members) implements CustomPacketPayload {
    public record Entry(UUID id, String name, boolean online, float health, float maxHealth) {}

    public static final Type<PartySyncPayload> TYPE = new Type<>(GettCore.id("party_sync"));
    public static final StreamCodec<FriendlyByteBuf, PartySyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.name(), 64);
                buf.writeVarInt(p.members().size());
                for (Entry e : p.members()) {
                    buf.writeUUID(e.id());
                    buf.writeUtf(e.name(), 64);
                    buf.writeBoolean(e.online());
                    buf.writeFloat(e.health());
                    buf.writeFloat(e.maxHealth());
                }
            },
            buf -> {
                String name = buf.readUtf(64);
                int n = Math.min(buf.readVarInt(), 16);
                List<Entry> members = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    members.add(new Entry(buf.readUUID(), buf.readUtf(64), buf.readBoolean(), buf.readFloat(),
                            buf.readFloat()));
                }
                return new PartySyncPayload(name, List.copyOf(members));
            });

    public static PartySyncPayload none() {
        return new PartySyncPayload("", List.of());
    }

    public boolean inParty() {
        return !members.isEmpty();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
