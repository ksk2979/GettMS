package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.NpcMapping;

/** Server to client: NPC to dialog mapping, so the client can cancel Easy NPC interaction for mapped NPCs. */
public record NpcMappingSyncPayload(Map<String, String> byUuid, Map<String, String> byName)
        implements CustomPacketPayload {
    public static final Type<NpcMappingSyncPayload> TYPE = new Type<>(GettCore.id("npc_mapping_sync"));
    public static final StreamCodec<ByteBuf, NpcMappingSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.STRING_UTF8),
            NpcMappingSyncPayload::byUuid,
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.STRING_UTF8),
            NpcMappingSyncPayload::byName,
            NpcMappingSyncPayload::new);

    public static NpcMappingSyncPayload of(NpcMapping m) {
        return new NpcMappingSyncPayload(m.byUuid(), m.byName());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
