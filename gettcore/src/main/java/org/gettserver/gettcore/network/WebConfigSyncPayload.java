package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.web.WebConfig;

/** S2C: full web.json, sent on login and on reload. */
public record WebConfigSyncPayload(WebConfig config) implements CustomPacketPayload {
    public static final Type<WebConfigSyncPayload> TYPE = new Type<>(GettCore.id("web_config_sync"));
    public static final StreamCodec<ByteBuf, WebConfigSyncPayload> STREAM_CODEC =
            WebConfig.STREAM_CODEC.map(WebConfigSyncPayload::new, WebConfigSyncPayload::config);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
