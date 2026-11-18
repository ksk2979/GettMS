package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.craft.CraftConfig;

/** S2C: full crafting.json (the crafting screen lists the recipes), sent on login and on reload. */
public record CraftConfigSyncPayload(CraftConfig config) implements CustomPacketPayload {
    public static final Type<CraftConfigSyncPayload> TYPE = new Type<>(GettCore.id("craft_config_sync"));
    public static final StreamCodec<ByteBuf, CraftConfigSyncPayload> STREAM_CODEC =
            CraftConfig.STREAM_CODEC.map(CraftConfigSyncPayload::new, CraftConfigSyncPayload::config);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
