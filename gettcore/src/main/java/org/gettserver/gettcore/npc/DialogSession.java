package org.gettserver.gettcore.npc;

import de.markusbordihn.easynpc.entity.easynpc.EasyNPC;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.ChoiceAction;
import org.gettserver.gettcore.data.DialogChoice;
import org.gettserver.gettcore.data.DialogDefinition;
import org.gettserver.gettcore.data.DialogNode;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.enchant.EnchantService;
import org.gettserver.gettcore.reinforce.ReinforceService;
import org.gettserver.gettcore.network.OpenDialogPayload;
import org.gettserver.gettcore.quest.QuestDialogs;
import org.gettserver.gettcore.quest.QuestService;
import org.gettserver.gettcore.shop.ShopMode;
import org.gettserver.gettcore.shop.ShopService;

/** Server-side dialog state. Only the server decides transitions; clients just send a choice index. */
public final class DialogSession {
    private DialogSession() {}

    /**
     * {@code questInjected}: the node was sent with the main quest choice at index 0, so client indices are shifted by
     * one (see {@link QuestDialogs#resolve}).
     */
    public record Session(String dialogId, String nodeId, int npcEntityId, String npcName, boolean questInjected) {}

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    public static void open(ServerPlayer player, EasyNPC<?> npc, DialogDefinition def) {
        Entity entity = (Entity) npc;
        DialogNode start = def.nodes().get(def.start());
        if (start == null) {
            GettCore.LOGGER.warn("Dialog {} has no start node {}", def.id(), def.start());
            return;
        }
        String npcName = entity.getName().getString();
        // Talk objectives count as soon as the dialog opens; refresh before deciding which quest choice to show.
        QuestService.onDialogOpen(player, npcName);
        send(player, def.id(), def.start(), entity.getId(), npcName, def, start);
    }

    public static void choose(ServerPlayer player, int index) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return;
        }
        if (index == -1) {
            SESSIONS.remove(player.getUUID());
            return;
        }
        Optional<DialogDefinition> def = GettData.get().dialog(session.dialogId());
        DialogNode node = def.map(d -> d.nodes().get(session.nodeId())).orElse(null);
        if (node == null) {
            close(player);
            return;
        }
        QuestDialogs.Choice choice = QuestDialogs.resolve(session.questInjected(), index, node.choices().size());
        if (choice.quest()) {
            openQuest(player, session, QuestService.MAIN_LINE);
            return;
        }
        if (!choice.valid()) {
            return;
        }
        ChoiceAction action = node.choices().get(choice.nodeIndex()).action();
        switch (action.type()) {
            case NEXT -> {
                String targetId = action.target().orElse(null);
                DialogNode next = targetId == null ? null : def.get().nodes().get(targetId);
                if (next == null) {
                    close(player);
                } else {
                    send(player, session.dialogId(), targetId, session.npcEntityId(), session.npcName(), def.get(),
                            next);
                }
            }
            case OPEN_SHOP_BUY -> openShop(player, action, ShopMode.BUY);
            case OPEN_SHOP_SELL -> openShop(player, action, ShopMode.SELL);
            case CLOSE -> close(player);
            case OPEN_ENCHANT -> {
                close(player);
                EnchantService.open(player);
            }
            case OPEN_REINFORCE -> {
                close(player);
                ReinforceService.open(player);
            }
            case QUEST -> openQuest(player, session, action.target().orElse(QuestService.MAIN_LINE));
        }
    }

    /** Closes the dialog and shows the quest screen of {@code lineId} for this NPC. */
    private static void openQuest(ServerPlayer player, Session session, String lineId) {
        close(player);
        QuestService.openDetail(player, session.npcEntityId(), session.npcName(), lineId);
    }

    private static void openShop(ServerPlayer player, ChoiceAction action, ShopMode mode) {
        String shopId = action.target().orElse(null);
        close(player);
        if (shopId == null) {
            GettCore.LOGGER.warn("Dialog shop action without target");
            return;
        }
        ShopService.open(player, shopId, mode);
    }

    /** Ends the session and tells the client to close its dialog screen (empty choices = close). */
    public static void close(ServerPlayer player) {
        Session s = SESSIONS.remove(player.getUUID());
        if (s != null) {
            PacketDistributor.sendToPlayer(player,
                    new OpenDialogPayload(s.npcEntityId(), "", Optional.empty(), "", List.of()));
        }
    }

    /** Drops state without notifying (logout). */
    public static void forget(ServerPlayer player) {
        SESSIONS.remove(player.getUUID());
    }

    private static void send(ServerPlayer player, String dialogId, String nodeId, int npcEntityId, String npcName,
                             DialogDefinition def, DialogNode node) {
        // The main quest choice goes on top of the start node only.
        Optional<String> questLabel = nodeId.equals(def.start())
                ? QuestService.injectedChoice(player, npcName) : Optional.empty();
        Session session = new Session(dialogId, nodeId, npcEntityId, npcName, questLabel.isPresent());
        SESSIONS.put(player.getUUID(), session);
        List<String> labels = new ArrayList<>(node.choices().size() + 1);
        questLabel.ifPresent(labels::add);
        node.choices().stream().map(DialogChoice::label).forEach(labels::add);
        if (labels.isEmpty()) {
            // Client treats empty choices as "close"; a node with no choices would be a dead end.
            close(player);
            return;
        }
        PacketDistributor.sendToPlayer(player, new OpenDialogPayload(session.npcEntityId(), def.name(),
                def.portrait(), node.text(), labels));
    }
}
