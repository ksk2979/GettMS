package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/**
 * S2C: replaces the whole highlight bundle {@code key} with {@code positions} for {@code ticks}.
 * Empty positions and ticks 0 deletes that key; key "" with ticks 0 deletes everything.
 */
public record DetectorHighlightPayload(String key, long[] positions, int ticks) implements CustomPacketPayload {
    /** Fixed key of the detector item's session. The enchant uses {@code "ore:" + Resolved.key()}. */
    public static final String DETECTOR_KEY = "detector";
    public static final int MAX_POSITIONS = 16384;

    public static final Type<DetectorHighlightPayload> TYPE = new Type<>(GettCore.id("detector_highlight"));
    private static final StreamCodec<ByteBuf, long[]> POSITIONS = new StreamCodec<>() {
        @Override
        public long[] decode(ByteBuf buf) {
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            if (n < 0 || n > MAX_POSITIONS) {
                throw new DecoderException("Too many positions: " + n + " > " + MAX_POSITIONS);
            }
            long[] out = new long[n];
            for (int i = 0; i < n; i++) out[i] = buf.readLong();
            return out;
        }

        @Override
        public void encode(ByteBuf buf, long[] value) {
            if (value.length > MAX_POSITIONS) {
                throw new EncoderException("Too many positions: " + value.length + " > " + MAX_POSITIONS);
            }
            ByteBufCodecs.VAR_INT.encode(buf, value.length);
            for (long l : value) buf.writeLong(l);
        }
    };
    public static final StreamCodec<ByteBuf, DetectorHighlightPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, DetectorHighlightPayload::key,
            POSITIONS, DetectorHighlightPayload::positions,
            ByteBufCodecs.VAR_INT, DetectorHighlightPayload::ticks,
            DetectorHighlightPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
