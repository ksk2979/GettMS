package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.skill.SkillSpec;

/** S2C: a nearby boss started a skill; show its name big in the middle of the screen. */
public record SkillShoutPayload(String text) implements CustomPacketPayload {
    public static final Type<SkillShoutPayload> TYPE = new Type<>(GettCore.id("skill_shout"));
    public static final StreamCodec<ByteBuf, SkillShoutPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(SkillSpec.MAX_SHOUT_TEXT), SkillShoutPayload::text, SkillShoutPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
