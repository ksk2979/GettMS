package org.gettserver.gettcore.quest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/** 퀘스트 줄 여러 개를 다루는 순수 규칙(판정 대상·선행 조건·킬 분배). */
public final class QuestLines {
    private QuestLines() {
    }

    /** 이 줄을 한 번이라도 진행했는가(완료한 퀘스트가 있거나 수락 중). */
    public static boolean started(QuestProgress p) {
        return !p.completed().isEmpty() || p.accepted().isPresent();
    }

    /** requires("줄:퀘스트") 충족 여부. 없으면 true, 형식이 틀리면 false. */
    public static boolean requirementMet(QuestLine line, Function<String, QuestProgress> progressOf) {
        if (line.requires().isEmpty()) return true;
        String[] parts = line.requires().get().split(":", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return false;
        QuestProgress p = progressOf.apply(parts[0]);
        return p != null && p.completed().contains(parts[1]);
    }

    /** 판정 대상 줄: 메인 먼저, 이어서 시작했거나 선행 조건을 채운 서브 줄(id 오름차순). */
    public static List<QuestLine> judged(Collection<QuestLine> all, Function<String, QuestProgress> progressOf) {
        List<QuestLine> out = new ArrayList<>();
        all.stream().filter(l -> !l.isSub()).forEach(out::add);
        all.stream().filter(QuestLine::isSub)
                .filter(l -> started(progressOf.apply(l.id())) || requirementMet(l, progressOf))
                .sorted(Comparator.comparing(QuestLine::id)).forEach(out::add);
        return out;
    }

    /** 모든 줄에 킬을 반영한다. 하나라도 바뀌면 true. */
    public static boolean applyKill(List<QuestLine> lines, Function<String, QuestProgress> progressOf,
                                    Predicate<String> targetMatches) {
        boolean changed = false;
        for (QuestLine l : lines) {
            changed |= QuestEngine.onKill(l, progressOf.apply(l.id()), targetMatches);
        }
        return changed;
    }
}
