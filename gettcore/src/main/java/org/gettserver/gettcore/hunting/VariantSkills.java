package org.gettserver.gettcore.hunting;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
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

    private static final Map<Mob, SkillRunner> RUNNERS = new WeakHashMap<>();

    private VariantSkills() {}

    /** Pure: does this variant need a runner? */
    static boolean wantsRunner(HuntingConfig.Entry entry) {
        return !entry.skills().isEmpty();
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) return;
        entryOf(mob).filter(VariantSkills::wantsRunner).ifPresent(e -> RUNNERS.computeIfAbsent(mob, SkillRunner::new));
    }

    @SubscribeEvent
    public static void onTick(EntityTickEvent.Post event) {
        if (event.getEntity().level().isClientSide() || !(event.getEntity() instanceof Mob mob)) return;
        SkillRunner runner = RUNNERS.get(mob);
        if (runner == null) return;
        if (!mob.isAlive() || mob.isRemoved()) {
            runner.cancel();
            RUNNERS.remove(mob);
            return;
        }
        Optional<HuntingConfig.Entry> entry = entryOf(mob).filter(VariantSkills::wantsRunner);
        if (entry.isEmpty()) {
            runner.cancel();
            RUNNERS.remove(mob);
            return;
        }
        runner.tick(entry.get().skills(), mob.getTarget(), 0, 1, SHOUT_RANGE);
    }

    /** The mob's current config entry via its variant tag (empty: untagged, data not loaded, zone/entry removed). */
    private static Optional<HuntingConfig.Entry> entryOf(Mob mob) {
        if (!GettData.get().loaded()) return Optional.empty();
        return VariantTag.read(mob.getPersistentData())
                .flatMap(t -> GettData.get().hunting().zone(t.zone()).flatMap(z -> z.entry(t.entry())));
    }
}
