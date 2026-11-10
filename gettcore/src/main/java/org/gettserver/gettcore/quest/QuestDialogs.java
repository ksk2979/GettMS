package org.gettserver.gettcore.quest;

import java.util.Optional;
import org.gettserver.gettcore.network.QuestDetailPayload.Mode;

/** Pure rules for putting a quest choice into an NPC dialog and choosing which quest screen to show. */
public final class QuestDialogs {
    private QuestDialogs() {}

    public static final String LABEL_PREFIX = "§e[메인] ";
    public static final String SUB_PREFIX = "§a[서브] ";
    public static final String REPORT_SUFFIX = " (완료)";

    /** A quest choice put on top of an NPC's start node: the line it opens and its label. */
    public record Injected(String lineId, String label) {}

    /**
     * Result of mapping a client choice index on a node that may carry injected quest choices at indices
     * {@code 0..injectedCount-1}: {@code questIndex} ≥ 0 picks an injected choice, else {@code nodeIndex} ≥ 0 a node one.
     */
    public record Choice(int questIndex, int nodeIndex) {
        public static final Choice INVALID = new Choice(-1, -1);

        public boolean quest() {
            return questIndex >= 0;
        }

        public boolean valid() {
            return quest() || nodeIndex >= 0;
        }
    }

    public static String choiceLabel(String title, boolean report) {
        return choiceLabel(title, report, false);
    }

    /** "§e[메인] 제목" for the main line, "§a[서브] 제목" for a sub line; " (완료)" appended for a report. */
    public static String choiceLabel(String title, boolean report, boolean sub) {
        return (sub ? SUB_PREFIX : LABEL_PREFIX) + title + (report ? REPORT_SUFFIX : "");
    }

    /** Reward index check of a report: anything when the quest has no choices, else {@code 0 <= choice < size}. */
    public static boolean validChoice(Quest q, int choice) {
        return q.choices().isEmpty() || (choice >= 0 && choice < q.choices().size());
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
        return injectedLabel(status, npcName, false);
    }

    /** Same as {@link #injectedLabel(QuestStatus, String)} with the sub-line prefix when {@code sub}. */
    public static Optional<String> injectedLabel(QuestStatus status, String npcName, boolean sub) {
        Optional<Mode> mode = detailMode(status, npcName);
        if (mode.isEmpty() || mode.get() == Mode.VIEW) return Optional.empty();
        return Optional.of(choiceLabel(status.quest().get().title(), mode.get() == Mode.REPORT, sub));
    }

    /** Maps a client index to an injected quest choice or a node choice index (shifted by {@code injectedCount}). */
    public static Choice resolve(int injectedCount, int index, int nodeChoices) {
        if (index >= 0 && index < injectedCount) return new Choice(index, -1);
        int i = index - injectedCount;
        return index >= 0 && i < nodeChoices ? new Choice(-1, i) : Choice.INVALID;
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
