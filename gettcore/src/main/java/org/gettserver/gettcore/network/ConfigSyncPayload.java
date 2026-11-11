package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

public record ConfigSyncPayload(int hudOffsetX, int hudOffsetY, String currencyName, float gunSoundVolume) implements CustomPacketPayload {
    public static final Type<ConfigSyncPayload> TYPE = new Type<>(GettCore.id("config_sync"));
    public static final StreamCodec<ByteBuf, ConfigSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ConfigSyncPayload::hudOffsetX,
            ByteBufCodecs.VAR_INT, ConfigSyncPayload::hudOffsetY,
            ByteBufCodecs.STRING_UTF8, ConfigSyncPayload::currencyName,
            ByteBufCodecs.FLOAT, ConfigSyncPayload::gunSoundVolume,
            ConfigSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
