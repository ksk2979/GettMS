package org.gettserver.gettcore.network;

import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/**
 * Client to server: something the player did in the party screens. {@code text} is the party name for CREATE,
 * {@code target} the other player for INVITE and KICK; unused fields are "" and the nil UUID. The server checks
 * everything again.
 */
public record PartyActionPayload(Action action, String text, UUID target) implements CustomPacketPayload {
    public enum Action { CREATE, INVITE, ACCEPT, DECLINE, LEAVE, KICK }

    public static final Type<PartyActionPayload> TYPE = new Type<>(GettCore.id("party_action"));
    public static final StreamCodec<FriendlyByteBuf, PartyActionPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeEnum(p.action());
                buf.writeUtf(p.text(), 64);
                buf.writeUUID(p.target());
            },
            buf -> new PartyActionPayload(buf.readEnum(Action.class), buf.readUtf(64), buf.readUUID()));

    public static PartyActionPayload of(Action action) {
        return new PartyActionPayload(action, "", Util.NIL_UUID);
    }

    public static PartyActionPayload of(Action action, UUID target) {
        return new PartyActionPayload(action, "", target);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
