package org.gettserver.gettcore.quest;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.gettserver.gettcore.network.QuestActionPayload;
import org.gettserver.gettcore.network.QuestDetailPayload;
import org.gettserver.gettcore.network.QuestDetailPayload.Mode;
import org.gettserver.gettcore.network.QuestNotifyPayload;
import org.gettserver.gettcore.network.QuestSyncPayload;
import org.junit.jupiter.api.Test;

/** Pure parts of the quest server service: dialog injection, index mapping, views, item targets, admin ops, codecs. */
class QuestServiceLogicTest {
    private static final String CHIEF = "이장";

    private static QuestTrigger npc(String name) {
        return new QuestTrigger(QuestTrigger.Type.NPC, Optional.of(name));
    }

    private static Objective obj(Objective.Type type, String target, int count) {
        return new Objective(type, target, count, false, Optional.empty());
    }

    private static Quest quest(String id, QuestTrigger start, QuestTrigger complete, Objective... objectives) {
        return new Quest(id, "1장", "제목 " + id, "설명", start, complete, List.of(objectives), Rewards.EMPTY, "완료");
    }

    private static final Quest Q1 = quest("q1", npc(CHIEF), QuestTrigger.AUTO, obj(Objective.Type.TALK, CHIEF, 1));
    private static final Quest Q2 = quest("q2", QuestTrigger.AUTO, npc(CHIEF),
            obj(Objective.Type.COLLECT, "#minecraft:logs", 16));
    private static final Quest Q3 = quest("q3", QuestTrigger.AUTO, QuestTrigger.AUTO,
            obj(Objective.Type.ADVANCEMENT, "minecraft:story/mine_stone", 5));
    private static final QuestLine LINE = new QuestLine("main", "메인", "준비 중", List.of(Q1, Q2, Q3));

    private static final class Facts implements PlayerFacts {
        int logs;

        @Override
        public int countItem(String itemOrTag) {
            return itemOrTag.equals("#minecraft:logs") ? logs : 0;
        }

        @Override
        public boolean hasAdvancement(String id) {
            return false;
        }
    }

    private static List<String> describe(List<QuestEvent> events) {
        return events.stream().map(e -> e.kind() + ":" + e.quest().id()).toList();
    }

    // ---- dialog injection ----

    @Test
    void labels() {
        assertEquals("§e[메인] 첫 걸음", QuestDialogs.choiceLabel("첫 걸음", false));
        assertEquals("§e[메인] 첫 걸음 (완료)", QuestDialogs.choiceLabel("첫 걸음", true));
    }

    @Test
    void offerInjectedOnlyAtStartNpc() {
        QuestStatus s = QuestEngine.status(LINE, new QuestProgress(), new Facts());
        assertEquals(Optional.of("§e[메인] 제목 q1"), QuestDialogs.injectedLabel(s, CHIEF));
        assertEquals(Optional.empty(), QuestDialogs.injectedLabel(s, "농부"));
        assertEquals(Optional.of(Mode.OFFER), QuestDialogs.detailMode(s, CHIEF));
        assertEquals(Optional.of(Mode.VIEW), QuestDialogs.detailMode(s, "농부"));
        assertEquals(CHIEF, QuestDialogs.hudNpc(s));
    }

    @Test
    void reportInjectedOnlyWhenReady() {
        QuestProgress p = new QuestProgress();
        p.completed().add("q1");
        Facts f = new Facts();
        QuestEngine.advance(LINE, p, f); // auto-accepts q2
        QuestStatus active = QuestEngine.status(LINE, p, f);
        assertEquals(QuestStatus.Phase.ACTIVE, active.phase());
        assertEquals(Optional.empty(), QuestDialogs.injectedLabel(active, CHIEF));
        assertEquals(Optional.of(Mode.VIEW), QuestDialogs.detailMode(active, CHIEF));
        assertEquals("", QuestDialogs.hudNpc(active));

        f.logs = 16;
        QuestStatus ready = QuestEngine.status(LINE, p, f);
        assertEquals(Optional.of("§e[메인] 제목 q2 (완료)"), QuestDialogs.injectedLabel(ready, CHIEF));
        assertEquals(Optional.of(Mode.REPORT), QuestDialogs.detailMode(ready, CHIEF));
        assertEquals(CHIEF, QuestDialogs.hudNpc(ready));
    }

