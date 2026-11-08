package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** S2C: 강화 결과(화면 연출용). {@code level} 은 판정 후 단계. */
public record ReinforceResultPayload(boolean success, int level) implements CustomPacketPayload {
    public static final Type<ReinforceResultPayload> TYPE = new Type<>(GettCore.id("reinforce_result"));
    public static final StreamCodec<ByteBuf, ReinforceResultPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, ReinforceResultPayload::success,
            ByteBufCodecs.VAR_INT, ReinforceResultPayload::level,
            ReinforceResultPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
