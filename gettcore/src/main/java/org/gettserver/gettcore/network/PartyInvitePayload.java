package org.gettserver.gettcore.network;

import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/**
 * Server to client: an invitation to show (bottom right, with the time left), or with {@code ticks} 0 the one on
 * screen is over (answered elsewhere, timed out, party gone).
 */
public record PartyInvitePayload(String party, String inviter, UUID inviterId, int ticks) implements CustomPacketPayload {
    public static final Type<PartyInvitePayload> TYPE = new Type<>(GettCore.id("party_invite"));
    public static final StreamCodec<FriendlyByteBuf, PartyInvitePayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.party(), 64);
                buf.writeUtf(p.inviter(), 64);
                buf.writeUUID(p.inviterId());
                buf.writeVarInt(p.ticks());
            },
            buf -> new PartyInvitePayload(buf.readUtf(64), buf.readUtf(64), buf.readUUID(), buf.readVarInt()));

    public static PartyInvitePayload clear() {
        return new PartyInvitePayload("", "", Util.NIL_UUID, 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
