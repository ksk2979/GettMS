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

/**
 * A skill-casting boss's fight movement: backs off inside {@code preferredDistance - 2}, closes in beyond its
 * longest skill range, otherwise holds; stands still while casting; melees whoever is within reach.
 * {@code preferredDistance == 0} chases like {@code MeleeAttackGoal}.
 */
public class KeepDistanceGoal extends Goal {
    static final int ATTACK_COOLDOWN_TICKS = 20;
    static final int REPATH_TICKS = 10;
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

    /** Pure: what to do at {@code distance} from the target. */
    static Move decide(double distance, double preferred, double maxSkillRange) {
        if (distance < preferred - 2) return Move.RETREAT;
        if (distance > maxSkillRange) return Move.APPROACH;
        return Move.HOLD;
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
        double preferred = cfg.preferredDistance();
        Move move = preferred <= 0 ? Move.APPROACH
                : decide(mob.distanceTo(target), preferred, maxSkillRange(cfg.skills()));
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

    /** The longest {@code maxRange} among the enabled skills (0 without any). */
    private static double maxSkillRange(List<SkillSpec> skills) {
        double max = 0;
        for (SkillSpec s : skills) {
            if (s.enabled()) max = Math.max(max, s.maxRange());
        }
        return max;
    }
}
