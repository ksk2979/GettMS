package org.gettserver.gettcore.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;

public record QuestLine(String id, String name, String endText, List<Quest> quests) {
    public static final String DEFAULT_END_TEXT = "다음 메인 퀘스트를 준비 중입니다";

    public static final Codec<QuestLine> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(QuestLine::id),
            Codec.STRING.fieldOf("name").forGetter(QuestLine::name),
            Codec.STRING.optionalFieldOf("endText", DEFAULT_END_TEXT).forGetter(QuestLine::endText),
            Quest.CODEC.listOf().optionalFieldOf("quests", List.of()).forGetter(QuestLine::quests)
    ).apply(i, QuestLine::new));
}
