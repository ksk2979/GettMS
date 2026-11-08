package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/**
 * C2S: the player acted on dex entry {@code index}: {@link Action#SUMMON} (summon it, or recall it if it is the
 * active pet) or {@link Action#RELEASE} (remove it from the dex for good).
 */
public record PetDexActionPayload(int index, Action action) implements CustomPacketPayload {
    public enum Action { SUMMON, RELEASE }

    private static final StreamCodec<ByteBuf, Action> ACTION_CODEC = ByteBufCodecs.VAR_INT.map(i -> {
        Action[] all = Action.values();
        if (i < 0 || i >= all.length) throw new DecoderException("bad pet dex action " + i);
        return all[i];
    }, Action::ordinal);

    public static final Type<PetDexActionPayload> TYPE = new Type<>(GettCore.id("pet_dex_action"));
    public static final StreamCodec<ByteBuf, PetDexActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PetDexActionPayload::index,
            ACTION_CODEC, PetDexActionPayload::action,
            PetDexActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
