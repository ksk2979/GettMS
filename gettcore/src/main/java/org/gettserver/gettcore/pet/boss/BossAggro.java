package org.gettserver.gettcore.pet.boss;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
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
        targets.addGoal(1, new BossHurtByTargetGoal(boss));
        targets.addGoal(2, new PlayerTargetGoal(boss));
    }

    /** Vanilla's line-of-sight memory (ticks) of each target goal: hurt-by sets it on start, the player goal keeps the default. */
    private static final int HURT_BY_UNSEEN_TICKS = 300;
    private static final int PLAYER_UNSEEN_TICKS = 60;

    /** Unseen memory for this tick: a 후공 boss never forgets a target behind a wall, 선공 keeps {@code vanilla}. */
    private static int unseenMemory(Mob boss, int vanilla) {
        return AggroRules.keepsUnseenTarget(config(boss).aggro()) ? Integer.MAX_VALUE : vanilla;
    }

    private static final class BossHurtByTargetGoal extends HurtByTargetGoal {
        BossHurtByTargetGoal(PathfinderMob boss) {
            super(boss);
        }

        @Override
        public boolean canContinueToUse() {
            unseenMemoryTicks = unseenMemory(mob, HURT_BY_UNSEEN_TICKS);
            return super.canContinueToUse();
        }
    }

    private static final class PlayerTargetGoal extends NearestAttackableTargetGoal<Player> {
        private final PathfinderMob boss;
        /** Vanilla fixes the search range at construction; 선공 keeps that, 후공 uses its live follow range. */
        private final double constructedRange;

        PlayerTargetGoal(PathfinderMob boss) {
            super(boss, Player.class, true);
            this.boss = boss;
            this.constructedRange = getFollowDistance();
        }

        @Override
        public boolean canUse() {
            if (!(boss instanceof GettBoss gb)) return super.canUse();
            BossConfig.Aggro mode = config(boss).aggro();
            if (mode == BossConfig.Aggro.FIRST) {
                targetConditions.range(constructedRange);
                return super.canUse();
            }
            if (!gb.bossState().aggro().angry()) return false;
            targetConditions.range(getFollowDistance());
            if (!super.canUse()) return false;
            return AggroRules.mayAcquirePlayer(mode, gb.bossState().aggro(), boss.distanceTo(target),
                    config(boss).reaggroRange());
        }

        @Override
        public boolean canContinueToUse() {
            unseenMemoryTicks = unseenMemory(mob, PLAYER_UNSEEN_TICKS);
            return super.canContinueToUse();
        }
    }

    /** A hit by a player, a player's projectile or an owned pet makes a 후공 boss angry. */
    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof GettBoss gb) || event.getEntity().level().isClientSide) return;
        var attacker = event.getSource().getEntity();
        if (attacker == null) return;
        BossState state = gb.bossState();
        state.setAggro(AggroRules.onHurt(state.aggro(), playerSide(attacker), event.getEntity().level().getGameTime()));
    }

    /** A player, or an entity owned by a player (a tamed animal, an owned pet). */
    private static boolean playerSide(Entity e) {
        boolean owned = (e instanceof OwnableEntity o && o.getOwner() instanceof Player)
                || (e instanceof GettPet p && p.ownerId() != null);
        return AggroRules.playerSide(e instanceof Player, owned);
    }

    /**
     * Memory tick; 후공 release when the target is too far, or when a calm 후공 boss still holds a player-side target
     * (switched from 선공, or its anger expired); live speed/armor from bosses.json.
     */
    static void serverTick(Mob boss, BossState state, BossConfig.Boss cfg) {
        long now = boss.level().getGameTime();
        AggroRules.Memory memory = state.aggro();
        if (memory.angry()) {
            boolean sees = boss.level().getNearestPlayer(boss.getX(), boss.getY(), boss.getZ(), cfg.searchRange(),
                    EntitySelector.NO_CREATIVE_OR_SPECTATOR) != null;
            state.setAggro(AggroRules.tick(memory, sees, now, cfg.calmSeconds()));
        }
        LivingEntity target = boss.getTarget();
        if (target != null && (AggroRules.shouldRelease(cfg.aggro(), boss.distanceTo(target), cfg.releaseRange())
                || (playerSide(target) && AggroRules.shouldDropPlayerTarget(cfg.aggro(), state.aggro())))) {
            boss.setTarget(null);
            boss.setLastHurtByMob(null);
            boss.getNavigation().stop();
        }
        cfg.speed().ifPresent(v -> setBase(boss, Attributes.MOVEMENT_SPEED, v));
        cfg.armor().ifPresent(v -> setBase(boss, Attributes.ARMOR, v));
        setBase(boss, Attributes.FOLLOW_RANGE, AggroRules.followRange(cfg.aggro(), cfg.releaseRange(),
                speciesBase(boss, Attributes.FOLLOW_RANGE)));
    }

    /** The species default (the boss type's registered attribute set): the range a 선공 boss acquires players at. */
    @SuppressWarnings("unchecked")
    private static double speciesBase(Mob boss, Holder<Attribute> attribute) {
        return DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) boss.getType()).getBaseValue(attribute);
    }

    private static void setBase(Mob boss, Holder<Attribute> attribute, double value) {
        AttributeInstance instance = boss.getAttribute(attribute);
        if (instance != null && instance.getBaseValue() != value) instance.setBaseValue(value);
    }
}
