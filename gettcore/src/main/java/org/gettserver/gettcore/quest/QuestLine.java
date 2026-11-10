package org.gettserver.gettcore.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;

/**
 * 퀘스트 줄. giver 가 있으면 서브 줄(해당 NPC 가 주는 줄), 없으면 메인 줄.
 * requires 는 "&lt;lineId&gt;:&lt;questId&gt;" 문자열 그대로 보관한다(해석은 서비스 쪽).
 */
public record QuestLine(String id, String name, String endText, List<Quest> quests, Optional<String> giver,
                        Optional<String> requires) {
    public static final String DEFAULT_END_TEXT = "다음 메인 퀘스트를 준비 중입니다";

    public QuestLine(String id, String name, String endText, List<Quest> quests) {
        this(id, name, endText, quests, Optional.empty(), Optional.empty());
    }

    public boolean isSub() {
        return giver.isPresent();
    }

    public static final Codec<QuestLine> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(QuestLine::id),
            Codec.STRING.fieldOf("name").forGetter(QuestLine::name),
            Codec.STRING.optionalFieldOf("endText", DEFAULT_END_TEXT).forGetter(QuestLine::endText),
            Quest.RAW_CODEC.listOf().optionalFieldOf("quests", List.of()).forGetter(
                    (QuestLine l) -> l.quests().stream().map(q -> new Quest.Raw(q, true, true)).toList()),
            Codec.STRING.optionalFieldOf("giver").forGetter(QuestLine::giver),
            Codec.STRING.optionalFieldOf("requires").forGetter(QuestLine::requires)
    ).apply(i, QuestLine::fromRaw));

    private static QuestLine fromRaw(String id, String name, String endText, List<Quest.Raw> raws,
                                     Optional<String> giver, Optional<String> requires) {
        QuestTrigger giverTrigger = giver.map(g -> new QuestTrigger(QuestTrigger.Type.NPC, Optional.of(g)))
                .orElse(QuestTrigger.AUTO);
        List<Quest> quests = raws.stream().map(r -> {
            Quest q = r.quest();
            if (giver.isEmpty() || (r.startSet() && r.completeSet())) return q;
            return new Quest(q.id(), q.chapter(), q.title(), q.description(),
                    r.startSet() ? q.start() : giverTrigger, r.completeSet() ? q.complete() : giverTrigger,
                    q.objectives(), q.rewards(), q.completeText(), q.choices());
        }).toList();
        return new QuestLine(id, name, endText, quests, giver, requires);
    }
}
