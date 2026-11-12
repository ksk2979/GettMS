package org.gettserver.gettcore.skill;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.gettserver.gettcore.pet.entity.GettPet;

/** Who a boss skill may hit, and how it hits them. */
public final class SkillTargets {
    private SkillTargets() {}

    /** Players (survival/adventure) and owned GettPets inside {@code box}, never the caster. */
    public static List<LivingEntity> in(ServerLevel level, AABB box, Mob caster) {
        return level.getEntitiesOfClass(LivingEntity.class, box, e -> e != caster && valid(caster, e));
    }

    /** {@link SkillRules#validTarget} for a real entity. */
    public static boolean valid(Mob caster, LivingEntity e) {
        boolean creativeOrSpectator = e instanceof Player p && (p.isCreative() || p.isSpectator());
        boolean playerSide = e instanceof Player || (e instanceof GettPet pet && pet.ownerId() != null);
        return SkillRules.validTarget(e.isAlive(), e.level() == caster.level(), creativeOrSpectator, playerSide);
    }

    /** Mob-attack damage from the caster; when it lands, {@code push} is added to the victim's motion (synced). */
    public static void hit(Mob caster, LivingEntity victim, double damage, Vec3 push) {
        if (!victim.hurt(caster.damageSources().mobAttack(caster), (float) damage)) return;
        victim.push(push.x, push.y, push.z);
        victim.hurtMarked = true;
    }
}
