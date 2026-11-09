package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.equipment.EquipmentConfig;

/** S2C: full equipment.json (item tooltips show the overridden values), sent on login and on reload. */
public record EquipmentConfigSyncPayload(EquipmentConfig config) implements CustomPacketPayload {
    public static final Type<EquipmentConfigSyncPayload> TYPE = new Type<>(GettCore.id("equipment_config_sync"));
    public static final StreamCodec<ByteBuf, EquipmentConfigSyncPayload> STREAM_CODEC =
            EquipmentConfig.STREAM_CODEC.map(EquipmentConfigSyncPayload::new, EquipmentConfigSyncPayload::config);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
