package org.gettserver.gettcore.pet.boss;

import java.util.EnumSet;
import java.util.List;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;
import org.gettserver.gettcore.skill.BossConfig;
import org.gettserver.gettcore.skill.SkillSpec;
import org.gettserver.gettcore.skill.SkillTargets;

/**
 * A skill-casting boss's fight movement: backs off inside {@code preferredDistance - 2}, follows beyond
 * {@code preferredDistance + 4}, otherwise holds; stands still while casting; melees whoever is within reach.
 * Chases like {@code MeleeAttackGoal} when {@code preferredDistance == 0}, without an enabled skill, or against a
 * target its skills cannot hit.
 */
public class KeepDistanceGoal extends Goal {
    static final int ATTACK_COOLDOWN_TICKS = 20;
    static final int REPATH_TICKS = 10;
    /** Hold band around {@code preferredDistance}: retreat inside {@code - RETREAT_MARGIN}, follow beyond {@code + FOLLOW_MARGIN}. */
    static final double RETREAT_MARGIN = 2;
    static final double FOLLOW_MARGIN = 4;
    /** Retreat target search: horizontal / vertical range for {@link DefaultRandomPos#getPosAway}. */
    private static final int RETREAT_RANGE = 16;
    private static final int RETREAT_HEIGHT = 7;

    enum Move { RETREAT, APPROACH, HOLD }

    private final PathfinderMob mob;
    private final double speed;
    private int attackCooldown;
    private int repath;
    private Move lastMove = Move.HOLD;

    public KeepDistanceGoal(PathfinderMob mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** Pure: what to do at {@code distance} from the target: stay within {@code preferred - 2 .. preferred + 4}. */
    static Move decide(double distance, double preferred) {
        if (distance < preferred - RETREAT_MARGIN) return Move.RETREAT;
        if (distance > preferred + FOLLOW_MARGIN) return Move.APPROACH;
        return Move.HOLD;
    }

    /**
     * Pure: {@link #decide}, or a plain melee chase ({@link Move#APPROACH}) when keeping distance is pointless:
     * {@code preferred == 0}, no enabled skill, or a target the skills cannot hit.
     */
    static Move move(double distance, double preferred, boolean hasSkill, boolean skillTarget) {
        if (preferred <= 0 || !hasSkill || !skillTarget) return Move.APPROACH;
        return decide(distance, preferred);
    }

    @Override
    public boolean canUse() {
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive() && EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        mob.setAggressive(true);
        attackCooldown = 0;
        repath = 0;
    }

    @Override
    public void stop() {
        mob.setAggressive(false);
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) return;
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (attackCooldown > 0) attackCooldown--;
        if (mob instanceof GettBoss boss && boss.bossState().skills().casting()) {
            mob.getNavigation().stop();
            return;
        }
        if (attackCooldown == 0 && mob.isWithinMeleeAttackRange(target) && mob.getSensing().hasLineOfSight(target)) {
            mob.swing(InteractionHand.MAIN_HAND);
            mob.doHurtTarget(target);
            attackCooldown = ATTACK_COOLDOWN_TICKS;
        }
        BossConfig.Boss cfg = BossAggro.config(mob);
        Move move = move(mob.distanceTo(target), cfg.preferredDistance(), hasEnabledSkill(cfg.skills()),
                SkillTargets.valid(mob, target));
        boolean changed = move != lastMove;
        lastMove = move;
        if (move == Move.HOLD) {
            mob.getNavigation().stop();
            return;
        }
        if (!changed && --repath > 0 && !mob.getNavigation().isDone()) return;
        repath = REPATH_TICKS;
        if (move == Move.APPROACH) {
            mob.getNavigation().moveTo(target, speed);
        } else {
            Vec3 away = DefaultRandomPos.getPosAway(mob, RETREAT_RANGE, RETREAT_HEIGHT, target.position());
            if (away != null) mob.getNavigation().moveTo(away.x, away.y, away.z, speed);
        }
    }

    private static boolean hasEnabledSkill(List<SkillSpec> skills) {
        for (SkillSpec s : skills) {
            if (s.enabled()) return true;
        }
        return false;
    }
}
