package org.gettserver.gettcore.quest;

import java.util.List;
import java.util.Optional;

/** Current quest of a line for one player, recomputed from rules each time. */
public record QuestStatus(Optional<Quest> quest, Phase phase, List<ObjectiveState> objectives) {
    public enum Phase { WAIT_START, ACTIVE, READY_TO_REPORT, LINE_DONE }
}
