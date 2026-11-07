package org.gettserver.gettcore.quest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.gettserver.gettcore.network.QuestSyncPayload;
import org.gettserver.gettcore.network.QuestSyncPayload.ObjState;
import org.gettserver.gettcore.network.QuestSyncPayload.QuestSummary;

/** Builds client views (sync payload, objective rows) from engine state. No server access. */
public final class QuestViews {
    private QuestViews() {}

    public static QuestSyncPayload sync(QuestLine line, QuestProgress progress, QuestStatus status) {
        List<QuestSummary> quests = new ArrayList<>(line.quests().size());
        List<String> completed = new ArrayList<>();
        Set<String> done = progress.completed();
        for (Quest q : line.quests()) {
            quests.add(new QuestSummary(q.id(), q.chapter(), q.title()));
            if (done.contains(q.id()) && !completed.contains(q.id())) completed.add(q.id());
        }
        Quest cur = status.quest().orElse(null);
        return new QuestSyncPayload(line.id(), line.name(), line.endText(),
                cur == null ? "" : cur.id(), cur == null ? "" : cur.chapter(), cur == null ? "" : cur.title(),
                status.phase(), QuestDialogs.hudNpc(status), objStates(status.objectives()), List.copyOf(completed),
                List.copyOf(quests));
    }

    public static List<ObjState> objStates(List<ObjectiveState> states) {
        List<ObjState> out = new ArrayList<>(states.size());
        for (ObjectiveState s : states) {
            Objective o = s.objective();
            out.add(new ObjState(o.type().getSerializedName(), o.target(), o.label().orElse(""), s.current(),
                    s.required(), s.done()));
        }
        return List.copyOf(out);
    }

    /** Rows for a quest that is not the current one: all done when completed, all zero when still locked. */
    public static List<ObjState> staticStates(Quest q, boolean completed) {
        List<ObjState> out = new ArrayList<>(q.objectives().size());
        for (Objective o : q.objectives()) {
            int required = switch (o.type()) {
                case COLLECT, KILL -> o.count();
                case ADVANCEMENT, TALK -> 1; // advancement/talk objectives ignore count
            };
            out.add(new ObjState(o.type().getSerializedName(), o.target(), o.label().orElse(""),
                    completed ? required : 0, required, completed));
        }
        return List.copyOf(out);
    }
}
