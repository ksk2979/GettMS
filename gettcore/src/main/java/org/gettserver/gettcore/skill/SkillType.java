package org.gettserver.gettcore.skill;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** One kind of boss skill ({@link SkillSpec#type()}); {@link SkillRunner} drives its phases. */
public interface SkillType {
    /** Charge phase tick (age 0..chargeTicks-1): telegraph only. */
    void charge(Cast cast, int age);

    /** Called once when the charge ends. */
    void release(Cast cast);

    /** After release, every tick until it returns false. */
    boolean active(Cast cast, int age);

    /**
     * One running cast. {@code spec} is captured at cast start, so config edits never change a running cast.
     * {@code aim} has one slot the type may set (e.g. at release) and read in later phases.
     */
    record Cast(ServerLevel level, Mob caster, SkillSpec spec, int targetId, Vec3[] aim) {}
}
