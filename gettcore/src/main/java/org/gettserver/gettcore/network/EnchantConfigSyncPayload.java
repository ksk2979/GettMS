package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.enchant.EnchantConfig;

/** S2C: full enchant.json values (tooltip numbers), sent on login and on /gett reload. */
public record EnchantConfigSyncPayload(EnchantConfig config) implements CustomPacketPayload {
    public static final Type<EnchantConfigSyncPayload> TYPE = new Type<>(GettCore.id("enchant_config_sync"));
    public static final StreamCodec<ByteBuf, EnchantConfigSyncPayload> STREAM_CODEC =
            ByteBufCodecs.fromCodec(EnchantConfig.CODEC)
                    .map(EnchantConfigSyncPayload::new, EnchantConfigSyncPayload::config);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
