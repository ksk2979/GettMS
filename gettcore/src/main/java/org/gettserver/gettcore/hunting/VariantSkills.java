package org.gettserver.gettcore.hunting;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.skill.SkillRunner;

/**
 * Skills on hunting variants: a tagged mob whose entry lists skills gets a {@link SkillRunner}. Skills only (no aggro
 * settings, no enrage). The entry is re-read from the config every tick, so editor saves apply live and a variant whose
 * zone or entry was removed loses its runner.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class VariantSkills {
    static final double SHOUT_RANGE = 24;

    /** Variant-tagged mobs in loaded server levels; the value is the runner, or null while the entry has no skills. */
    private static final Map<Mob, SkillRunner> TAGGED = new IdentityHashMap<>();

    private VariantSkills() {}

    /** Pure: does this variant need a runner? */
    static boolean wantsRunner(HuntingConfig.Entry entry) {
        return !entry.skills().isEmpty();
    }

    enum Action { NONE, CREATE, DROP }

    /** Pure: what to do with a tagged mob's runner given whether it has one and whether its entry wants one. */
    static Action decide(boolean hasRunner, boolean wants) {
        if (wants) return hasRunner ? Action.NONE : Action.CREATE;
        return hasRunner ? Action.DROP : Action.NONE;
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) return;
        if (VariantTag.read(mob.getPersistentData()).isPresent()) TAGGED.putIfAbsent(mob, null);
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) return;
        SkillRunner runner = TAGGED.remove(mob);
        if (runner != null) runner.cancel();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TAGGED.clear();
    }

    @SubscribeEvent
    public static void onTick(EntityTickEvent.Post event) {
        // Server side only (an integrated server's client copy shares the entity id). Untagged mobs (nearly all)
        // leave after one lookup.
        if (event.getEntity().level().isClientSide() || !(event.getEntity() instanceof Mob mob)
                || !TAGGED.containsKey(mob)) return;
        SkillRunner runner = TAGGED.get(mob);
        if (!mob.isAlive() || mob.isRemoved()) {
            if (runner != null) runner.cancel();
            TAGGED.remove(mob);
            return;
        }
        Optional<HuntingConfig.Entry> entry = entryOf(mob).filter(VariantSkills::wantsRunner);
        switch (decide(runner != null, entry.isPresent())) {
            case CREATE -> TAGGED.put(mob, runner = new SkillRunner(mob));
            case DROP -> {
                runner.cancel();
                TAGGED.put(mob, null);
                return;
            }
            case NONE -> {}
        }
        if (runner != null) runner.tick(entry.get().skills(), mob.getTarget(), 0, 1, SHOUT_RANGE);
    }

    /** The mob's current config entry via its variant tag (empty: untagged, data not loaded, zone/entry removed). */
    private static Optional<HuntingConfig.Entry> entryOf(Mob mob) {
        if (!GettData.get().loaded()) return Optional.empty();
        return VariantTag.read(mob.getPersistentData())
                .flatMap(t -> GettData.get().hunting().zone(t.zone()).flatMap(z -> z.entry(t.entry())));
    }
}
