package org.gettserver.gettcore.pet.boss;

import com.crispytwig.naturalist.world.entity.ai.goal.FlyingWanderGoal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
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
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLEnvironment;
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
    /**
     * The boss bear's chase modifier. Naturalist's Bear sprints (run animation, +30% speed) only while its move
     * control's speed modifier is at least 1.25 (Bear.customServerAiStep), so the bear needs more than 1.2.
     */
    static final double BEAR_CHASE_MODIFIER = 1.3;

    static final int BAR_SYNC_TICKS = 10;
    /** Dev runs only: ticks between two {@code [boss-debug]} lines per boss with a target. */
    static final int DEBUG_INTERVAL_TICKS = 20;
    /** Bosses climb one-block ledges by walking (the vanilla default step is 0.6). */
    static final double MIN_STEP_HEIGHT = 1.0;

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
        applyCombatBases(boss);
        float max = boss.getMaxHealth();
        state.bossEvent().setProgress(max > 0 ? Mth.clamp(boss.getHealth() / max, 0.0F, 1.0F) : 0.0F);
        BossConfig.Boss cfg = GettData.get().bosses().boss(BuiltInRegistries.ENTITY_TYPE.getKey(boss.getType()));
        if (boss.tickCount % BAR_SYNC_TICKS == 0) syncBossBar(boss, state, barRange(cfg.releaseRange()));
        BossAggro.serverTick(boss, state, cfg);
        state.skills().tick(cfg.skills(), boss.getTarget(), cfg.enrageBelow(), cfg.enrageCooldownMult(), cfg.shoutRange());
        if (!FMLEnvironment.production && boss.tickCount % DEBUG_INTERVAL_TICKS == 0) logDebug(boss, state);
        if (state.skills().casting()) return; // a dash is not pulled home mid-way
        String id = state.pointId();
        if (id.isEmpty()) return;
        point(id).filter(p -> p.dimension().equals(boss.level().dimension()))
                .ifPresent(p -> tickLeash(boss, p.pos(), p.radius()));
    }

    /**
     * Every tick, so bosses spawned before this existed get it too: hits do not push a boss (knockback and explosion
     * knockback resistance 1; {@link BossKnockback} undoes pushes that bypass both), and it walks up one-block ledges.
     */
    static void applyCombatBases(Mob boss) {
        setBase(boss, Attributes.KNOCKBACK_RESISTANCE, 1.0);
        setBase(boss, Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 1.0);
        AttributeInstance step = boss.getAttribute(Attributes.STEP_HEIGHT);
        if (step != null && step.getBaseValue() < MIN_STEP_HEIGHT) step.setBaseValue(MIN_STEP_HEIGHT);
    }

    private static void setBase(Mob boss, Holder<Attribute> attribute, double value) {
        AttributeInstance instance = boss.getAttribute(attribute);
        if (instance != null && instance.getBaseValue() != value) instance.setBaseValue(value);
    }

    /**
     * Dev runs only: one line per second for a boss with a target, so a playtest log shows why a boss does not move.
     * moved = horizontal blocks since the last line; expect = blocks per second its move speed gives on open ground.
     */
    private static void logDebug(Mob boss, BossState state) {
        Vec3 pos = boss.position();
        Vec3 last = state.markDebugPosition(pos);
        LivingEntity target = boss.getTarget();
        if (target == null) return;
        double moved = last == null ? 0 : Math.hypot(pos.x - last.x, pos.z - last.z);
        PathNavigation nav = boss.getNavigation();
        GettCore.LOGGER.info("[boss-debug] type={} dist={} speedAttr={} sprint={} navDone={} pathNull={} wantedSpeed={}"
                        + " moveSpeed={} expect={} moved={} casting={} goal={} onGround={} target={}",
                BuiltInRegistries.ENTITY_TYPE.getKey(boss.getType()).getPath(), fmt(boss.distanceTo(target)),
                fmt(boss.getAttributeValue(Attributes.MOVEMENT_SPEED)), boss.isSprinting(), nav.isDone(),
                nav.getPath() == null, fmt(boss.getMoveControl().getSpeedModifier()), fmt(boss.getSpeed()),
                fmt(ChaseRules.groundBlocksPerSecond(boss.getSpeed())), fmt(moved), state.skills().casting(),
                state.chaseMode(), boss.onGround(), target.getName().getString());
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
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
        registerGroundGoals(boss, goals, targets, ATTACK_SPEED_MODIFIER);
    }

    /** Like {@link #registerGroundGoals(PathfinderMob, GoalSelector, GoalSelector)} with its own chase speed modifier. */
    public static void registerGroundGoals(PathfinderMob boss, GoalSelector goals, GoalSelector targets,
                                           double chaseModifier) {
        goals.removeAllGoals(g -> true);
        targets.removeAllGoals(g -> true);
        goals.addGoal(0, new FloatGoal(boss));
        goals.addGoal(1, new BossMeleeGoal(boss, chaseModifier, true));
        goals.addGoal(5, new WaterAvoidingRandomStrollGoal(boss, 1.0));
        goals.addGoal(6, new LookAtPlayerGoal(boss, Player.class, 8.0F));
        goals.addGoal(7, new RandomLookAroundGoal(boss));
        BossAggro.registerTargets(boss, targets);
    }

    /**
     * The chase-and-bite goal. Holds still while the boss casts a skill (a cast owns the boss's movement). A ground
     * boss ({@code walkStraight}) is never idle out of reach: with no usable path (none found, or one that ended short
     * of the target: the 2.24-wide boss bear needs 3-wide, 3-high gaps, so trees and low leaves cut its paths) or
     * while stuck it walks straight at its target, unless that would take it off a cliff or into lava. What still
     * blocks it there is for its skills (the dash and the roar clear terrain); plain walking breaks nothing.
     */
    static final class BossMeleeGoal extends MeleeAttackGoal {
        private final PathfinderMob boss;
        private final double chaseModifier;
        private final boolean walkStraight;
        private boolean wasCasting;
        /** Stuck check: where the boss stood at the start of the current window, and the window's age in ticks. */
        private Vec3 sampleFrom;
        private int sampleAge;
        /** Ticks left of walking straight after the boss was found stuck. */
        private int stuckHold;
        /** The boss walked straight (or held) during the current window. */
        private boolean windowStraight;

        BossMeleeGoal(PathfinderMob boss, double chaseModifier, boolean walkStraight) {
            super(boss, chaseModifier, true);
            this.boss = boss;
            this.chaseModifier = chaseModifier;
            this.walkStraight = walkStraight;
        }

        private boolean casting() {
            return boss instanceof GettBoss b && b.bossState().skills().casting();
        }

        private static boolean chaseable(LivingEntity target) {
            return target != null && target.isAlive() && EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target);
        }

        /** Vanilla starts only with a path (or in reach), and checks once a second; a ground boss starts at once. */
        @Override
        public boolean canUse() {
            return super.canUse() || walkStraight && chaseable(boss.getTarget());
        }

        @Override
        public void start() {
            super.start();
            resetStuck();
            LivingEntity target = boss.getTarget();
            // super.start used the path its last canUse found, which is old when the start came from our canUse.
            if (target != null) boss.getNavigation().moveTo(target, chaseModifier);
        }

        @Override
        public void stop() {
            super.stop();
            resetStuck();
            setMode("idle");
        }

        /** Keeps running through a cast (so the wander goal cannot take the MOVE flag) but neither moves nor bites. */
        @Override
        public void tick() {
            if (casting()) {
                boss.getNavigation().stop();
                wasCasting = true;
                resetStuck();
                setMode(ChaseRules.Mode.CAST);
                return;
            }
            if (wasCasting) {
                // The cast cleared the path; vanilla would re-path only once the target moved a block (else 5% a
                // tick), so a target standing still stalled the boss for about a second. Path again at once.
                wasCasting = false;
                LivingEntity target = boss.getTarget();
                if (target != null) boss.getNavigation().moveTo(target, chaseModifier);
            }
            super.tick();
            LivingEntity target = boss.getTarget();
            if (target == null) {
                setMode("idle");
                return;
            }
            boolean inReach = boss.isWithinMeleeAttackRange(target);
            if (!walkStraight) {
                setMode(inReach ? ChaseRules.Mode.ATTACK : ChaseRules.Mode.PATH);
                return;
            }
            PathNavigation nav = boss.getNavigation();
            ChaseRules.Mode mode = ChaseRules.mode(false, inReach, !nav.isDone(), checkStuck(inReach));
            if (mode == ChaseRules.Mode.DIRECT) {
                nav.stop();
                if (ChaseRules.safeAhead(groundAhead(target))) {
                    // The path's modifier: the move control turns the boss to the target and Naturalist's bear sprints.
                    boss.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), chaseModifier);
                } else {
                    mode = ChaseRules.Mode.HOLD;
                }
                windowStraight = true;
            }
            setMode(mode);
        }

        /**
         * Compares the position every {@link ChaseRules#STUCK_WINDOW_TICKS} while out of reach; true while the boss
         * walks straight after a window in which it barely moved on its path ({@link ChaseRules#straightTicksAfterWindow}).
         */
        private boolean checkStuck(boolean inReach) {
            if (stuckHold > 0) stuckHold--;
            if (inReach || sampleFrom == null) {
                sampleFrom = inReach ? null : boss.position();
                sampleAge = 0;
                windowStraight = false;
                return stuckHold > 0;
            }
            if (++sampleAge >= ChaseRules.STUCK_WINDOW_TICKS) {
                Vec3 now = boss.position();
                double moved = Math.hypot(now.x - sampleFrom.x, now.z - sampleFrom.z);
                double moveSpeed = boss.getAttributeValue(Attributes.MOVEMENT_SPEED) * chaseModifier;
                if (ChaseRules.stuck(moved, moveSpeed, ChaseRules.STUCK_WINDOW_TICKS)) {
                    stuckHold = ChaseRules.straightTicksAfterWindow(true, windowStraight);
                }
                sampleFrom = now;
                sampleAge = 0;
                windowStraight = false;
            }
            return stuckHold > 0;
        }

        private void resetStuck() {
            sampleFrom = null;
            sampleAge = 0;
            stuckHold = 0;
            windowStraight = false;
        }

        /** The column just past the boss's front edge towards {@code target}, from one above its feet to three below. */
        private List<ChaseRules.Ground> groundAhead(LivingEntity target) {
            double dx = target.getX() - boss.getX(), dz = target.getZ() - boss.getZ();
            double len = Math.hypot(dx, dz);
            if (len < 1.0E-6) return List.of(ChaseRules.Ground.SOLID);
            double ahead = boss.getBbWidth() / 2.0 + 0.5;
            int x = Mth.floor(boss.getX() + dx / len * ahead);
            int z = Mth.floor(boss.getZ() + dz / len * ahead);
            int feet = Mth.floor(boss.getY() + 1.0E-3);
            Level level = boss.level();
            List<ChaseRules.Ground> column = new ArrayList<>(5);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = feet + 1; y >= feet - 3; y--) {
                pos.set(x, y, z);
                column.add(groundOf(level, pos, level.getBlockState(pos)));
            }
            return column;
        }

        private static ChaseRules.Ground groundOf(Level level, BlockPos pos, BlockState state) {
            if (state.getFluidState().is(FluidTags.LAVA) || state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES)
                    || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)) {
                return ChaseRules.Ground.HAZARD;
            }
            if (state.getFluidState().is(FluidTags.WATER)) return ChaseRules.Ground.WATER;
            return state.getCollisionShape(level, pos).isEmpty() ? ChaseRules.Ground.AIR : ChaseRules.Ground.SOLID;
        }

        private void setMode(ChaseRules.Mode mode) {
            setMode(mode.name().toLowerCase(Locale.ROOT));
        }

        private void setMode(String mode) {
            if (boss instanceof GettBoss b) b.bossState().setChaseMode(mode);
        }
    }

    /** The vulture: Naturalist's flying wander (its flying navigation stays), melee chase through the air. */
    public static void registerFlyingGoals(PathfinderMob boss, GoalSelector goals, GoalSelector targets) {
        goals.removeAllGoals(g -> true);
        targets.removeAllGoals(g -> true);
        goals.addGoal(0, new FloatGoal(boss));
        goals.addGoal(1, new BossMeleeGoal(boss, ATTACK_SPEED_MODIFIER, false));
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
