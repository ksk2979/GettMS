package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.detector.DetectorConfig;

/** S2C: full detector.json, sent on login and on reload. */
public record DetectorConfigSyncPayload(DetectorConfig config) implements CustomPacketPayload {
    public static final Type<DetectorConfigSyncPayload> TYPE = new Type<>(GettCore.id("detector_config_sync"));
    public static final StreamCodec<ByteBuf, DetectorConfigSyncPayload> STREAM_CODEC =
            DetectorConfig.STREAM_CODEC.map(DetectorConfigSyncPayload::new, DetectorConfigSyncPayload::config);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
