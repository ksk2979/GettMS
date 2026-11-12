package org.gettserver.gettcore.skill;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.network.SkillShoutPayload;
import org.jetbrains.annotations.Nullable;

/** Casts one entity's skills: picks a skill, charges, releases, runs it out, then waits for the next. */
public final class SkillRunner {
    private final Mob mob;
    private final Machine machine = new Machine();

    public SkillRunner(Mob mob) {
        this.mob = mob;
    }

    /** Server tick. target may be null. No-op for an empty list (a running cast still finishes). */
    public void tick(List<SkillSpec> skills, @Nullable LivingEntity target, double enrageBelow, double enrageCooldownMult,
                     double shoutRange) {
        if (skills.isEmpty() && !machine.casting()) return;
        if (!(mob.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        boolean alive = mob.isAlive() && !mob.isRemoved();
        float max = mob.getMaxHealth();
        double ratio = max > 0 ? mob.getHealth() / max : 0;
        boolean started = machine.tick(alive, now, () -> pick(level, skills, target, now),
                s -> SkillRules.cooldownTicks(s, ratio, enrageBelow, enrageCooldownMult));
        if (started && machine.cast.spec().shout()) shout(level, machine.cast.spec().shoutText(), shoutRange);
        if (machine.phase() == Machine.Phase.CHARGE) holdStill(level, machine.cast.targetId());
    }

    /** Charge or active phase: movement goals must hold still. */
    public boolean casting() {
        return machine.casting();
    }

    public void cancel() {
        machine.cancel();
    }

    private Optional<Machine.Start> pick(ServerLevel level, List<SkillSpec> skills, @Nullable LivingEntity target, long now) {
        if (target == null || !SkillTargets.valid(mob, target)) return Optional.empty();
        return SkillRules.pick(skills, mob.distanceTo(target), machine.readyTick(), now)
                .flatMap(s -> SkillTypes.get(s.type()).map(type ->
                        new Machine.Start(type, new SkillType.Cast(level, mob, s, target.getId(), new Vec3[1]))));
    }

    private void shout(ServerLevel level, String text, double range) {
        SkillShoutPayload payload = new SkillShoutPayload(text);
        for (ServerPlayer p : level.players()) {
            if (SkillRules.hearsShout(p.distanceTo(mob), range)) PacketDistributor.sendToPlayer(p, payload);
        }
    }

    private void holdStill(ServerLevel level, int targetId) {
        mob.getNavigation().stop();
        Entity target = level.getEntity(targetId);
        if (target != null) mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
    }

    /** Level-free phase machine: IDLE -> CHARGE (chargeTicks) -> release -> ACTIVE (while the type says so) -> IDLE. */
    static final class Machine {
        enum Phase { IDLE, CHARGE, ACTIVE }

        record Start(SkillType type, SkillType.Cast cast) {}

        /** Game time each skill (by id) may be cast again. */
        private final Map<String, Long> readyTick = new HashMap<>();
        private Phase phase = Phase.IDLE;
        private SkillType type;
        private SkillType.Cast cast;
        private int age;

        /**
         * One tick. A dead caster cancels. While idle {@code pick} may start a cast (charge tick 0 runs at once);
         * {@code cooldown} is asked at release. Returns true when a cast started this tick.
         */
        boolean tick(boolean casterAlive, long now, Supplier<Optional<Start>> pick, ToIntFunction<SkillSpec> cooldown) {
            if (!casterAlive) {
                cancel();
                return false;
            }
            boolean started = false;
            if (phase == Phase.IDLE) {
                Optional<Start> start = pick.get();
                if (start.isEmpty()) return false;
                type = start.get().type();
                cast = start.get().cast();
                phase = Phase.CHARGE;
                age = 0;
                started = true;
            }
            if (phase == Phase.CHARGE) {
                if (age < cast.spec().chargeTicks()) {
                    type.charge(cast, age++);
                } else {
                    type.release(cast);
                    readyTick.put(cast.spec().id(), now + cooldown.applyAsInt(cast.spec()));
                    phase = Phase.ACTIVE;
                    age = 0;
                }
            } else if (!type.active(cast, age++)) {
                cancel();
            }
            return started;
        }

        void cancel() {
            phase = Phase.IDLE;
            type = null;
            cast = null;
            age = 0;
        }

        boolean casting() {
            return phase != Phase.IDLE;
        }

        Phase phase() {
            return phase;
        }

        Map<String, Long> readyTick() {
            return readyTick;
        }
    }
}
