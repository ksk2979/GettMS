package org.gettserver.gettcore.item;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

public class ReturnPointData extends SavedData {
    private static final String NAME = "gettcore_world";
    private static final SavedData.Factory<ReturnPointData> FACTORY =
            new SavedData.Factory<>(ReturnPointData::new, ReturnPointData::load);

    private GlobalPos pos;
    private float yaw;

    public static ReturnPointData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public Optional<GlobalPos> pos() {
        return Optional.ofNullable(pos);
    }

    public float yaw() {
        return yaw;
    }

    public void set(ResourceKey<Level> dimension, BlockPos blockPos, float yaw) {
        this.pos = GlobalPos.of(dimension, blockPos.immutable());
        this.yaw = yaw;
        setDirty();
    }

    public static ReturnPointData load(CompoundTag tag, HolderLookup.Provider registries) {
        ReturnPointData data = new ReturnPointData();
        if (tag.contains("dimension", Tag.TAG_STRING) && tag.contains("pos")) {
            ResourceLocation dim = ResourceLocation.tryParse(tag.getString("dimension"));
            Optional<BlockPos> bp = NbtUtils.readBlockPos(tag, "pos");
            if (dim != null && bp.isPresent()) {
                data.pos = GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), bp.get());
                data.yaw = tag.getFloat("yaw");
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (pos != null) {
            tag.putString("dimension", pos.dimension().location().toString());
            tag.put("pos", NbtUtils.writeBlockPos(pos.pos()));
            tag.putFloat("yaw", yaw);
        }
        return tag;
    }
}