    @Test
    void lineDoneHasNoScreen() {
        QuestProgress p = new QuestProgress();
        p.completed().addAll(List.of("q1", "q2", "q3"));
        QuestStatus s = QuestEngine.status(LINE, p, new Facts());
        assertEquals(Optional.empty(), QuestDialogs.detailMode(s, CHIEF));
        assertEquals(Optional.empty(), QuestDialogs.injectedLabel(s, CHIEF));
    }

    @Test
    void choiceIndexMapping() {
        // Injected: 0 = quest, 1..n = node choices 0..n-1.
        assertTrue(QuestDialogs.resolve(true, 0, 2).quest());
        assertEquals(0, QuestDialogs.resolve(true, 1, 2).nodeIndex());
        assertEquals(1, QuestDialogs.resolve(true, 2, 2).nodeIndex());
        assertFalse(QuestDialogs.resolve(true, 3, 2).valid());
        // Only the quest choice (node without choices).
        assertTrue(QuestDialogs.resolve(true, 0, 0).quest());
        assertFalse(QuestDialogs.resolve(true, 1, 0).valid());
        // Not injected: identity.
        assertFalse(QuestDialogs.resolve(false, 0, 2).quest());
        assertEquals(0, QuestDialogs.resolve(false, 0, 2).nodeIndex());
        assertFalse(QuestDialogs.resolve(false, 2, 2).valid());
        assertFalse(QuestDialogs.resolve(false, -5, 2).valid());
    }

    /** Accept at 이장 then the same-visit talk completes the first quest (the sequence QuestService.onAction runs). */
    @Test
    void acceptThenTalkCompletesFirstQuestInSameVisit() {
        QuestProgress p = new QuestProgress();
        Facts f = new Facts();
        List<QuestEvent> events = new ArrayList<>(QuestEngine.interact(LINE, p, f, CHIEF));
        assertTrue(QuestEngine.onTalk(LINE, p, CHIEF));
        events.addAll(QuestEngine.advance(LINE, p, f));
        assertEquals(List.of("ACCEPTED:q1", "COMPLETED:q1", "ACCEPTED:q2"), describe(events));
    }

    // ---- views ----

    @Test
    void syncPayloadContentsAndEquality() {
        QuestProgress p = new QuestProgress();
        p.completed().add("q1");
        p.completed().add("gone"); // not in the line any more: not sent
        Facts f = new Facts();
        QuestEngine.advance(LINE, p, f);
        f.logs = 7;
        QuestSyncPayload a = QuestViews.sync(LINE, p, QuestEngine.status(LINE, p, f));
        assertEquals("main", a.lineId());
        assertEquals("q2", a.questId());
        assertEquals(QuestStatus.Phase.ACTIVE, a.phase());
        assertEquals(List.of("q1"), a.completedIds());
        assertEquals(3, a.quests().size());
        assertEquals(new QuestSyncPayload.ObjState("collect", "#minecraft:logs", "", 7, 16, false),
                a.objectives().get(0));
        // Unchanged state builds an equal payload (no resend); a changed count does not.
        assertEquals(a, QuestViews.sync(LINE, p, QuestEngine.status(LINE, p, f)));
        f.logs = 8;
        assertNotEquals(a, QuestViews.sync(LINE, p, QuestEngine.status(LINE, p, f)));
    }

    @Test
    void staticStatesIgnoreAdvancementCount() {
        List<QuestSyncPayload.ObjState> done = QuestViews.staticStates(Q3, true);
        assertEquals(1, done.get(0).required());
        assertTrue(done.get(0).done());
        List<QuestSyncPayload.ObjState> locked = QuestViews.staticStates(Q2, false);
        assertEquals(0, locked.get(0).current());
        assertEquals(16, locked.get(0).required());
    }

    // ---- item targets ----

    @Test
    void parseTargets() {
        assertEquals(Optional.of(new ItemTargets.Target(true, "minecraft:logs")), ItemTargets.parse("#minecraft:logs"));
        assertEquals(Optional.of(new ItemTargets.Target(false, "minecraft:torch")), ItemTargets.parse("torch"));
        assertEquals(Optional.of(new ItemTargets.Target(true, "minecraft:beds")), ItemTargets.parse("#beds"));
        assertEquals(Optional.empty(), ItemTargets.parse("#"));
        assertEquals(Optional.empty(), ItemTargets.parse("  "));
        assertEquals(Optional.empty(), ItemTargets.parse(null));
    }

    @Test
    void removalPlanTakesExactly() {
        assertArrayEquals(new int[]{0, 10, 6, 0}, ItemTargets.removalPlan(new int[]{0, 10, 64, 5}, 16));
        assertArrayEquals(new int[]{3, 2}, ItemTargets.removalPlan(new int[]{3, 2}, 9));
        assertArrayEquals(new int[]{0, 0}, ItemTargets.removalPlan(new int[]{3, 2}, 0));
    }

