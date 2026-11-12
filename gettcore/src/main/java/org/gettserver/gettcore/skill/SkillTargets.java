package org.gettserver.gettcore.skill;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.gettserver.gettcore.pet.entity.GettPet;
import org.jetbrains.annotations.Nullable;

/** Who a boss skill may hit, and how it hits them. */
public final class SkillTargets {
    private SkillTargets() {}

    /** Players (survival/adventure) and owned GettPets inside {@code box}, never the caster (null = caster gone). */
    public static List<LivingEntity> in(ServerLevel level, AABB box, @Nullable Mob caster) {
        return level.getEntitiesOfClass(LivingEntity.class, box, e -> e != caster && valid(level, e));
    }

    /** {@link SkillRules#validTarget} for a real entity. */
    public static boolean valid(Mob caster, LivingEntity e) {
        return valid(caster.level(), e);
    }

    /** {@link SkillRules#validTarget} for a real entity, in {@code level}. */
    private static boolean valid(Level level, LivingEntity e) {
        boolean creativeOrSpectator = e instanceof Player p && (p.isCreative() || p.isSpectator());
        boolean playerSide = e instanceof Player || (e instanceof GettPet pet && pet.ownerId() != null);
        return SkillRules.validTarget(e.isAlive(), e.level() == level, creativeOrSpectator, playerSide);
    }

    /** Mob-attack damage from the caster; when it lands, {@code push} is added to the victim's motion (synced). */
    public static void hit(Mob caster, LivingEntity victim, double damage, Vec3 push) {
        hit(caster.damageSources().mobAttack(caster), victim, damage, push);
    }

    /** As {@link #hit(Mob, LivingEntity, double, Vec3)}, but magic damage when the caster is gone (null). */
    public static void hit(ServerLevel level, @Nullable Mob caster, LivingEntity victim, double damage, Vec3 push) {
        if (caster != null) hit(caster, victim, damage, push);
        else hit(level.damageSources().magic(), victim, damage, push);
    }

    private static void hit(DamageSource source, LivingEntity victim, double damage, Vec3 push) {
        if (!victim.hurt(source, (float) damage)) return;
        victim.push(push.x, push.y, push.z);
        victim.hurtMarked = true;
    }
}
