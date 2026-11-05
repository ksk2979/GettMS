package org.gettserver.gettcore.npc;

import de.markusbordihn.easynpc.entity.easynpc.EasyNPC;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;

@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class NpcInteractHandler {
    private NpcInteractHandler() {}

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        // Sneak+right-click is left to Easy NPC so owners can still open its config screen.
        if (event.getEntity().isShiftKeyDown()) {
            return;
        }
        Entity target = event.getTarget();
        if (!(target instanceof EasyNPC<?> npc)) {
            return;
        }
        // The client has no GettData on a dedicated server, so it uses the synced mapping cache.
        var dialogId = event.getLevel().isClientSide()
                ? ClientNpcMappings.dialogForNpc(target.getUUID(), target.getName().getString())
                : GettData.get().dialogForNpc(target.getUUID(), target.getName().getString());
        if (dialogId.isEmpty()) {
            return;
        }
        // Cancel on both sides so Easy NPC's own mobInteract (default dialog) never runs.
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof ServerPlayer player) {
            GettData.get().dialog(dialogId.get()).ifPresent(def -> DialogSession.open(player, npc, def));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DialogSession.forget(player);
        }
    }
}
