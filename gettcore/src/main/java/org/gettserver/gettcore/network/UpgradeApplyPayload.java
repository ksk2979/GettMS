package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** C2S: "업그레이드" 버튼. 데이터 없음 — 서버가 열린 메뉴의 장비 칸만 보고 판정한다. */
public record UpgradeApplyPayload() implements CustomPacketPayload {
    public static final Type<UpgradeApplyPayload> TYPE = new Type<>(GettCore.id("upgrade_apply"));
    public static final StreamCodec<ByteBuf, UpgradeApplyPayload> STREAM_CODEC =
            StreamCodec.unit(new UpgradeApplyPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