    @Test
    void itemMatcherHandlesIds() {
        var torch = ServerPlayerFacts.itemMatcher("minecraft:torch").orElseThrow();
        assertTrue(torch.test(new ItemStack(Items.TORCH)));
        assertFalse(torch.test(new ItemStack(Items.OAK_LOG)));
        assertTrue(ServerPlayerFacts.itemMatcher("#minecraft:logs").isPresent());
        assertTrue(ServerPlayerFacts.itemMatcher("Bad Id!").isEmpty());
    }

    // ---- admin ----

    @Test
    void forceCompleteWithoutAccepting() {
        QuestProgress p = new QuestProgress();
        assertEquals(List.of("COMPLETED:q1", "ACCEPTED:q2"), describe(QuestEngine.forceComplete(LINE, p, new Facts())));
        p.completed().addAll(List.of("q2", "q3"));
        assertTrue(QuestEngine.forceComplete(LINE, p, new Facts()).isEmpty());
    }

    @Test
    void jumpToMarksExactlyEarlierQuests() {
        QuestProgress p = new QuestProgress();
        p.completed().addAll(List.of("q1", "q2", "q3"));
        assertTrue(QuestEngine.jumpTo(LINE, p, "q2"));
        assertEquals(List.of("q1"), List.copyOf(p.completed()));
        assertEquals(Optional.empty(), p.accepted());
        assertEquals("q2", QuestEngine.current(LINE, p).orElseThrow().id());
        assertFalse(QuestEngine.jumpTo(LINE, p, "nope"));
    }

    // ---- storage, codecs ----

    @Test
    void questDataRoundTrip() {
        QuestData data = new QuestData();
        UUID id = UUID.randomUUID();
        QuestProgress p = data.progress(id, "main");
        p.completed().add("q1");
        p.setAccepted("q2");
        p.kills().put("minecraft:zombie", 2);
        CompoundTag tag = data.save(new CompoundTag(), null);
        QuestProgress back = QuestData.load(tag, null).progress(id, "main");
        assertEquals(List.of("q1"), List.copyOf(back.completed()));
        assertEquals(Optional.of("q2"), back.accepted());
        assertEquals(Map.of("minecraft:zombie", 2), back.kills());
        data.reset(id);
        assertTrue(data.progress(id, "main").completed().isEmpty());
    }

    @Test
    void payloadCodecsRoundTrip() {
        QuestProgress p = new QuestProgress();
        QuestSyncPayload sync = QuestViews.sync(LINE, p, QuestEngine.status(LINE, p, new Facts()));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        QuestSyncPayload.STREAM_CODEC.encode(buf, sync);
        assertEquals(sync, QuestSyncPayload.STREAM_CODEC.decode(buf));
        QuestSyncPayload.STREAM_CODEC.encode(buf, QuestSyncPayload.empty());
        assertEquals(QuestSyncPayload.empty(), QuestSyncPayload.STREAM_CODEC.decode(buf));

        QuestActionPayload action = new QuestActionPayload("q1", QuestActionPayload.Action.REPORT);
        QuestActionPayload.STREAM_CODEC.encode(buf, action);
        assertEquals(action, QuestActionPayload.STREAM_CODEC.decode(buf));

        QuestNotifyPayload notify = new QuestNotifyPayload(QuestNotifyPayload.Kind.COMPLETED, "첫 걸음", 200);
        QuestNotifyPayload.STREAM_CODEC.encode(buf, notify);
        assertEquals(notify, QuestNotifyPayload.STREAM_CODEC.decode(buf));

        RegistryFriendlyByteBuf rbuf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        QuestDetailPayload detail = new QuestDetailPayload("q1", "t", "d", "c", QuestViews.staticStates(Q2, false),
                200, List.of(new ItemStack(Items.TORCH, 3)), 2, Mode.OFFER);
        QuestDetailPayload.STREAM_CODEC.encode(rbuf, detail);
        QuestDetailPayload back = QuestDetailPayload.STREAM_CODEC.decode(rbuf);
        assertEquals(detail.objectives(), back.objectives());
        assertEquals(200, back.gold());
        assertEquals(Mode.OFFER, back.mode());
        assertEquals(1, back.items().size());
        assertTrue(ItemStack.matches(detail.items().get(0), back.items().get(0)));
    }
}
