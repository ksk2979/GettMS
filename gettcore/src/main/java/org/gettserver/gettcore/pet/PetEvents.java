package org.gettserver.gettcore.pet;

import de.markusbordihn.easynpc.entity.easynpc.EasyNPC;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.network.MobHitPayload;
import org.gettserver.gettcore.pet.entity.GettPet;

/** Drives {@link PetService}: dex sync on login, silent recall on logout/dimension change/death, auto-return. */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class PetEvents {
    private static final int CHECK_INTERVAL_TICKS = 20;

    private PetEvents() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PetService.onLogin(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PetService.recall(player, true);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PetService.recall(player, true);
        }
    }

    /** LOWEST so deaths canceled by other mods are not received. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PetService.recall(player, true);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % CHECK_INTERVAL_TICKS == 0) {
            PetService.tick(server);
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        PetService.onServerStarted(event.getServer());
    }

    /** A pet saved in a chunk (e.g. across a restart) that is not its online owner's tracked pet never loads. */
    @SubscribeEvent
    public static void onJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof GettPet pet)) return;
        MinecraftServer server = event.getLevel().getServer();
        if (server != null && !PetService.isTrackedActive(server, pet, event.getEntity())) {
            event.setCanceled(true);
            event.getEntity().discard();
        }
    }

    /** Mob health bar: tell the attacking player (or the attacking pet's online owner) which mob it damaged. */
    @SubscribeEvent
    public static void onDamagePost(LivingDamageEvent.Post event) {
        Entity victim = event.getEntity();
        if (victim instanceof Player || victim instanceof GettPet || victim instanceof EasyNPC
                || victim.level().isClientSide()) return;
        if (!(event.getNewDamage() > 0)) return;
        Entity attacker = event.getSource().getEntity();
        ServerPlayer target = null;
        if (attacker instanceof ServerPlayer player) {
            target = player;
        } else if (attacker instanceof GettPet pet && pet.ownerId() != null) {
            MinecraftServer server = attacker.getServer();
            if (server != null) target = server.getPlayerList().getPlayer(pet.ownerId());
        }
        if (target != null) PacketDistributor.sendToPlayer(target, new MobHitPayload(victim.getId()));
    }
}
