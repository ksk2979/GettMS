package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.upgrade.UpgradeConfig;

/** S2C: full upgrade.json (the upgrade screen shows the next item and its cost), sent on login and on reload. */
public record UpgradeConfigSyncPayload(UpgradeConfig config) implements CustomPacketPayload {
    public static final Type<UpgradeConfigSyncPayload> TYPE = new Type<>(GettCore.id("upgrade_config_sync"));
    public static final StreamCodec<ByteBuf, UpgradeConfigSyncPayload> STREAM_CODEC =
            UpgradeConfig.STREAM_CODEC.map(UpgradeConfigSyncPayload::new, UpgradeConfigSyncPayload::config);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
