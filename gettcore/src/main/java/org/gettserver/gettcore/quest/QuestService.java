package org.gettserver.gettcore.quest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.economy.EconomyData;
import org.gettserver.gettcore.economy.Gold;
import org.gettserver.gettcore.economy.Ledger;
import org.gettserver.gettcore.network.QuestActionPayload;
import org.gettserver.gettcore.network.QuestDetailPayload;
import org.gettserver.gettcore.network.QuestDetailPayload.Mode;
import org.gettserver.gettcore.network.QuestNotifyPayload;
import org.gettserver.gettcore.network.QuestSyncPayload;

/**
 * Server side of the quest system: judges progress with {@link QuestEngine}, applies rewards, talks to the client.
 * Only the {@value #MAIN_LINE} line is auto-advanced and synced to the HUD (spec §5.2). Server thread only.
 */
public final class QuestService {
    public static final String MAIN_LINE = "main";
    /** A quest screen's accept/report button stays valid this long after the NPC showed it. */
    private static final long ACTION_WINDOW_MS = 60_000;
    private static final double NPC_REACH_SQR = 8.0 * 8.0;

    /** The NPC that last showed an OFFER/REPORT screen to a player (the dialog is closed by then). */
    private record Pending(int entityId, String npcName, String lineId, String questId, long expiresAt) {}

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, QuestSyncPayload> LAST_SENT = new HashMap<>();
    private static final Set<UUID> REFRESH_REQUESTS = new LinkedHashSet<>();

    private QuestService() {}

    public static Optional<QuestLine> mainLine() {
        return GettData.get().questLine(MAIN_LINE);
    }

    // ---- judging ----

    /** Applies automatic transitions, handles their events and sends a sync if anything the HUD shows changed. */
    public static void refresh(ServerPlayer player) {
        if (!player.isAlive()) { // no rewards or advancing while dead (items would drop into the death pile)
            sync(player);
            return;
        }
        Optional<QuestLine> line = mainLine();
        if (line.isPresent()) {
            QuestData data = QuestData.get(player.server);
            QuestProgress progress = data.progress(player.getUUID(), MAIN_LINE);
            List<QuestEvent> events = QuestEngine.advance(line.get(), progress, new ServerPlayerFacts(player));
            if (!events.isEmpty()) {
                data.setDirty();
                handle(player, events, false);
            }
        }
        sync(player);
    }

