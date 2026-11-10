package org.gettserver.gettcore.quest;

import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
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

    /** Who hit which mob recently (spec §5.3 함께 잡기). Server thread only. */
    private static final KillCredit KILL_CREDIT = new KillCredit();
    private static final int PRUNE_INTERVAL_TICKS = 200;

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
        if (event.getServer().getTickCount() % PRUNE_INTERVAL_TICKS == 0) {
            KILL_CREDIT.prune(System.currentTimeMillis());
        }
    }

    /** The player credited for damage dealt by {@code attacker}: the player, or the owner of a pet; else null. */
    @Nullable
    private static UUID creditOf(@Nullable Entity attacker) {
        if (attacker instanceof ServerPlayer p) return p.getUUID();
        if (attacker instanceof GettPet pet) return pet.ownerId();
        return null;
    }

    /** Records real damage (after armor etc.) by a player or a player's pet; projectiles count for the shooter. */
    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Post event) {
        if (event.getEntity().level().isClientSide() || event.getNewDamage() <= 0) return;
        UUID who = creditOf(event.getSource().getEntity());
        if (who != null && !who.equals(event.getEntity().getUUID())) {
            KILL_CREDIT.hit(event.getEntity().getId(), who, System.currentTimeMillis());
        }
    }

    /**
     * LOWEST so other mods' cancellations are already applied; canceled deaths are not received. Every online player
     * who hit the mob within {@link KillCredit#WINDOW_MS}, plus the killer (a pet's kill counts for its owner), gets
     * the kill once.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        Entity victim = event.getEntity();
        MinecraftServer server = victim.level().getServer();
        if (victim.level().isClientSide() || server == null) return;
        UUID killer = creditOf(event.getSource().getEntity());
        for (UUID id : KILL_CREDIT.onDeath(victim.getId(), killer, System.currentTimeMillis())) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null && p != victim) QuestService.onKill(p, victim);
        }
    }

    @SubscribeEvent
    public static void onLeaveLevel(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) KILL_CREDIT.forget(event.getEntity().getId());
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
        KILL_CREDIT.prune(Long.MAX_VALUE); // drops every entry
    }
}
