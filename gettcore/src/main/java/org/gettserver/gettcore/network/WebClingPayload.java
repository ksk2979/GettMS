package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** C2S: 벽에 붙었다({@code on}) / 떨어졌다. 서버는 낙하 피해와 공중 체류 판정에만 쓴다. */
public record WebClingPayload(boolean on) implements CustomPacketPayload {
    public static final Type<WebClingPayload> TYPE = new Type<>(GettCore.id("web_cling"));
    public static final StreamCodec<ByteBuf, WebClingPayload> STREAM_CODEC =
            ByteBufCodecs.BOOL.map(WebClingPayload::new, WebClingPayload::on);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
