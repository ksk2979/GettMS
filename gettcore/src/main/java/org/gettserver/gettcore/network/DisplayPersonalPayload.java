package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** S2C: play {@code url} on that display for this player only; "" goes back to the shared video. */
public record DisplayPersonalPayload(UUID display, String url) implements CustomPacketPayload {
    public static final Type<DisplayPersonalPayload> TYPE = new Type<>(GettCore.id("display_personal"));
    public static final StreamCodec<ByteBuf, DisplayPersonalPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, DisplayPersonalPayload::display,
            ByteBufCodecs.stringUtf8(1024), DisplayPersonalPayload::url,
            DisplayPersonalPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
