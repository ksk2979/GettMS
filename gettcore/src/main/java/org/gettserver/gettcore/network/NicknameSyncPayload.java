package org.gettserver.gettcore.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** Server to client: every nickname (player id -> nickname), on login and whenever one changes. */
public record NicknameSyncPayload(Map<UUID, String> nicknames) implements CustomPacketPayload {
    public static final Type<NicknameSyncPayload> TYPE = new Type<>(GettCore.id("nickname_sync"));
    public static final StreamCodec<FriendlyByteBuf, NicknameSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.nicknames().size());
                for (Map.Entry<UUID, String> e : p.nicknames().entrySet()) {
                    buf.writeUUID(e.getKey());
                    buf.writeUtf(e.getValue(), 64);
                }
            },
            buf -> {
                int n = buf.readVarInt();
                Map<UUID, String> map = new HashMap<>();
                for (int i = 0; i < n; i++) map.put(buf.readUUID(), buf.readUtf(64));
                return new NicknameSyncPayload(Map.copyOf(map));
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
