package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.reinforce.ReinforceConfig;

/** S2C: full reinforce.json values (screen numbers), sent on login and on /gett reload. */
public record ReinforceConfigSyncPayload(ReinforceConfig config) implements CustomPacketPayload {
    public static final Type<ReinforceConfigSyncPayload> TYPE = new Type<>(GettCore.id("reinforce_config_sync"));
    public static final StreamCodec<ByteBuf, ReinforceConfigSyncPayload> STREAM_CODEC =
            ReinforceConfig.STREAM_CODEC.map(ReinforceConfigSyncPayload::new, ReinforceConfigSyncPayload::config);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