    public static void refreshAll(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            try {
                refresh(p);
            } catch (RuntimeException e) {
                GettCore.LOGGER.error("gettcore: quest refresh failed for {}", p.getGameProfile().getName(), e);
            }
        }
    }

    /** Defers a refresh to the next server tick (used from events fired in the middle of other game logic). */
    public static void requestRefresh(ServerPlayer player) {
        REFRESH_REQUESTS.add(player.getUUID());
    }

    /** Server tick: runs deferred refreshes, and refreshes each player once every 20 ticks (staggered). */
    static void tick(MinecraftServer server) {
        if (!REFRESH_REQUESTS.isEmpty()) {
            List<UUID> ids = new ArrayList<>(REFRESH_REQUESTS);
            REFRESH_REQUESTS.clear();
            for (UUID id : ids) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null) safeRefresh(p);
            }
        }
        int tick = server.getTickCount();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (Math.floorMod(tick + p.getId(), 20) == 0) safeRefresh(p);
        }
    }

    private static void safeRefresh(ServerPlayer p) {
        try {
            refresh(p);
        } catch (RuntimeException e) {
            GettCore.LOGGER.error("gettcore: quest refresh failed for {}", p.getGameProfile().getName(), e);
        }
    }

    /** A player killed {@code victim}: counts kill objectives of the accepted main quest. */
    public static void onKill(ServerPlayer player, Entity victim) {
        Optional<QuestLine> line = mainLine();
        if (line.isEmpty()) return;
        QuestData data = QuestData.get(player.server);
        QuestProgress progress = data.progress(player.getUUID(), MAIN_LINE);
        if (QuestEngine.onKill(line.get(), progress, ServerPlayerFacts.killMatcher(victim))) {
            data.setDirty();
            requestRefresh(player);
        }
    }

    /** Called when a dialog with an NPC opens, before choices are built: records talk objectives and refreshes. */
    public static void onDialogOpen(ServerPlayer player, String npcName) {
        try {
            Optional<QuestLine> line = mainLine();
            if (line.isPresent()) {
                QuestData data = QuestData.get(player.server);
                if (QuestEngine.onTalk(line.get(), data.progress(player.getUUID(), MAIN_LINE), npcName)) {
                    data.setDirty();
                }
            }
            refresh(player);
        } catch (RuntimeException e) {
            // Never let a quest problem stop the NPC dialog from opening.
            GettCore.LOGGER.error("gettcore: quest talk handling failed for {}", player.getGameProfile().getName(), e);
        }
    }

    /** Label of the main quest choice to put on top of this NPC's start node, if the current quest needs this NPC. */
    public static Optional<String> injectedChoice(ServerPlayer player, String npcName) {
        try {
            Optional<QuestLine> line = mainLine();
            if (line.isEmpty()) return Optional.empty();
            QuestProgress progress = QuestData.get(player.server).progress(player.getUUID(), MAIN_LINE);
            return QuestDialogs.injectedLabel(
                    QuestEngine.status(line.get(), progress, new ServerPlayerFacts(player)), npcName);
        } catch (RuntimeException e) {
            GettCore.LOGGER.error("gettcore: quest choice check failed for {}", player.getGameProfile().getName(), e);
            return Optional.empty();
        }
    }

    /** True for a player who has never completed nor accepted a main quest (login welcome). */
    public static boolean isFresh(ServerPlayer player) {
        QuestProgress p = QuestData.get(player.server).progress(player.getUUID(), MAIN_LINE);
        return p.completed().isEmpty() && p.accepted().isEmpty();
    }

    // ---- quest screens ----

    /**
     * A quest choice was picked in the dialog of NPC {@code entityId}/{@code npcName} (dialog already closed): sends the
     * quest screen of the current quest of {@code lineId}: OFFER/REPORT when this NPC starts/completes it, else VIEW.
     */
    public static void openDetail(ServerPlayer player, int entityId, String npcName, String lineId) {
        Optional<QuestLine> line = GettData.get().questLine(lineId);
        if (line.isEmpty()) {
            GettCore.LOGGER.warn("gettcore: quest dialog choice for unknown line {}", lineId);
            player.sendSystemMessage(Component.translatable("gettcore.quest.no_line", lineId));
            return;
        }
        QuestProgress progress = QuestData.get(player.server).progress(player.getUUID(), lineId);
        QuestStatus status = QuestEngine.status(line.get(), progress, new ServerPlayerFacts(player));
        Optional<Mode> mode = QuestDialogs.detailMode(status, npcName);
        if (mode.isEmpty()) {
            player.sendSystemMessage(Component.literal(line.get().endText()).withStyle(ChatFormatting.YELLOW));
            return;
        }
        Quest q = status.quest().orElseThrow();
        if (mode.get() == Mode.VIEW) {
            PENDING.remove(player.getUUID());
        } else {
            PENDING.put(player.getUUID(), new Pending(entityId, npcName, lineId, q.id(),
                    System.currentTimeMillis() + ACTION_WINDOW_MS));
        }
        PacketDistributor.sendToPlayer(player, detail(q, QuestViews.objStates(status.objectives()), mode.get()));
    }

    /** Read-only screen of any main-line quest (quest list): live rows for the current one, else done/locked rows. */
    private static void sendView(ServerPlayer player, String questId) {
        Optional<QuestLine> line = mainLine();
        if (line.isEmpty()) return;
        Quest q = line.get().quests().stream().filter(x -> x.id().equals(questId)).findFirst().orElse(null);
        if (q == null) return;
        QuestProgress progress = QuestData.get(player.server).progress(player.getUUID(), MAIN_LINE);
        QuestStatus status = QuestEngine.status(line.get(), progress, new ServerPlayerFacts(player));
        List<QuestSyncPayload.ObjState> rows;
        if (status.quest().map(c -> c.id().equals(questId)).orElse(false)) {
            rows = QuestViews.objStates(status.objectives());
        } else {
            rows = QuestViews.staticStates(q, progress.completed().contains(questId));
        }
        PacketDistributor.sendToPlayer(player, detail(q, rows, Mode.VIEW));
    }

    private static QuestDetailPayload detail(Quest q, List<QuestSyncPayload.ObjState> rows, Mode mode) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemReward r : q.rewards().items()) {
            Item item = BuiltInRegistries.ITEM.get(r.item());
            if (item != Items.AIR) items.add(new ItemStack(item, r.count()));
        }
        return new QuestDetailPayload(q.id(), q.title(), q.description(), q.completeText(), rows, q.rewards().gold(),
                List.copyOf(items), q.rewards().xp(), mode);
    }

    /** Accept/report button of a quest screen, or a VIEW request from the quest list. */
    public static void onAction(ServerPlayer player, QuestActionPayload payload) {
        if (payload.action() == QuestActionPayload.Action.VIEW) {
            sendView(player, payload.questId());
            return;
        }
        if (!player.isAlive()) return;
        UUID uuid = player.getUUID();
        Pending pend = PENDING.get(uuid);
        if (pend == null && alreadyCompleted(player, payload.questId())) return; // e.g. a double click on report
        if (pend == null || System.currentTimeMillis() > pend.expiresAt() || !pend.questId().equals(payload.questId())) {
            PENDING.remove(uuid);
            player.sendSystemMessage(Component.translatable("gettcore.quest.expired").withStyle(ChatFormatting.RED));
            return;
        }
        Entity npc = player.serverLevel().getEntity(pend.entityId());
        if (npc == null || !npc.isAlive() || player.distanceToSqr(npc) > NPC_REACH_SQR) {
            player.sendSystemMessage(Component.translatable("gettcore.quest.too_far").withStyle(ChatFormatting.RED));
            return;
        }
        PENDING.remove(uuid);
        Optional<QuestLine> line = GettData.get().questLine(pend.lineId());
        if (line.isEmpty()) return;
        QuestData data = QuestData.get(player.server);
        QuestProgress progress = data.progress(uuid, pend.lineId());
        ServerPlayerFacts facts = new ServerPlayerFacts(player);
        QuestStatus status = QuestEngine.status(line.get(), progress, facts);
        QuestStatus.Phase expected = payload.action() == QuestActionPayload.Action.ACCEPT
                ? QuestStatus.Phase.WAIT_START : QuestStatus.Phase.READY_TO_REPORT;
        boolean sameQuest = status.quest().map(q -> q.id().equals(payload.questId())).orElse(false);
        List<QuestEvent> events = new ArrayList<>();
        if (sameQuest && status.phase() == expected) {
            events.addAll(QuestEngine.interact(line.get(), progress, facts, pend.npcName()));
            // Same visit counts as talking to this NPC (e.g. the first quest: start at 이장 + talk to 이장).
            if (QuestEngine.onTalk(line.get(), progress, pend.npcName())) {
                events.addAll(QuestEngine.advance(line.get(), progress, facts));
            }
        }
        if (events.isEmpty()) {
            player.sendSystemMessage(Component.translatable("gettcore.quest.not_ready").withStyle(ChatFormatting.RED));
        } else {
            data.setDirty();
            handle(player, events, true);
        }
        if (MAIN_LINE.equals(pend.lineId())) sync(player);
    }

    private static boolean alreadyCompleted(ServerPlayer player, String questId) {
        QuestData data = QuestData.get(player.server);
        for (QuestLine l : GettData.get().allQuestLines()) {
            if (l.quests().stream().anyMatch(q -> q.id().equals(questId))
                    && data.progress(player.getUUID(), l.id()).completed().contains(questId)) {
                return true;
            }
        }
        return false;
    }

    // ---- events, rewards ----

    /**
     * Notifies and rewards. {@code consume}: remove consume=true collect items of npc-complete quests (only on a real
     * report, never for auto completion or admin completion).
     */
    private static void handle(ServerPlayer player, List<QuestEvent> events, boolean consume) {
        for (QuestEvent e : events) {
            Quest q = e.quest();
            switch (e.kind()) {
                case ACCEPTED -> PacketDistributor.sendToPlayer(player,
                        new QuestNotifyPayload(QuestNotifyPayload.Kind.ACCEPTED, q.title(), 0));
                case COMPLETED -> {
                    if (consume && q.complete().type() == QuestTrigger.Type.NPC) consumeItems(player, q);
                    long paid = giveRewards(player, q);
                    PacketDistributor.sendToPlayer(player,
                            new QuestNotifyPayload(QuestNotifyPayload.Kind.COMPLETED, q.title(), paid));
                    player.sendSystemMessage(Component.translatable("gettcore.quest.completed", q.title())
                            .withStyle(ChatFormatting.GOLD));
                    GettCore.LOGGER.info("gettcore: {} completed quest {}", player.getGameProfile().getName(), q.id());
                }
            }
        }
    }

    private static void consumeItems(ServerPlayer player, Quest q) {
        ServerPlayerFacts facts = new ServerPlayerFacts(player);
        for (Objective o : q.objectives()) {
            if (o.type() == Objective.Type.COLLECT && o.consume() && !facts.removeItems(o.target(), o.count())) {
                GettCore.LOGGER.warn("gettcore: {} reported {} without {} x{} (not consumed)",
                        player.getGameProfile().getName(), q.id(), o.target(), o.count());
            }
        }
    }

    /** Returns the gold actually deposited (0 when the deposit failed; the player is told in chat). */
    private static long giveRewards(ServerPlayer player, Quest q) {
        Rewards r = q.rewards();
        long paid = 0;
        if (r.gold() > 0) {
            EconomyData econ = EconomyData.get(player.server);
            if (econ.ledger().deposit(player.getUUID(), r.gold()) == Ledger.Result.OK) {
                econ.changed(player.getUUID());
                paid = r.gold();
            } else {
                GettCore.LOGGER.warn("gettcore: quest {} gold {} could not be paid to {}", q.id(),
                        Gold.format(r.gold()), player.getGameProfile().getName());
                player.sendSystemMessage(Component.translatable("gettcore.quest.gold_failed", Gold.format(r.gold()))
                        .withStyle(ChatFormatting.RED));
            }
        }
        boolean dropped = false;
        for (ItemReward ir : r.items()) {
            Item item = BuiltInRegistries.ITEM.get(ir.item());
            if (item == Items.AIR) continue;
            int max = new ItemStack(item).getMaxStackSize();
            int left = ir.count();
            while (left > 0) {
                int n = Math.min(left, max);
                left -= n;
                ItemStack stack = new ItemStack(item, n);
                player.getInventory().add(stack);
                if (!stack.isEmpty()) {
                    player.drop(stack, false);
                    dropped = true;
                }
            }
        }
        if (dropped) {
            player.sendSystemMessage(Component.translatable("gettcore.quest.reward_dropped")
                    .withStyle(ChatFormatting.YELLOW));
        }
        if (r.xp() > 0) player.giveExperienceLevels(r.xp());
        return paid;
    }

    // ---- sync ----

    /** Sends the HUD payload when it differs from the last one sent to this player. */
    public static void sync(ServerPlayer player) {
        QuestSyncPayload payload = mainLine().map(line -> {
            QuestProgress progress = QuestData.get(player.server).progress(player.getUUID(), MAIN_LINE);
            return QuestViews.sync(line, progress,
                    QuestEngine.status(line, progress, new ServerPlayerFacts(player)));
        }).orElseGet(QuestSyncPayload::empty);
        if (payload.equals(LAST_SENT.get(player.getUUID()))) return;
        LAST_SENT.put(player.getUUID(), payload);
        PacketDistributor.sendToPlayer(player, payload);
    }

    // ---- admin ----

    /** Deletes all quest progress of the player and re-judges from the start. */
    public static void adminReset(ServerPlayer player) {
        QuestData.get(player.server).reset(player.getUUID());
        PENDING.remove(player.getUUID());
        refresh(player);
    }

    /** Completes the current main quest with rewards (accepted or not). Returns it, or empty when the line is done. */
    public static Optional<Quest> adminComplete(ServerPlayer player) {
        Optional<QuestLine> line = mainLine();
        if (line.isEmpty()) return Optional.empty();
        QuestData data = QuestData.get(player.server);
        QuestProgress progress = data.progress(player.getUUID(), MAIN_LINE);
        ServerPlayerFacts facts = new ServerPlayerFacts(player);
        Optional<Quest> cur = QuestEngine.current(line.get(), progress);
        if (cur.isEmpty()) return Optional.empty();
        List<QuestEvent> events = QuestEngine.forceComplete(line.get(), progress, facts);
        data.setDirty();
        PENDING.remove(player.getUUID());
        handle(player, events, false);
        sync(player);
        return cur;
    }

    /** Marks every main quest before {@code questId} completed (no rewards). False when the id is unknown. */
    public static boolean adminGoto(ServerPlayer player, String questId) {
        Optional<QuestLine> line = mainLine();
        if (line.isEmpty()) return false;
        QuestData data = QuestData.get(player.server);
        if (!QuestEngine.jumpTo(line.get(), data.progress(player.getUUID(), MAIN_LINE), questId)) return false;
        data.setDirty();
        PENDING.remove(player.getUUID());
        refresh(player);
        return true;
    }

    // ---- lifecycle ----

    /** Drops per-player state (login/logout) so the next sync is sent in full. */
    public static void forget(UUID player) {
        PENDING.remove(player);
        LAST_SENT.remove(player);
        REFRESH_REQUESTS.remove(player);
    }

    static void clearAll() {
        PENDING.clear();
        LAST_SENT.clear();
        REFRESH_REQUESTS.clear();
    }
}
