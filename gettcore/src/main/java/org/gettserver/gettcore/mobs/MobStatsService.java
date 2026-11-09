package org.gettserver.gettcore.mobs;

import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.hunting.VariantTag;
import org.gettserver.gettcore.pet.entity.GettPet;

/**
 * Applies mobs.json to every newly appearing mob once (flag {@value #FLAG} in its persistent data). Bosses call
 * {@link #applyOnce} before their own multiplier, so the multiplier lands on the overridden base exactly once.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class MobStatsService {
    public static final String FLAG = "GettMobStats";

    private MobStatsService() {}

    /** HIGH: before other join handlers look at the mob's stats. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof LivingEntity living)) return;
        if (!GettData.get().loaded()) return;
        if (event.loadedFromDisk()) return;   // an existing mob: stays as it is
        applyOnce(living);
    }

    /** Overrides the base values (if mobs.json has any for the type) and flags the mob; no-op once flagged. */
    public static void applyOnce(LivingEntity mob) {
        var data = mob.getPersistentData();
        if (!MobStatsRules.shouldApply(data.getBoolean(FLAG), false, mob instanceof Player, mob instanceof GettPet,
                VariantTag.read(data).isPresent())) {
            return;
        }
        data.putBoolean(FLAG, true);
        Optional<MobStatsConfig.Stats> stats = GettData.get().mobs()
                .get(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()));
        if (stats.isEmpty()) return;
        MobStatsConfig.Stats s = stats.get();
        boolean fullHealth = mob.getHealth() >= mob.getMaxHealth();
        set(mob, Attributes.MAX_HEALTH, s.health());
        set(mob, Attributes.ATTACK_DAMAGE, s.attack());
        set(mob, Attributes.MOVEMENT_SPEED, s.speed());
        set(mob, Attributes.ARMOR, s.armor());
        if (s.health().isPresent() && fullHealth) mob.setHealth(mob.getMaxHealth());
    }

    private static void set(LivingEntity mob, Holder<Attribute> attribute, Optional<Double> value) {
        if (value.isEmpty()) return;
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(MobStatsRules.base(instance.getBaseValue(), value));
    }
}
