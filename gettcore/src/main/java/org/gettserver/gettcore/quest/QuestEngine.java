package org.gettserver.gettcore.quest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Pure quest progression rules (spec §5.3). Nothing is cached: the current quest and its phase are recomputed from the
 * line as it is now, so operators can reorder, insert, delete or retune quests without breaking saved progress.
 */
public final class QuestEngine {
    private QuestEngine() {}

    /** First quest in line order whose id is not completed (later duplicates of a completed id are skipped too). */
    public static Optional<Quest> current(QuestLine line, QuestProgress progress) {
        for (Quest q : line.quests()) {
            if (!progress.completed().contains(q.id())) return Optional.of(q);
        }
        return Optional.empty();
    }

    /** Read-only judgment; a stale {@code accepted} (not the current quest) is treated as not accepted. */
    public static QuestStatus status(QuestLine line, QuestProgress progress, PlayerFacts facts) {
        Optional<Quest> cur = current(line, progress);
        if (cur.isEmpty()) return new QuestStatus(Optional.empty(), QuestStatus.Phase.LINE_DONE, List.of());
        Quest q = cur.get();
        boolean accepted = isAccepted(q, progress);
        List<ObjectiveState> states = objectiveStates(q, progress, facts, accepted);
        QuestStatus.Phase phase;
        if (!accepted) {
            phase = QuestStatus.Phase.WAIT_START;
        } else if (allDone(states)) {
            phase = QuestStatus.Phase.READY_TO_REPORT;
        } else {
            phase = QuestStatus.Phase.ACTIVE;
        }
        return new QuestStatus(cur, phase, states);
    }

    /**
     * Applies automatic transitions until an NPC is needed or objectives are missing: auto-start accepts, auto-complete
     * completes once all objectives are done. Each quest takes up to two transitions (accept + complete), so the loop
     * guard is 2 * line length + 1; it is a safety bound, never a cutoff for a valid chain.
     */
    public static List<QuestEvent> advance(QuestLine line, QuestProgress progress, PlayerFacts facts) {
        normalize(line, progress);
        List<QuestEvent> events = new ArrayList<>();
        int limit = 2 * line.quests().size() + 1;
        for (int i = 0; i < limit; i++) {
            Optional<Quest> cur = current(line, progress);
            if (cur.isEmpty()) break;
            Quest q = cur.get();
            if (!isAccepted(q, progress)) {
                if (q.start().type() != QuestTrigger.Type.AUTO) break;
                accept(q, progress, events);
            } else {
                if (q.complete().type() != QuestTrigger.Type.AUTO
                        || !allDone(objectiveStates(q, progress, facts, true))) break;
                complete(q, progress, events);
            }
        }
        return events;
    }

    /**
     * Player interacted with an NPC: accepts the current quest if it waits for this NPC to start, or completes it if it
     * is ready to report to this NPC; then {@link #advance}. Any other NPC changes nothing. Does not record talk
     * objectives (that is {@link #onTalk}).
     */
    public static List<QuestEvent> interact(QuestLine line, QuestProgress progress, PlayerFacts facts, String npcName) {
        normalize(line, progress);
        Optional<Quest> cur = current(line, progress);
        if (cur.isEmpty()) return List.of();
        Quest q = cur.get();
        List<QuestEvent> events = new ArrayList<>();
        if (!isAccepted(q, progress)) {
            if (!isNpc(q.start(), npcName)) return List.of();
            accept(q, progress, events);
        } else {
            if (!isNpc(q.complete(), npcName) || !allDone(objectiveStates(q, progress, facts, true))) return List.of();
            complete(q, progress, events);
        }
        events.addAll(advance(line, progress, facts));
        return events;
    }

