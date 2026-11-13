package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** C2S: a button of the display menu ({@code DisplayRules.ACTION_*}); {@code url} only for the personal video. */
public record DisplayActionPayload(UUID display, int action, String url) implements CustomPacketPayload {
    public static final Type<DisplayActionPayload> TYPE = new Type<>(GettCore.id("display_action"));
    public static final StreamCodec<ByteBuf, DisplayActionPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, DisplayActionPayload::display,
            ByteBufCodecs.VAR_INT, DisplayActionPayload::action,
            ByteBufCodecs.stringUtf8(1024), DisplayActionPayload::url,
            DisplayActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
