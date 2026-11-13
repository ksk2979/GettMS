package org.gettserver.gettcore.pet.boss;

import com.crispytwig.naturalist.world.entity.ai.goal.FlyingWanderGoal;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.economy.EconomyData;
import org.gettserver.gettcore.economy.Gold;
import org.gettserver.gettcore.economy.Ledger;
import org.gettserver.gettcore.mobs.MobStatsRules;
import org.gettserver.gettcore.mobs.MobStatsService;
import org.gettserver.gettcore.pet.PetSpecies;
import org.gettserver.gettcore.skill.BossConfig;

/** Shared boss logic. The boss classes cannot share a superclass (each extends its Naturalist original). */
public final class BossBehavior {
    /** Loot table rolls per death: the original's table, three times. */
    public static final int LOOT_ROLLS = 3;
    static final double FALLBACK_HEALTH_MULT = 5.0;
    static final double FALLBACK_ATTACK_MULT = 2.0;
    static final double FALLBACK_SIZE_SCALE = 1.6;
    static final double FALLBACK_FLYING_SIZE_SCALE = 2.5;
    static final double ATTACK_SPEED_MODIFIER = 1.2;

    static final int BAR_SYNC_TICKS = 10;

    private BossBehavior() {}

    /** Boss bar distance: a little past the range at which the boss gives up its target, at least 32 blocks. */
    static double barRange(double releaseRange) {
        return Math.max(32.0, releaseRange + 8.0);
    }

    /** A player sees the bar while alive, in the boss's level and within {@code range} blocks. */
    static boolean showsBar(boolean alive, boolean sameLevel, double distance, double range) {
        return alive && sameLevel && distance <= range;
    }

    /**
     * The bar follows distance, not entity tracking: tracking reaches 160 blocks and a player who died near the
     * boss kept the bar after respawning far away.
     */
    private static void syncBossBar(Mob boss, BossState state, double range) {
        if (!(boss.level() instanceof net.minecraft.server.level.ServerLevel level)) return;
        var bar = state.bossEvent();
        for (ServerPlayer p : List.copyOf(bar.getPlayers())) {
            if (!showsBar(p.isAlive() && !p.hasDisconnected(), p.level() == level, p.distanceTo(boss), range)) {
                bar.removePlayer(p);
            }
        }
        for (ServerPlayer p : level.players()) {
            if (showsBar(p.isAlive(), true, p.distanceTo(boss), range)) bar.addPlayer(p);
        }
    }

