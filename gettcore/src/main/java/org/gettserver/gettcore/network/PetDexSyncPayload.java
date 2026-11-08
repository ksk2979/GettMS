package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.pet.PetKey;

/** S2C: the player's pet dex. {@code boss.get(i)} is the boss flag of {@code owned.get(i)}. */
public record PetDexSyncPayload(List<PetKey> owned, List<Boolean> boss, Optional<PetKey> active)
        implements CustomPacketPayload {
    public static final Type<PetDexSyncPayload> TYPE = new Type<>(GettCore.id("pet_dex_sync"));
    public static final StreamCodec<ByteBuf, PetDexSyncPayload> STREAM_CODEC = StreamCodec.composite(
            PetKey.STREAM_CODEC.apply(ByteBufCodecs.list()), PetDexSyncPayload::owned,
            ByteBufCodecs.BOOL.apply(ByteBufCodecs.list()), PetDexSyncPayload::boss,
            ByteBufCodecs.optional(PetKey.STREAM_CODEC), PetDexSyncPayload::active,
            PetDexSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
