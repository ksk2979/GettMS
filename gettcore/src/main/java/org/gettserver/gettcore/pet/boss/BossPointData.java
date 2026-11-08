package org.gettserver.gettcore.pet.boss;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.gettserver.gettcore.GettCore;
import org.jetbrains.annotations.Nullable;

/**
 * Runtime state of every boss point ({@code gettcore_boss_points.dat}, overworld): the UUIDs of the bosses it spawned
 * that are believed alive, and the overworld game time the last one was gone (killed, captured or vanished;
 * -1 = never, which counts as "respawn interval elapsed").
 */
public class BossPointData extends SavedData {
    private static final String NAME = "gettcore_boss_points";
    private static final SavedData.Factory<BossPointData> FACTORY =
            new SavedData.Factory<>(BossPointData::new, BossPointData::load);
    public static final long NEVER_GONE = -1;

    private static final class State {
        final List<UUID> alive = new ArrayList<>();
        long lastGone = NEVER_GONE;
    }

    private final Map<String, State> points = new HashMap<>();

    public static BossPointData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** UUIDs of the point's bosses believed alive (a copy). */
    public List<UUID> alive(String pointId) {
        State s = points.get(pointId);
        return s == null ? List.of() : List.copyOf(s.alive);
    }

    public long lastGone(String pointId) {
        State s = points.get(pointId);
        return s == null ? NEVER_GONE : s.lastGone;
    }

    public void registerAlive(String pointId, UUID boss) {
        State s = points.computeIfAbsent(pointId, k -> new State());
        if (!s.alive.contains(boss)) {
            s.alive.add(boss);
            setDirty();
        }
    }

    /** Forgets a boss without starting the respawn timer (e.g. it could not be added to the level). */
    public void unregister(String pointId, UUID boss) {
        State s = points.get(pointId);
        if (s != null && s.alive.remove(boss)) setDirty();
    }

    /** A boss of the point died or was captured: forget it and start the respawn timer at {@code now}. */
    public void onBossGone(String pointId, @Nullable UUID boss, long now) {
        if (pointId.isEmpty()) return;
        State s = points.computeIfAbsent(pointId, k -> new State());
        if (boss != null) s.alive.remove(boss);
        s.lastGone = now;
        setDirty();
    }

    /**
     * Drops the UUIDs {@code isAlive} rejects and returns how many remain. If any were dropped and the point never
     * lost a boss before, the timer starts at {@code now} so a boss that vanished without dying still respawns.
     */
    public int prune(String pointId, Predicate<UUID> isAlive, long now) {
        State s = points.get(pointId);
        if (s == null) return 0;
        if (s.alive.removeIf(isAlive.negate())) {
            if (s.lastGone < 0) s.lastGone = now;
            setDirty();
        }
        return s.alive.size();
    }

    /**
     * Admin reset: the respawn timer counts as elapsed, so missing bosses spawn at the next check. The bosses still
     * alive stay registered (and counted), so the point never goes over its maxCount.
     */
    public void reset(String pointId) {
        State s = points.get(pointId);
        if (s == null) return;
        s.lastGone = NEVER_GONE;
        setDirty();
    }

    public void remove(String pointId) {
        if (points.remove(pointId) != null) setDirty();
    }

    public static BossPointData load(CompoundTag tag, @Nullable HolderLookup.Provider registries) {
        BossPointData data = new BossPointData();
        CompoundTag all = tag.getCompound("points");
        for (String id : all.getAllKeys()) {
            CompoundTag src = all.getCompound(id);
            State s = new State();
            for (Tag t : src.getList("alive", Tag.TAG_INT_ARRAY)) {
                try {
                    s.alive.add(NbtUtils.loadUUID(t));
                } catch (IllegalArgumentException e) {
                    GettCore.LOGGER.warn("gettcore_boss_points: {} bad uuid: {}", id, e.getMessage());
                }
            }
            s.lastGone = src.contains("lastGone", Tag.TAG_LONG) ? src.getLong("lastGone") : NEVER_GONE;
            data.points.put(id, s);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, @Nullable HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        for (Map.Entry<String, State> p : points.entrySet()) {
            CompoundTag out = new CompoundTag();
            ListTag alive = new ListTag();
            for (UUID u : p.getValue().alive) alive.add(NbtUtils.createUUID(u));
            out.put("alive", alive);
            out.putLong("lastGone", p.getValue().lastGone);
            all.put(p.getKey(), out);
        }
        tag.put("points", all);
        return tag;
    }
}