    /** The boss's species from pets.json (keyed by its own entity id, e.g. gettcore:boss_lion), or the fallback. */
    public static PetSpecies species(Mob boss) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(boss.getType());
        return GettData.get().pets().species(id).orElseGet(() -> fallbackSpecies(id, boss instanceof FlyingAnimal));
    }

    static PetSpecies fallbackSpecies(ResourceLocation id, boolean flying) {
        return new PetSpecies(id.toString(), flying ? PetSpecies.Kind.FLYING_MOUNT : PetSpecies.Kind.FIGHTER, id,
                6, 0.3, 1, 0.6, 0.3, true, flying ? FALLBACK_FLYING_SIZE_SCALE : FALLBACK_SIZE_SCALE,
                FALLBACK_HEALTH_MULT, FALLBACK_ATTACK_MULT, 0, PetSpecies.DEFAULT_JUMP_HEIGHT);
    }

    /**
     * Once per boss (guarded by its {@code Applied} flag): MAX_HEALTH x healthMult at full health, ATTACK_DAMAGE
     * x attackMult, scale = sizeScale. The multiplied base values are saved with the entity's attributes.
     * mobs.json's override for the boss type comes first (once, flagged), so the multiplier applies on top of it.
     */
    public static void apply(Mob boss, PetSpecies s) {
        MobStatsService.applyOnce(boss);
        multiplyBase(boss, Attributes.MAX_HEALTH, s.healthMult());
        boss.setHealth(boss.getMaxHealth());
        multiplyBase(boss, Attributes.ATTACK_DAMAGE, s.attackMult());
        if (boss instanceof GettBoss b) b.bossState().markApplied((float) s.sizeScale());
        // The hitbox follows the new Scale through each boss's onSyncedDataUpdated.
    }

    private static void multiplyBase(Mob boss, Holder<Attribute> attribute, double mult) {
        AttributeInstance instance = boss.getAttribute(attribute);
        if (instance != null && mult > 0) instance.setBaseValue(MobStatsRules.bossBase(instance.getBaseValue(), mult));
    }

    /**
     * Server tick of every boss: a boss whose point no longer exists (removed in the editor or with /gett boss
     * remove) is discarded; otherwise stats on the first tick, boss bar progress, aggro, skills, leash to its point.
     */
    static void serverTick(Mob boss, BossState state) {
        if (isOrphan(state.pointId(), GettData.get().bossPoints())) {
            state.bossEvent().removeAllPlayers();
            boss.discard();
            return;
        }
        if (!state.applied()) apply(boss, species(boss));
        float max = boss.getMaxHealth();
        state.bossEvent().setProgress(max > 0 ? Mth.clamp(boss.getHealth() / max, 0.0F, 1.0F) : 0.0F);
        BossConfig.Boss cfg = GettData.get().bosses().boss(BuiltInRegistries.ENTITY_TYPE.getKey(boss.getType()));
        if (boss.tickCount % BAR_SYNC_TICKS == 0) syncBossBar(boss, state, barRange(cfg.releaseRange()));
        BossAggro.serverTick(boss, state, cfg);
        state.skills().tick(cfg.skills(), boss.getTarget(), cfg.enrageBelow(), cfg.enrageCooldownMult(), cfg.shoutRange());
        if (state.skills().casting()) return; // a dash is not pulled home mid-way
        String id = state.pointId();
        if (id.isEmpty()) return;
        point(id).filter(p -> p.dimension().equals(boss.level().dimension()))
                .ifPresent(p -> tickLeash(boss, p.pos(), p.radius()));
    }

    /** Pure: the boss came from a point ({@code pointId} non-empty) that is not in {@code points} any more. */
    public static boolean isOrphan(String pointId, List<BossPoint> points) {
        if (pointId.isEmpty()) return false;
        for (BossPoint p : points) {
            if (p.id().equals(pointId)) return false;
        }
        return true;
    }

    public static Optional<BossPoint> point(String id) {
        for (BossPoint p : GettData.get().bossPoints()) {
            if (p.id().equals(id)) return Optional.of(p);
        }
        return Optional.empty();
    }

    /** Pure: farther (horizontally) than twice the point radius from its center. */
    public static boolean outsideLeash(double dx, double dz, int radius) {
        double max = radius * 2.0;
        return dx * dx + dz * dz > max * max;
    }

    /** A boss that strayed beyond twice the radius is put back on the ground within half the radius of the center. */
    public static void tickLeash(Mob boss, BlockPos center, int radius) {
        if (!outsideLeash(boss.getX() - (center.getX() + 0.5), boss.getZ() - (center.getZ() + 0.5), radius)) return;
        Level level = boss.level();
        RandomSource r = boss.getRandom();
        int spread = Math.max(1, radius / 2);
        int x = center.getX() + r.nextInt(spread * 2 + 1) - spread;
        int z = center.getZ() + r.nextInt(spread * 2 + 1) - spread;
        // Never load a chunk for this; the boss tries again next tick.
        if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) return;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        boss.getNavigation().stop();
        boss.teleportTo(x + 0.5, y, z + 0.5);
    }

    /** Ground bosses: melee whoever hurts them and nearby players, wander, look around. */
    public static void registerGroundGoals(PathfinderMob boss, GoalSelector goals, GoalSelector targets) {
        goals.removeAllGoals(g -> true);
        targets.removeAllGoals(g -> true);
        goals.addGoal(0, new FloatGoal(boss));
        goals.addGoal(1, new BossMeleeGoal(boss));
        goals.addGoal(5, new WaterAvoidingRandomStrollGoal(boss, 1.0));
        goals.addGoal(6, new LookAtPlayerGoal(boss, Player.class, 8.0F));
        goals.addGoal(7, new RandomLookAroundGoal(boss));
        BossAggro.registerTargets(boss, targets);
    }

    /** The plain chase-and-bite goal, holding still while the boss casts a skill (a cast owns the boss's movement). */
    private static final class BossMeleeGoal extends MeleeAttackGoal {
        private final PathfinderMob boss;

        BossMeleeGoal(PathfinderMob boss) {
            super(boss, ATTACK_SPEED_MODIFIER, true);
            this.boss = boss;
        }

        private boolean casting() {
            return boss instanceof GettBoss b && b.bossState().skills().casting();
        }

        /** Keeps running through a cast (so the wander goal cannot take the MOVE flag) but neither moves nor bites. */
        @Override
        public void tick() {
            if (casting()) {
                boss.getNavigation().stop();
                return;
            }
            super.tick();
        }
    }

    /** The vulture: Naturalist's flying wander (its flying navigation stays), melee chase through the air. */
    public static void registerFlyingGoals(PathfinderMob boss, GoalSelector goals, GoalSelector targets) {
        goals.removeAllGoals(g -> true);
        targets.removeAllGoals(g -> true);
        goals.addGoal(0, new FloatGoal(boss));
        goals.addGoal(1, new BossMeleeGoal(boss));
        goals.addGoal(5, new FlyingWanderGoal(boss));
        goals.addGoal(6, new LookAtPlayerGoal(boss, Player.class, 8.0F));
        goals.addGoal(7, new RandomLookAroundGoal(boss));
        BossAggro.registerTargets(boss, targets);
    }

    /** Rolls the original's loot table {@link #LOOT_ROLLS} times; {@code roll} is the boss class's super call. */
    public static void dropLoot(Runnable roll) {
        for (int n = 0; n < LOOT_ROLLS; n++) roll.run();
    }

    /** Pays the species' killGold to the player who killed the boss (nothing when it is 0). */
    public static void rewardKill(ServerPlayer killer, PetSpecies s) {
        long gold = s.killGold();
        if (gold <= 0) return;
        EconomyData econ = EconomyData.get(killer.server);
        if (econ.ledger().deposit(killer.getUUID(), gold) != Ledger.Result.OK) {
            GettCore.LOGGER.warn("gettcore: boss {} kill gold {} could not be paid to {}", s.id(), Gold.format(gold),
                    killer.getGameProfile().getName());
            return;
        }
        econ.changed(killer.getUUID());
        killer.sendSystemMessage(Component.translatable("gettcore.msg.boss.kill_gold", Gold.format(gold))
                .withStyle(ChatFormatting.GOLD));
        GettCore.LOGGER.info("gettcore: {} killed boss {} and got {} gold", killer.getGameProfile().getName(),
                s.id(), Gold.format(gold));
    }
}
