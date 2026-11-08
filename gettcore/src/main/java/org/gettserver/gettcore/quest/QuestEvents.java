package org.gettserver.gettcore.quest;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.pet.entity.GettPet;

/** Game events that drive quest judgment (spec §5.3 "판정 시점"). */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class QuestEvents {
    private QuestEvents() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        QuestService.forget(player.getUUID());
        boolean fresh = QuestService.mainLine().isPresent() && QuestService.isFresh(player);
        QuestService.refresh(player);
        if (fresh) {
            player.sendSystemMessage(Component.translatable("gettcore.quest.welcome").withStyle(ChatFormatting.YELLOW));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        QuestService.forget(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        QuestService.tick(event.getServer());
    }

    /** LOWEST so other mods' cancellations are already applied; canceled deaths are not received. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        if (event.getSource().getEntity() instanceof ServerPlayer killer && killer != event.getEntity()) {
            QuestService.onKill(killer, event.getEntity());
        } else if (event.getSource().getEntity() instanceof GettPet pet && pet.ownerId() != null
                && event.getEntity().level().getServer() != null) {
            // A pet's kill counts for its owner.
            ServerPlayer owner = event.getEntity().level().getServer().getPlayerList().getPlayer(pet.ownerId());
            if (owner != null && owner != event.getEntity()) {
                QuestService.onKill(owner, event.getEntity());
            }
        }
    }

    @SubscribeEvent
    public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            QuestService.requestRefresh(player);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        QuestService.clearAll();
    }
}
