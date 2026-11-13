package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** S2C: every display (Dream Displays id) whose video repeats; all others stop at the end. */
public record DisplayLoopSyncPayload(List<UUID> loops) implements CustomPacketPayload {
    public static final Type<DisplayLoopSyncPayload> TYPE = new Type<>(GettCore.id("display_loop_sync"));
    public static final StreamCodec<ByteBuf, DisplayLoopSyncPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list(65536)), DisplayLoopSyncPayload::loops,
            DisplayLoopSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
