package org.gettserver.gettcore.quest;

import java.util.Optional;
import org.gettserver.gettcore.network.QuestDetailPayload.Mode;

/** Pure rules for putting a quest choice into an NPC dialog and choosing which quest screen to show. */
public final class QuestDialogs {
    private QuestDialogs() {}

    public static final String LABEL_PREFIX = "§e[메인] ";
    public static final String REPORT_SUFFIX = " (완료)";

    /** Result of mapping a client choice index on a node that may carry an injected quest choice at index 0. */
    public record Choice(boolean quest, int nodeIndex) {
        public static final Choice INVALID = new Choice(false, -1);

        public boolean valid() {
            return quest || nodeIndex >= 0;
        }
    }

    public static String choiceLabel(String title, boolean report) {
        return LABEL_PREFIX + title + (report ? REPORT_SUFFIX : "");
    }

    /**
     * The screen an NPC opens for the current quest: OFFER when it waits for this NPC to start, REPORT when it is ready
     * to report to this NPC, VIEW otherwise; empty when the line is done.
     */
    public static Optional<Mode> detailMode(QuestStatus status, String npcName) {
        if (status.quest().isEmpty()) return Optional.empty();
        Quest q = status.quest().get();
        return switch (status.phase()) {
            case WAIT_START -> Optional.of(isNpc(q.start(), npcName) ? Mode.OFFER : Mode.VIEW);
            case READY_TO_REPORT -> Optional.of(isNpc(q.complete(), npcName) ? Mode.REPORT : Mode.VIEW);
            case ACTIVE -> Optional.of(Mode.VIEW);
            case LINE_DONE -> Optional.empty();
        };
    }

    /** Label of the quest choice injected at the top of this NPC's start node, if any (OFFER/REPORT only). */
    public static Optional<String> injectedLabel(QuestStatus status, String npcName) {
        Optional<Mode> mode = detailMode(status, npcName);
        if (mode.isEmpty() || mode.get() == Mode.VIEW) return Optional.empty();
        return Optional.of(choiceLabel(status.quest().get().title(), mode.get() == Mode.REPORT));
    }

    /** Maps a client index to the injected quest choice or a node choice index (shifted by one when injected). */
    public static Choice resolve(boolean injected, int index, int nodeChoices) {
        if (injected && index == 0) return new Choice(true, -1);
        int i = injected ? index - 1 : index;
        return i >= 0 && i < nodeChoices ? new Choice(false, i) : Choice.INVALID;
    }

    /** NPC the HUD should point to: start NPC while waiting, complete NPC when ready to report, else "". */
    public static String hudNpc(QuestStatus status) {
        if (status.quest().isEmpty()) return "";
        Quest q = status.quest().get();
        return switch (status.phase()) {
            case WAIT_START -> npcOf(q.start());
            case READY_TO_REPORT -> npcOf(q.complete());
            default -> "";
        };
    }

    private static String npcOf(QuestTrigger t) {
        return t.type() == QuestTrigger.Type.NPC ? t.npc().orElse("") : "";
    }

    private static boolean isNpc(QuestTrigger t, String npcName) {
        return t.type() == QuestTrigger.Type.NPC && t.npc().map(n -> n.equals(npcName)).orElse(false);
    }
}
