package org.gettserver.gettcore.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.gettserver.gettcore.economy.ClientBalance;
import org.gettserver.gettcore.economy.ClientConfig;
import org.gettserver.gettcore.enchant.AbilityKey;
import org.gettserver.gettcore.enchant.AbilityToggle;
import org.gettserver.gettcore.enchant.ClientEnchantConfig;
import org.gettserver.gettcore.enchant.EnchantScreen;
import org.gettserver.gettcore.editor.EditorServerService;
import org.gettserver.gettcore.editor.client.EditorClient;
import org.gettserver.gettcore.enchant.EnchantService;
import org.gettserver.gettcore.item.ClientReturnCasts;
import org.gettserver.gettcore.npc.DialogClient;
import org.gettserver.gettcore.npc.ClientNpcMappings;
import org.gettserver.gettcore.npc.DialogSession;
import org.gettserver.gettcore.quest.QuestService;
import org.gettserver.gettcore.shop.ClientShopCache;
import org.gettserver.gettcore.shop.ShopService;

public final class GettNetwork {
    private GettNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(BalanceSyncPayload.TYPE, BalanceSyncPayload.STREAM_CODEC,
                (payload, ctx) -> ClientBalance.set(payload.balance()));
        registrar.playToClient(ConfigSyncPayload.TYPE, ConfigSyncPayload.STREAM_CODEC,
                (payload, ctx) -> ClientConfig.set(payload.hudOffsetX(), payload.hudOffsetY(), payload.currencyName()));
        registrar.playToClient(EnchantConfigSyncPayload.TYPE, EnchantConfigSyncPayload.STREAM_CODEC,
                (payload, ctx) -> ClientEnchantConfig.set(payload.config()));
        registrar.playToClient(NpcMappingSyncPayload.TYPE, NpcMappingSyncPayload.STREAM_CODEC,
                (payload, ctx) -> ClientNpcMappings.set(payload.byUuid(), payload.byName()));
        registrar.playToClient(ShopSyncPayload.TYPE, ShopSyncPayload.STREAM_CODEC,
                (payload, ctx) -> ClientShopCache.set(payload.shopId(), payload.definition(), payload.dynamicsEnabled()));
        registrar.playToClient(ReturnCastPayload.TYPE, ReturnCastPayload.STREAM_CODEC,
                (payload, ctx) -> ClientReturnCasts.set(payload.entityId(), payload.active(),
                        ctx.player().level().getGameTime()));
        // DialogClient is only resolved when this client-bound handler runs, so the server never loads it.
        registrar.playToClient(OpenDialogPayload.TYPE, OpenDialogPayload.STREAM_CODEC,
                (payload, ctx) -> DialogClient.open(payload));
        // Same for EnchantScreen (client-only): referenced only inside the client-bound handler.
        registrar.playToClient(EnchantResultPayload.TYPE, EnchantResultPayload.STREAM_CODEC,
                (payload, ctx) -> EnchantScreen.onResult(payload.abilityId(), payload.level()));
        // AbilityKey (client-only) likewise lives only inside the client-bound handler.
        registrar.playToClient(AbilityStatePayload.TYPE, AbilityStatePayload.STREAM_CODEC,
                (payload, ctx) -> AbilityKey.setState(payload.enabled()));
        // EditorClient (client-only) is referenced only inside the client-bound handler.
        registrar.playToClient(EditorDataPayload.TYPE, EditorDataPayload.STREAM_CODEC,
                (payload, ctx) -> EditorClient.onData(payload));
        // Quest UI payloads: the client screens/HUD come in a later task (Task 13); until then the client ignores them.
        registrar.playToClient(QuestSyncPayload.TYPE, QuestSyncPayload.STREAM_CODEC, (payload, ctx) -> {});
        registrar.playToClient(QuestDetailPayload.TYPE, QuestDetailPayload.STREAM_CODEC, (payload, ctx) -> {});
        registrar.playToClient(QuestNotifyPayload.TYPE, QuestNotifyPayload.STREAM_CODEC, (payload, ctx) -> {});
        registrar.playToServer(QuestActionPayload.TYPE, QuestActionPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> QuestService.onAction((ServerPlayer) ctx.player(), payload)));
        registrar.playToServer(EditorSaveChunkPayload.TYPE, EditorSaveChunkPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(
                        () -> EditorServerService.onChunk((ServerPlayer) ctx.player(), payload)));
        registrar.playToServer(DialogChoicePayload.TYPE, DialogChoicePayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(
                        () -> DialogSession.choose((ServerPlayer) ctx.player(), payload.choiceIndex())));
        registrar.playToServer(TradePayload.TYPE, TradePayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> ShopService.trade((ServerPlayer) ctx.player(), payload)));
        registrar.playToServer(ShopEditPayload.TYPE, ShopEditPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> ShopService.edit((ServerPlayer) ctx.player(), payload)));
        registrar.playToServer(EnchantApplyPayload.TYPE, EnchantApplyPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> EnchantService.apply((ServerPlayer) ctx.player())));
        registrar.playToServer(AbilityTogglePayload.TYPE, AbilityTogglePayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> AbilityToggle.toggle((ServerPlayer) ctx.player())));
    }
}
