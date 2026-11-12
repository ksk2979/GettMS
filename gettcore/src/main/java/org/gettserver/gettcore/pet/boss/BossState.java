package org.gettserver.gettcore.pet.boss;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Mob;
import org.gettserver.gettcore.skill.AggroRules;
import org.gettserver.gettcore.skill.SkillRunner;

/**
 * State of one boss entity, shared by all boss classes. Persistent values live in one synched {@link CompoundTag}
 * (the accessor is defined by each boss class) so the client gets the scale and both survive save/reload:
 * {@code Point} (boss point id), {@code Scale}, {@code Applied} (stats were multiplied once),
 * {@code Angry} / {@code Provoked} (후공 aggro memory, see {@link AggroRules}).
 */
public final class BossState {
    private static final String NBT_KEY = "GettBoss";
    private static final String POINT = "Point";
    private static final String SCALE = "Scale";
    private static final String APPLIED = "Applied";
    private static final String ANGRY = "Angry";
    private static final String PROVOKED = "Provoked";

    private final Mob mob;
    private final EntityDataAccessor<CompoundTag> data;
    private ServerBossEvent bossEvent;
    private SkillRunner skills;

    public BossState(Mob mob, EntityDataAccessor<CompoundTag> data) {
        this.mob = mob;
        this.data = data;
    }

    /** Constructor-time setup: never despawn, never pick up items. */
    public void init() {
        mob.setPersistenceRequired();
        mob.setCanPickUpLoot(false);
    }

    private CompoundTag tag() {
        return mob.getEntityData().get(data);
    }

    private void update(java.util.function.Consumer<CompoundTag> change) {
        CompoundTag copy = tag().copy();
        change.accept(copy);
        mob.getEntityData().set(data, copy);
    }

    public String pointId() {
        return tag().getString(POINT);
    }

    public void setPointId(String id) {
        update(t -> t.putString(POINT, id));
    }

    /** Model and hitbox scale (the species' sizeScale once applied), else 1. */
    public float scale() {
        CompoundTag tag = tag();
        return tag.contains(SCALE, Tag.TAG_FLOAT) ? tag.getFloat(SCALE) : 1.0F;
    }

    public boolean applied() {
        return tag().getBoolean(APPLIED);
    }

    /** Called by {@link BossBehavior#apply} once the stats are multiplied. */
    void markApplied(float scale) {
        update(t -> {
            t.putFloat(SCALE, scale);
            t.putBoolean(APPLIED, true);
        });
    }

    public AggroRules.Memory aggro() {
        CompoundTag tag = tag();
        return new AggroRules.Memory(tag.getBoolean(ANGRY), tag.getLong(PROVOKED));
    }

    /** No-op when unchanged, so the synched tag is not resent every tick. */
    public void setAggro(AggroRules.Memory m) {
        if (m.equals(aggro())) return;
        update(t -> {
            t.putBoolean(ANGRY, m.angry());
            t.putLong(PROVOKED, m.provokedTick());
        });
    }

    /** Lazily created; the name is the entity type's name ("보스 사자"), not a custom name. */
    public ServerBossEvent bossEvent() {
        if (bossEvent == null) {
            bossEvent = new ServerBossEvent(Component.translatable(mob.getType().getDescriptionId()),
                    BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
        }
        return bossEvent;
    }

    /** Lazily created; runs this boss's bosses.json skills (not saved: a reload starts idle). */
    public SkillRunner skills() {
        if (skills == null) skills = new SkillRunner(mob);
        return skills;
    }

    public void startSeenBy(ServerPlayer player) {
        bossEvent().addPlayer(player);
    }

    public void stopSeenBy(ServerPlayer player) {
        bossEvent().removePlayer(player);
    }

    public void save(CompoundTag out) {
        out.put(NBT_KEY, tag().copy());
    }

    public void load(CompoundTag in) {
        if (in.contains(NBT_KEY, Tag.TAG_COMPOUND)) mob.getEntityData().set(data, in.getCompound(NBT_KEY));
    }

    /** Server tick: first-tick stats, boss bar progress, leash to the boss point. */
    public void tick() {
        if (mob.level().isClientSide) return;
        BossBehavior.serverTick(mob, this);
    }
}
