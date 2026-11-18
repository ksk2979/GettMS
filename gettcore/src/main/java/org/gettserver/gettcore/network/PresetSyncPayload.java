package org.gettserver.gettcore.network;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.preset.ItemPresets;

/** S2C: every item preset (name -> stack with components), sent on login, on reload and when one is saved or removed. */
public record PresetSyncPayload(Map<String, ItemStack> presets) implements CustomPacketPayload {
    public static final Type<PresetSyncPayload> TYPE = new Type<>(GettCore.id("preset_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PresetSyncPayload> STREAM_CODEC =
            ByteBufCodecs.<RegistryFriendlyByteBuf, String, ItemStack, Map<String, ItemStack>>map(
                    HashMap::new, ByteBufCodecs.STRING_UTF8, ItemStack.STREAM_CODEC, ItemPresets.MAX_PRESETS)
                    .map(PresetSyncPayload::new, PresetSyncPayload::presets);

    public static PresetSyncPayload current() {
        return new PresetSyncPayload(new HashMap<>(ItemPresets.all()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
