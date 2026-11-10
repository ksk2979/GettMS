package org.gettserver.gettcore.quest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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
 * Judged lines (spec §5.2): the {@value #MAIN_LINE} line plus every sub line the player started or unlocked
 * ({@link QuestLines#judged}); other non-sub lines are only opened through explicit dialog actions, as before.
 * Server thread only.
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

    private static Function<String, QuestProgress> progressOf(QuestData data, UUID player) {
        return id -> data.progress(player, id);
    }

    /** Lines judged for this player, in {@link QuestLines#judged} order (main first, then sub lines by id). */
    private static List<QuestLine> judgedLines(QuestData data, UUID player) {
        return QuestLines.judged(GettData.get().allQuestLines(), progressOf(data, player)).stream()
                .filter(l -> l.isSub() || MAIN_LINE.equals(l.id()))
                .toList();
    }

    // ---- judging ----

    /** Applies automatic transitions, handles their events and sends a sync if anything the HUD shows changed. */
    public static void refresh(ServerPlayer player) {
        if (!player.isAlive()) { // no rewards or advancing while dead (items would drop into the death pile)
            sync(player);
            return;
        }
        QuestData data = QuestData.get(player.server);
        ServerPlayerFacts facts = new ServerPlayerFacts(player);
        for (QuestLine line : judgedLines(data, player.getUUID())) {
            QuestProgress progress = data.progress(player.getUUID(), line.id());
            List<QuestEvent> events = QuestEngine.advance(line, progress, facts);
            if (!events.isEmpty()) {
                data.setDirty();
                handle(player, line, events, "", 0);
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

    /** {@code player} gets credit for killing {@code victim}: counts kill objectives of every judged line. */
    public static void onKill(ServerPlayer player, Entity victim) {
        QuestData data = QuestData.get(player.server);
        UUID id = player.getUUID();
        if (QuestLines.applyKill(judgedLines(data, id), progressOf(data, id), ServerPlayerFacts.killMatcher(victim))) {
            data.setDirty();
            requestRefresh(player);
        }
    }

    /** Called when a dialog with an NPC opens, before choices are built: records talk objectives and refreshes. */
    public static void onDialogOpen(ServerPlayer player, String npcName) {
        try {
            QuestData data = QuestData.get(player.server);
            for (QuestLine line : judgedLines(data, player.getUUID())) {
                if (QuestEngine.onTalk(line, data.progress(player.getUUID(), line.id()), npcName)) {
                    data.setDirty();
                }
            }
            refresh(player);
        } catch (RuntimeException e) {
            // Never let a quest problem stop the NPC dialog from opening.
            GettCore.LOGGER.error("gettcore: quest talk handling failed for {}", player.getGameProfile().getName(), e);
        }
    }

    /**
     * Quest choices to put on top of this NPC's start node, one per judged line whose current quest this NPC starts
     * (not accepted yet) or takes the report of (objectives done). Main first, then sub lines.
     */
    public static List<QuestDialogs.Injected> injectedChoices(ServerPlayer player, String npcName) {
        try {
            QuestData data = QuestData.get(player.server);
            ServerPlayerFacts facts = new ServerPlayerFacts(player);
            List<QuestDialogs.Injected> out = new ArrayList<>();
            for (QuestLine line : judgedLines(data, player.getUUID())) {
                QuestStatus status = QuestEngine.status(line, data.progress(player.getUUID(), line.id()), facts);
                QuestDialogs.injectedLabel(status, npcName, line.isSub())
                        .ifPresent(label -> out.add(new QuestDialogs.Injected(line.id(), label)));
            }
            return out;
        } catch (RuntimeException e) {
            GettCore.LOGGER.error("gettcore: quest choice check failed for {}", player.getGameProfile().getName(), e);
            return List.of();
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
        QuestData data = QuestData.get(player.server);
        QuestProgress progress = data.progress(player.getUUID(), lineId);
        if (line.get().isSub() && !QuestLines.started(progress)
                && !QuestLines.requirementMet(line.get(), progressOf(data, player.getUUID()))) {
            player.sendSystemMessage(Component.translatable("gettcore.quest.not_ready").withStyle(ChatFormatting.RED));
            return;
        }
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
        PacketDistributor.sendToPlayer(player, detail(lineId, q, QuestViews.objStates(status.objectives()),
                mode.get()));
    }

    /** Read-only screen of any quest of a line the player sees (quest list): live rows for the current one. */
    private static void sendView(ServerPlayer player, String lineId, String questId) {
        Optional<QuestLine> line = GettData.get().questLine(lineId);
        if (line.isEmpty()) return;
        Quest q = line.get().quests().stream().filter(x -> x.id().equals(questId)).findFirst().orElse(null);
        if (q == null) return;
        QuestProgress progress = QuestData.get(player.server).progress(player.getUUID(), lineId);
        if (line.get().isSub() && !QuestLines.started(progress)) return; // not on the player's list yet
        QuestStatus status = QuestEngine.status(line.get(), progress, new ServerPlayerFacts(player));
        List<QuestSyncPayload.ObjState> rows;
        if (status.quest().map(c -> c.id().equals(questId)).orElse(false)) {
            rows = QuestViews.objStates(status.objectives());
        } else {
            rows = QuestViews.staticStates(q, progress.completed().contains(questId));
        }
        PacketDistributor.sendToPlayer(player, detail(lineId, q, rows, Mode.VIEW));
    }

    private static QuestDetailPayload detail(String lineId, Quest q, List<QuestSyncPayload.ObjState> rows,
                                             Mode mode) {
        return new QuestDetailPayload(lineId, q.id(), q.title(), q.description(), q.completeText(), rows,
                q.rewards().gold(), stacks(q.rewards().items()), stacks(q.choices()), q.rewards().xp(), mode);
    }

    private static List<ItemStack> stacks(List<ItemReward> rewards) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemReward r : rewards) {
            Item item = BuiltInRegistries.ITEM.get(r.item());
            if (item != Items.AIR) out.add(new ItemStack(item, r.count()));
        }
        return List.copyOf(out);
    }

    /** Accept/report button of a quest screen, or a VIEW request from the quest list. */
    public static void onAction(ServerPlayer player, QuestActionPayload payload) {
        if (payload.action() == QuestActionPayload.Action.VIEW) {
            sendView(player, payload.lineId(), payload.questId());
            return;
        }
        if (!player.isAlive()) return;
        UUID uuid = player.getUUID();
        Pending pend = PENDING.get(uuid);
        if (pend == null && alreadyCompleted(player, payload.lineId(), payload.questId())) return; // double click
        if (pend == null || System.currentTimeMillis() > pend.expiresAt() || !pend.questId().equals(payload.questId())
                || !pend.lineId().equals(payload.lineId())) {
            PENDING.remove(uuid);
            player.sendSystemMessage(Component.translatable("gettcore.quest.expired").withStyle(ChatFormatting.RED));
            return;
        }
        Entity npc = player.serverLevel().getEntity(pend.entityId());
        if (npc == null || !npc.isAlive() || player.distanceToSqr(npc) > NPC_REACH_SQR) {
            player.sendSystemMessage(Component.translatable("gettcore.quest.too_far").withStyle(ChatFormatting.RED));
            return;
        }
        Optional<QuestLine> line = GettData.get().questLine(pend.lineId());
        if (line.isEmpty()) {
            PENDING.remove(uuid);
            return;
        }
        QuestData data = QuestData.get(player.server);
        QuestProgress progress = data.progress(uuid, pend.lineId());
        ServerPlayerFacts facts = new ServerPlayerFacts(player);
        // Re-judged now: e.g. another line's report may have taken the items this report needs (spec §5.2).
        QuestStatus status = QuestEngine.status(line.get(), progress, facts);
        boolean report = payload.action() == QuestActionPayload.Action.REPORT;
        QuestStatus.Phase expected = report ? QuestStatus.Phase.READY_TO_REPORT : QuestStatus.Phase.WAIT_START;
        boolean sameQuest = status.quest().map(q -> q.id().equals(payload.questId())).orElse(false);
        boolean ready = sameQuest && status.phase() == expected;
        if (ready && report && !QuestDialogs.validChoice(status.quest().get(), payload.choice())) {
            // Keep PENDING so the player can pick a reward and press again.
            player.sendSystemMessage(Component.translatable("gettcore.quest.choose_reward")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        PENDING.remove(uuid);
        List<QuestEvent> events = new ArrayList<>();
        if (ready) {
            events.addAll(QuestEngine.interact(line.get(), progress, facts, pend.npcName()));
            // Same visit counts as talking to this NPC (e.g. the first quest: start at 이장 + talk to 이장).
            if (QuestEngine.onTalk(line.get(), progress, pend.npcName())) {
                events.addAll(QuestEngine.advance(line.get(), progress, facts));
            }
        }
        if (events.isEmpty()) {
            boolean itemsGone = report && sameQuest && status.phase() == QuestStatus.Phase.ACTIVE;
            player.sendSystemMessage(Component.translatable(itemsGone ? "gettcore.quest.items_missing"
                    : "gettcore.quest.not_ready").withStyle(ChatFormatting.RED));
        } else {
            data.setDirty();
            handle(player, line.get(), events, report ? payload.questId() : "", payload.choice());
        }
        sync(player);
    }

    private static boolean alreadyCompleted(ServerPlayer player, String lineId, String questId) {
        if (GettData.get().questLine(lineId).isEmpty()) return false;
        return QuestData.get(player.server).progress(player.getUUID(), lineId).completed().contains(questId);
    }

    /** Shows or hides a sub line on the player's HUD (saved; progress continues). Ignored for unknown/main lines. */
    public static void setHidden(ServerPlayer player, String lineId, boolean hidden) {
        Optional<QuestLine> line = GettData.get().questLine(lineId);
        if (line.isEmpty() || !line.get().isSub()) return;
        QuestData data = QuestData.get(player.server);
        QuestPrefs prefs = data.prefs(player.getUUID());
        boolean changed = hidden ? prefs.hidden().add(lineId) : prefs.hidden().remove(lineId);
        if (changed) data.setDirty();
        sync(player);
    }

    // ---- events, rewards ----

    /**
     * Notifies and rewards the events of one line. {@code reportedQuestId}: the quest the player just reported at its
     * NPC ("" for none) — only that one consumes its consume=true collect items and gets reward {@code choice}; every
     * other completion (auto, admin) gets choice 0.
     */
    private static void handle(ServerPlayer player, QuestLine line, List<QuestEvent> events, String reportedQuestId,
                               int choice) {
        for (QuestEvent e : events) {
            Quest q = e.quest();
            switch (e.kind()) {
                case ACCEPTED -> {
                    if (line.isSub()) {
                        QuestData data = QuestData.get(player.server);
                        data.prefs(player.getUUID()).noteStarted(line.id());
                        data.setDirty();
                    }
                    PacketDistributor.sendToPlayer(player,
                            new QuestNotifyPayload(QuestNotifyPayload.Kind.ACCEPTED, q.title(), 0));
                }
                case COMPLETED -> {
                    boolean reported = q.id().equals(reportedQuestId);
                    if (reported && q.complete().type() == QuestTrigger.Type.NPC) consumeItems(player, q);
                    long paid = giveRewards(player, q, reported ? choice : 0);
                    PacketDistributor.sendToPlayer(player,
                            new QuestNotifyPayload(QuestNotifyPayload.Kind.COMPLETED, q.title(), paid));
                    player.sendSystemMessage(Component.translatable(
                                    line.isSub() ? "gettcore.quest.completed_sub" : "gettcore.quest.completed",
                                    q.title())
                            .withStyle(ChatFormatting.GOLD));
                    GettCore.LOGGER.info("gettcore: {} completed quest {} of {}", player.getGameProfile().getName(),
                            q.id(), line.id());
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

    /**
     * Gives the fixed rewards plus {@code q.choices().get(choice)} when {@code choice} is in range. Returns the gold
     * actually deposited (0 when the deposit failed; the player is told in chat).
     */
    private static long giveRewards(ServerPlayer player, Quest q, int choice) {
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
        for (ItemReward ir : r.items()) dropped |= giveItem(player, ir);
        if (choice >= 0 && choice < q.choices().size()) dropped |= giveItem(player, q.choices().get(choice));
        if (dropped) {
            player.sendSystemMessage(Component.translatable("gettcore.quest.reward_dropped")
                    .withStyle(ChatFormatting.YELLOW));
        }
        if (r.xp() > 0) player.giveExperienceLevels(r.xp());
        return paid;
    }

    /** Adds the stacks to the inventory, dropping what does not fit. True when something was dropped. */
    private static boolean giveItem(ServerPlayer player, ItemReward ir) {
        Item item = BuiltInRegistries.ITEM.get(ir.item());
        if (item == Items.AIR) return false;
        boolean dropped = false;
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
        return dropped;
    }

    // ---- sync ----

    /**
     * Sends the HUD payload when it differs from the last one sent to this player: the main line, then the started sub
     * lines in first-started order ({@link QuestPrefs#ordered}).
     */
    public static void sync(ServerPlayer player) {
        QuestData data = QuestData.get(player.server);
        UUID id = player.getUUID();
        ServerPlayerFacts facts = new ServerPlayerFacts(player);
        List<QuestSyncPayload.LineView> views = new ArrayList<>();
        mainLine().ifPresent(line -> views.add(view(line, data.progress(id, MAIN_LINE), facts, false)));
        Map<String, QuestLine> started = new HashMap<>();
        for (QuestLine l : GettData.get().allQuestLines()) {
            if (l.isSub() && QuestLines.started(data.progress(id, l.id()))) started.put(l.id(), l);
        }
        QuestPrefs prefs = data.prefs(id);
        for (String lineId : prefs.ordered(started.keySet())) {
            views.add(view(started.get(lineId), data.progress(id, lineId), facts, prefs.hidden().contains(lineId)));
        }
        QuestSyncPayload payload = new QuestSyncPayload(List.copyOf(views));
        if (payload.equals(LAST_SENT.get(id))) return;
        LAST_SENT.put(id, payload);
        PacketDistributor.sendToPlayer(player, payload);
    }

    private static QuestSyncPayload.LineView view(QuestLine line, QuestProgress progress, PlayerFacts facts,
                                                  boolean hidden) {
        return QuestViews.lineView(line, progress, QuestEngine.status(line, progress, facts), hidden);
    }

    // ---- admin ----

    /** Deletes the quest progress of the player — every line (and prefs) when {@code lineId} is empty — and re-judges. */
    public static void adminReset(ServerPlayer player, Optional<String> lineId) {
        QuestData data = QuestData.get(player.server);
        lineId.ifPresentOrElse(l -> data.resetLine(player.getUUID(), l), () -> data.reset(player.getUUID()));
        PENDING.remove(player.getUUID());
        refresh(player);
    }

    /**
     * Completes the current quest of {@code lineId} with rewards (accepted or not; first reward choice when it has
     * choices). Returns it, or empty when the line is unknown or done.
     */
    public static Optional<Quest> adminComplete(ServerPlayer player, String lineId) {
        Optional<QuestLine> line = GettData.get().questLine(lineId);
        if (line.isEmpty()) return Optional.empty();
        QuestData data = QuestData.get(player.server);
        QuestProgress progress = data.progress(player.getUUID(), lineId);
        ServerPlayerFacts facts = new ServerPlayerFacts(player);
        Optional<Quest> cur = QuestEngine.current(line.get(), progress);
        if (cur.isEmpty()) return Optional.empty();
        List<QuestEvent> events = QuestEngine.forceComplete(line.get(), progress, facts);
        data.setDirty();
        PENDING.remove(player.getUUID());
        handle(player, line.get(), events, "", 0);
        if (line.get().isSub()) data.prefs(player.getUUID()).noteStarted(lineId);
        sync(player);
        return cur;
    }

    /** Marks every quest of {@code lineId} before {@code questId} completed (no rewards). False when unknown. */
    public static boolean adminGoto(ServerPlayer player, String lineId, String questId) {
        Optional<QuestLine> line = GettData.get().questLine(lineId);
        if (line.isEmpty()) return false;
        QuestData data = QuestData.get(player.server);
        if (!QuestEngine.jumpTo(line.get(), data.progress(player.getUUID(), lineId), questId)) return false;
        if (line.get().isSub()) data.prefs(player.getUUID()).noteStarted(lineId);
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