    /**
     * A kill happened; {@code targetMatches} says whether a kill objective target (entity id or #tag) matches the
     * victim. Counts only for the accepted current quest, capped at the objective count. Returns true if changed.
     */
    public static boolean onKill(QuestLine line, QuestProgress progress, Predicate<String> targetMatches) {
        normalize(line, progress);
        Optional<Quest> cur = current(line, progress);
        if (cur.isEmpty() || !isAccepted(cur.get(), progress)) return false;
        // Objectives sharing a target share one counter; cap at the largest count among them.
        Map<String, Integer> caps = new LinkedHashMap<>();
        for (Objective o : cur.get().objectives()) {
            if (o.type() == Objective.Type.KILL && targetMatches.test(o.target())) {
                caps.merge(o.target(), o.count(), Math::max);
            }
        }
        boolean changed = false;
        for (Map.Entry<String, Integer> e : caps.entrySet()) {
            int have = progress.kills().getOrDefault(e.getKey(), 0);
            if (have < e.getValue()) {
                progress.kills().put(e.getKey(), have + 1);
                changed = true;
            }
        }
        return changed;
    }

    /** A dialog with {@code npcName} opened: records a matching talk objective of the accepted quest. */
    public static boolean onTalk(QuestLine line, QuestProgress progress, String npcName) {
        normalize(line, progress);
        Optional<Quest> cur = current(line, progress);
        if (cur.isEmpty() || !isAccepted(cur.get(), progress)) return false;
        for (Objective o : cur.get().objectives()) {
            if (o.type() == Objective.Type.TALK && o.target().equals(npcName)) {
                return progress.talked().add(npcName);
            }
        }
        return false;
    }

    // ---- internals ----

    /** Drops accepted/kills/talked when they do not belong to the current quest any more. */
    private static void normalize(QuestLine line, QuestProgress progress) {
        Optional<Quest> cur = current(line, progress);
        if (cur.isPresent() && isAccepted(cur.get(), progress)) return;
        progress.setAccepted(null);
        progress.kills().clear();
        progress.talked().clear();
    }

    private static boolean isAccepted(Quest q, QuestProgress progress) {
        return progress.accepted().map(q.id()::equals).orElse(false);
    }

    private static boolean isNpc(QuestTrigger trigger, String npcName) {
        return trigger.type() == QuestTrigger.Type.NPC && trigger.npc().map(npcName::equals).orElse(false);
    }

    private static void accept(Quest q, QuestProgress progress, List<QuestEvent> events) {
        progress.setAccepted(q.id());
        progress.kills().clear();
        progress.talked().clear();
        events.add(new QuestEvent(QuestEvent.Kind.ACCEPTED, q));
    }

    private static void complete(Quest q, QuestProgress progress, List<QuestEvent> events) {
        progress.completed().add(q.id());
        progress.setAccepted(null);
        progress.kills().clear();
        progress.talked().clear();
        events.add(new QuestEvent(QuestEvent.Kind.COMPLETED, q));
    }

    private static boolean allDone(List<ObjectiveState> states) {
        for (ObjectiveState s : states) {
            if (!s.done()) return false;
        }
        return true; // zero objectives: done as soon as accepted
    }

    private static List<ObjectiveState> objectiveStates(Quest q, QuestProgress progress, PlayerFacts facts,
                                                        boolean accepted) {
        List<ObjectiveState> out = new ArrayList<>(q.objectives().size());
        for (Objective o : q.objectives()) {
            out.add(switch (o.type()) {
                case COLLECT -> counted(o, facts.countItem(o.target()), o.count());
                case KILL -> counted(o, accepted ? progress.kills().getOrDefault(o.target(), 0) : 0, o.count());
                case ADVANCEMENT -> counted(o, facts.hasAdvancement(o.target()) ? 1 : 0, 1);
                case TALK -> counted(o, accepted && progress.talked().contains(o.target()) ? 1 : 0, 1);
            });
        }
        return out;
    }

    private static ObjectiveState counted(Objective o, int have, int required) {
        return new ObjectiveState(o, Math.min(Math.max(have, 0), required), required, have >= required);
    }
}
