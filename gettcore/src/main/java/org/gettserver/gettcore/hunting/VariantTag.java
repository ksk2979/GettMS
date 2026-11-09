package org.gettserver.gettcore.hunting;

import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * The mark a hunting variant carries in its persistent data ({@code GettHunt}): zone id, entry name and the kill
 * reward frozen at spawn time (later edits leave existing variants as they are). {@code test} = /사냥터 소환
 * (damage display, not counted for the zone, never leashed).
 */
public record VariantTag(String zone, String entry, long gold, double xpMultiplier, boolean test) {
    public static final String KEY = "GettHunt";

    public static VariantTag of(String zone, HuntingConfig.Entry e, boolean test) {
        return new VariantTag(zone, e.name(), e.gold(), e.xpMultiplier(), test);
    }

    public static Optional<VariantTag> read(CompoundTag persistentData) {
        if (!persistentData.contains(KEY, Tag.TAG_COMPOUND)) return Optional.empty();
        CompoundTag t = persistentData.getCompound(KEY);
        return Optional.of(new VariantTag(t.getString("Zone"), t.getString("Entry"), t.getLong("Gold"),
                t.contains("Xp") ? t.getDouble("Xp") : 1.0, t.getBoolean("Test")));
    }

    public void write(CompoundTag persistentData) {
        CompoundTag t = new CompoundTag();
        t.putString("Zone", zone);
        t.putString("Entry", entry);
        t.putLong("Gold", gold);
        t.putDouble("Xp", xpMultiplier);
        t.putBoolean("Test", test);
        persistentData.put(KEY, t);
    }
}
