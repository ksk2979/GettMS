package org.gettserver.gettcore.npc;

import de.markusbordihn.easynpc.entity.easynpc.EasyNPC;
import net.minecraft.core.registries.BuiltInRegistries;
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
        // Easy NPC 지팡이/아이템(easy_npc* 네임스페이스)을 들고 클릭하면 Easy NPC 설정 쪽에 맡긴다.
        var held = BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem());
        if (held.getNamespace().startsWith("easy_npc")) {
            return;
        }
        Entity target = event.getTarget();
        if (!(target instanceof EasyNPC<?> npc)) {
            return;
        }
        // Never cancel on the client: a client-side cancel stops the interact packet from being sent,
        // so the server would never see the click. Easy NPC's dialog is server-driven anyway.
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        var dialogId = GettData.get().dialogForNpc(target.getUUID(), target.getName().getString());
        if (dialogId.isEmpty()) {
            return;
        }
        // Cancel on the server so Easy NPC's own mobInteract (default dialog) never runs.
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        GettData.get().dialog(dialogId.get()).ifPresent(def -> DialogSession.open(player, npc, def));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DialogSession.forget(player);
        }
    }
}
