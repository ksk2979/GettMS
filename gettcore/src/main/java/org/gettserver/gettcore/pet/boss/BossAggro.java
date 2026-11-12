package org.gettserver.gettcore.pet.boss;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.minecraft.core.Holder;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.pet.entity.GettPet;
import org.gettserver.gettcore.skill.AggroRules;
import org.gettserver.gettcore.skill.BossConfig;

/** Entity glue for bosses.json's 선공/후공 aggro and live speed/armor; the rules are {@link AggroRules}. */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class BossAggro {
    private BossAggro() {}

    static BossConfig.Boss config(Mob boss) {
        return GettData.get().bosses().boss(BuiltInRegistries.ENTITY_TYPE.getKey(boss.getType()));
    }

    /** Hurt-by (whoever hit it) first, then the nearest player, which a calm 후공 boss may not pick. */
    static void registerTargets(PathfinderMob boss, GoalSelector targets) {
        targets.addGoal(1, new HurtByTargetGoal(boss));
        targets.addGoal(2, new PlayerTargetGoal(boss));
    }

    private static final class PlayerTargetGoal extends NearestAttackableTargetGoal<Player> {
        private final PathfinderMob boss;

        PlayerTargetGoal(PathfinderMob boss) {
            super(boss, Player.class, true);
            this.boss = boss;
        }

        @Override
        public boolean canUse() {
            if (!(boss instanceof GettBoss gb)) return super.canUse();
            BossConfig.Aggro mode = config(boss).aggro();
            if (mode == BossConfig.Aggro.FIRST) return super.canUse();
            if (!gb.bossState().aggro().angry()) return false;
            if (!super.canUse()) return false;
            return AggroRules.mayAcquirePlayer(mode, gb.bossState().aggro(), boss.distanceTo(target),
                    config(boss).reaggroRange());
        }
    }

    /** A hit by a player, a player's projectile or an owned pet makes a 후공 boss angry. */
    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof GettBoss gb) || event.getEntity().level().isClientSide) return;
        var attacker = event.getSource().getEntity();
        if (attacker == null) return;
        boolean owned = (attacker instanceof OwnableEntity o && o.getOwner() instanceof Player)
                || (attacker instanceof GettPet p && p.ownerId() != null);
        BossState state = gb.bossState();
        state.setAggro(AggroRules.onHurt(state.aggro(), AggroRules.playerSide(attacker instanceof Player, owned),
                event.getEntity().level().getGameTime()));
    }

    /** Memory tick, 후공 release when the target is too far, live speed/armor from bosses.json. */
    static void serverTick(Mob boss, BossState state, BossConfig.Boss cfg) {
        long now = boss.level().getGameTime();
        AggroRules.Memory memory = state.aggro();
        if (memory.angry()) {
            boolean sees = boss.level().getNearestPlayer(boss.getX(), boss.getY(), boss.getZ(), cfg.searchRange(),
                    EntitySelector.NO_CREATIVE_OR_SPECTATOR) != null;
            state.setAggro(AggroRules.tick(memory, sees, now, cfg.calmSeconds()));
        }
        LivingEntity target = boss.getTarget();
        if (target != null && AggroRules.shouldRelease(cfg.aggro(), boss.distanceTo(target), cfg.releaseRange())) {
            boss.setTarget(null);
            boss.setLastHurtByMob(null);
            boss.getNavigation().stop();
        }
        cfg.speed().ifPresent(v -> setBase(boss, Attributes.MOVEMENT_SPEED, v));
        cfg.armor().ifPresent(v -> setBase(boss, Attributes.ARMOR, v));
    }

    private static void setBase(Mob boss, Holder<Attribute> attribute, double value) {
        AttributeInstance instance = boss.getAttribute(attribute);
        if (instance != null && instance.getBaseValue() != value) instance.setBaseValue(value);
    }
}
