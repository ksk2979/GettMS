package org.gettserver.gettcore.quest.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.gettserver.gettcore.economy.Gold;
import org.gettserver.gettcore.network.QuestSyncPayload;
import org.gettserver.gettcore.network.QuestSyncPayload.ObjState;
import org.gettserver.gettcore.quest.QuestStatus.Phase;

/**
 * Pure text rules for the quest HUD, screens and notifications. Names (items, entities, advancements) are resolved by
 * the caller and passed in, so this class needs no client game state and is unit-tested.
 */
public final class QuestText {
    private QuestText() {}

    public enum Status { DONE, CURRENT, LOCKED }

    /** One row of the quest list: a chapter header ({@code header} true, only {@code chapter} set) or a quest. */
    public record LogEntry(boolean header, String chapter, String id, String title, Status status) {}

    /** Label of an objective: the configured label, or an automatic one built from the resolved target name. */
    public static Component objectiveLabel(ObjState o, Component targetName) {
        if (!o.label().isBlank()) return Component.literal(o.label());
        return switch (o.type()) {
            case "collect" -> Component.translatable("gettcore.quest.obj.collect", targetName, o.required());
            case "kill" -> "@monster".equals(o.target())
                    ? Component.translatable("gettcore.quest.obj.kill_monster", o.required())
                    : Component.translatable("gettcore.quest.obj.kill", targetName, o.required());
            case "advancement" -> Component.translatable("gettcore.quest.obj.advancement", targetName);
            case "talk" -> Component.translatable("gettcore.quest.obj.talk", targetName,
                    waGwa(targetName.getString()));
            default -> targetName.copy();
        };
    }

    /** "• 라벨 7/16" (white), or "✔ 라벨 16/16" (green) when done. The count is capped at the required amount. */
    public static MutableComponent objectiveLine(ObjState o, Component label) {
        int shown = Math.max(0, Math.min(o.current(), o.required()));
        if (o.done()) shown = o.required();
        MutableComponent line = Component.literal(o.done() ? "✔ " : "• ").append(label)
                .append(" " + shown + "/" + o.required());
        return line.withStyle(o.done() ? ChatFormatting.GREEN : ChatFormatting.WHITE);
    }

    /** "[1장. 시작] 첫 걸음" in yellow; just the title when there is no chapter. */
    public static MutableComponent headerLine(String chapter, String title) {
        String text = chapter.isBlank() ? title : "[" + chapter + "] " + title;
        return Component.literal(text).withStyle(ChatFormatting.YELLOW);
    }

    /** The line shown instead of objectives for a phase, or empty while ACTIVE (objectives are shown then). */
    public static Optional<Component> phaseLine(Phase phase, String npc, String endText) {
        return switch (phase) {
            case WAIT_START -> Optional.of(npc.isBlank()
                    ? Component.translatable("gettcore.quest.hud.wait_start_any")
                    : Component.translatable("gettcore.quest.hud.wait_start", npc));
            // 목표를 다 채운 상태라 완료한 목표 줄과 같은 초록색.
            case READY_TO_REPORT -> Optional.of((npc.isBlank()
                    ? Component.translatable("gettcore.quest.hud.report_any")
                    : Component.translatable("gettcore.quest.hud.report", npc)).withStyle(ChatFormatting.GREEN));
            case LINE_DONE -> Optional.of(endText.isBlank()
                    ? Component.translatable("gettcore.quest.hud.line_done")
                    : Component.literal(endText));
            case ACTIVE -> Optional.empty();
        };
    }

    /**
     * Everything the tracker HUD shows for one line, top to bottom (before wrapping). Empty for a blank line id.
     * {@code labeler} turns an objective into its label (client name resolution).
     */
    public static List<Component> hudLines(QuestSyncPayload.LineView s, Function<ObjState, Component> labeler) {
        List<Component> out = new ArrayList<>();
        if (s.lineId().isEmpty()) return out;
        if (s.phase() == Phase.LINE_DONE) {
            if (!s.lineName().isBlank()) out.add(headerLine("", s.lineName()));
            phaseLine(s.phase(), s.npc(), s.endText()).ifPresent(out::add);
            return out;
        }
        out.add(headerLine(s.chapter(), s.title()));
        out.addAll(progressLines(s, labeler));
        return out;
    }

    /** The phase line, or one line per objective while ACTIVE (the HUD without its header). */
    public static List<Component> progressLines(QuestSyncPayload.LineView s, Function<ObjState, Component> labeler) {
        List<Component> out = new ArrayList<>();
        Optional<Component> phase = phaseLine(s.phase(), s.npc(), s.endText());
        if (phase.isPresent()) {
            out.add(phase.get());
        } else {
            for (ObjState o : s.objectives()) out.add(objectiveLine(o, labeler.apply(o)));
        }
        return out;
    }

    /** Status of one quest of the line for the quest list. */
    public static Status status(QuestSyncPayload.LineView s, String questId) {
        if (s.completedIds().contains(questId)) return Status.DONE;
        if (questId.equals(s.questId())) return Status.CURRENT;
        return Status.LOCKED;
    }

    /** Quest list rows in line order, with a header row whenever the chapter changes (none for a blank chapter). */
    public static List<LogEntry> logEntries(QuestSyncPayload.LineView s) {
        List<LogEntry> out = new ArrayList<>();
        Set<String> done = new HashSet<>(s.completedIds());
        String chapter = null;
        for (QuestSyncPayload.QuestSummary q : s.quests()) {
            if (!q.chapter().equals(chapter)) {
                chapter = q.chapter();
                if (!chapter.isBlank()) out.add(new LogEntry(true, chapter, "", "", Status.LOCKED));
            }
            Status st = done.contains(q.id()) ? Status.DONE
                    : q.id().equals(s.questId()) ? Status.CURRENT : Status.LOCKED;
            out.add(new LogEntry(false, q.chapter(), q.id(), q.title(), st));
        }
        return out;
    }

    /** Toast subtitle: the quest title, plus "  +1,000 골드" in gold when the reward has gold. */
    public static MutableComponent toastSubtitle(String title, long gold, String currency) {
        MutableComponent sub = Component.literal(title).withStyle(ChatFormatting.WHITE);
        if (gold > 0) {
            sub.append(Component.literal("  +" + Gold.format(gold) + " " + currency)
                    .withStyle(ChatFormatting.GOLD));
        }
        return sub;
    }

    /** Korean particle 와/과 for the word: 과 after a final consonant, 와 after a vowel, 와(과) for non-Hangul. */
    public static String waGwa(String word) {
        if (word.isEmpty()) return "와(과)";
        char c = word.charAt(word.length() - 1);
        if (c < 0xAC00 || c > 0xD7A3) return "와(과)";
        return (c - 0xAC00) % 28 != 0 ? "과" : "와";
    }
}
