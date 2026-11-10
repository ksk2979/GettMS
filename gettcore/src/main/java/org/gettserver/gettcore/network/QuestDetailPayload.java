package org.gettserver.gettcore.network;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import org.gettserver.gettcore.GettCore;

/**
 * Server to client: open the quest screen for one quest. OFFER shows an accept button, REPORT a "보상 받기" button
 * (both answered with {@link QuestActionPayload}), VIEW is read-only. The dialog screen is closed just before this is
 * sent. {@code lineId} is the quest's line (sent back in the action). {@code items} are reward stacks always given
 * (count may exceed the max stack size); {@code choices} are the "pick one" reward stacks (empty when none).
 */
public record QuestDetailPayload(String lineId, String questId, String title, String description, String completeText,
                                 List<QuestSyncPayload.ObjState> objectives, long gold, List<ItemStack> items,
                                 List<ItemStack> choices, int xp, Mode mode) implements CustomPacketPayload {
    public enum Mode { OFFER, REPORT, VIEW }

    public static final Type<QuestDetailPayload> TYPE = new Type<>(GettCore.id("quest_detail"));
    private static final StreamCodec<RegistryFriendlyByteBuf, List<ItemStack>> ITEMS =
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list());
    public static final StreamCodec<RegistryFriendlyByteBuf, QuestDetailPayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> p.write(buf), QuestDetailPayload::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(lineId);
        buf.writeUtf(questId);
        buf.writeUtf(title);
        buf.writeUtf(description);
        buf.writeUtf(completeText);
        buf.writeCollection(objectives, QuestSyncPayload::writeObj);
        buf.writeVarLong(gold);
        ITEMS.encode(buf, items);
        ITEMS.encode(buf, choices);
        buf.writeVarInt(xp);
        buf.writeEnum(mode);
    }

    private static QuestDetailPayload read(RegistryFriendlyByteBuf buf) {
        return new QuestDetailPayload(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                buf.readList(QuestSyncPayload::readObj), buf.readVarLong(), ITEMS.decode(buf), ITEMS.decode(buf),
                buf.readVarInt(), buf.readEnum(Mode.class));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
