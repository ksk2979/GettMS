package org.gettserver.gettcore.hunting;

import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.economy.EconomyData;
import org.gettserver.gettcore.economy.Gold;
import org.gettserver.gettcore.economy.Ledger;

/**
 * Hunting zones: blocks natural spawns inside zones, tracks variants, refills zones, pays kill rewards and shows the
 * damage line for test spawns.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class HuntingEvents {
    private HuntingEvents() {}

    /** Natural and chunk-generation spawns (both go through this check). */
    @SubscribeEvent
    public static void onPositionCheck(MobSpawnEvent.PositionCheck event) {
        if (!GettData.get().loaded()) return;
        if (blocks(event.getLevel().getLevel(), event.getEntity(), event.getSpawnType())) {
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
        }
    }

    /** Patrols and reinforcements (they skip the position check). */
    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (!GettData.get().loaded()) return;
        if (blocks(event.getLevel().getLevel(), event.getEntity(), event.getSpawnType())) {
            event.setSpawnCancelled(true);
        }
    }

    private static boolean blocks(ServerLevel level, Mob mob, MobSpawnType type) {
        HuntingConfig cfg = GettData.get().hunting();
        if (cfg.zones().isEmpty()) return false;
        return HuntingRules.blocksSpawn(cfg, level.dimension(), BlockPos.containing(mob.getX(), mob.getY(), mob.getZ()),
                type, VariantTag.read(mob.getPersistentData()).isPresent());
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        if (!(entity instanceof Mob)) return;
        Optional<VariantTag> tag = VariantTag.read(entity.getPersistentData());
        if (tag.isEmpty()) return;
        if (!GettData.get().loaded()) {
            // Spawn chunks load before the data: track it once the zones are known (first hunting tick).
            HuntingService.deferJoin(entity, event.loadedFromDisk());
            return;
        }
        if (!HuntingService.onJoin(entity, tag.get(), event.loadedFromDisk())) {
            event.setCanceled(true);
            entity.discard();
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) HuntingService.onLeave(event.getEntity());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (GettData.get().loaded()) HuntingService.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        HuntingService.clear();
    }

    /** LOWEST so deaths canceled by other mods are not paid. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead.level().isClientSide() || !(event.getSource().getEntity() instanceof ServerPlayer killer)) return;
        Optional<VariantTag> tag = VariantTag.read(dead.getPersistentData());
        if (tag.isEmpty() || tag.get().gold() <= 0) return;
        long gold = tag.get().gold();
        EconomyData econ = EconomyData.get(killer.server);
        if (econ.ledger().deposit(killer.getUUID(), gold) != Ledger.Result.OK) {
            GettCore.LOGGER.warn("gettcore: hunting kill gold {} for {}/{} could not be paid to {}", Gold.format(gold),
                    tag.get().zone(), tag.get().entry(), killer.getGameProfile().getName());
            return;
        }
        econ.changed(killer.getUUID());
        killer.sendSystemMessage(Component.translatable("gettcore.msg.hunt.kill_gold", tag.get().entry(),
                Gold.format(gold)).withStyle(ChatFormatting.GOLD));
    }

    /** Experience = the mob's normal drop x the entry's multiplier (fixed at spawn). */
    @SubscribeEvent
    public static void onExperience(LivingExperienceDropEvent event) {
        Optional<VariantTag> tag = VariantTag.read(event.getEntity().getPersistentData());
        if (tag.isEmpty() || tag.get().xpMultiplier() == 1.0) return;
        event.setDroppedExperience((int) Math.round(event.getDroppedExperience() * tag.get().xpMultiplier()));
    }

    /** Test spawns only: "피해 12.5 · 남은 체력 87.5/100" to the player who hit it (damage actually applied). */
    @SubscribeEvent
    public static void onDamagePost(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide() || !(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        Optional<VariantTag> tag = VariantTag.read(victim.getPersistentData());
        if (tag.isEmpty() || !tag.get().test()) return;
        player.sendSystemMessage(Component.translatable("gettcore.msg.hunt.damage",
                HuntingRules.number(event.getNewDamage()), HuntingRules.number(victim.getHealth()),
                HuntingRules.number(victim.getMaxHealth())));
    }
}
