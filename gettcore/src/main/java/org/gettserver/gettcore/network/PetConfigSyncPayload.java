package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.pet.PetConfig;

/** S2C: full pets.json values (health bar look, species whitelist), sent on login and on /gett reload. */
public record PetConfigSyncPayload(PetConfig config) implements CustomPacketPayload {
    public static final Type<PetConfigSyncPayload> TYPE = new Type<>(GettCore.id("pet_config_sync"));
    public static final StreamCodec<ByteBuf, PetConfigSyncPayload> STREAM_CODEC =
            PetConfig.STREAM_CODEC.map(PetConfigSyncPayload::new, PetConfigSyncPayload::config);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
