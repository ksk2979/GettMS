package org.gettserver.gettcore.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;

public record Quest(String id, String chapter, String title, String description, QuestTrigger start,
                    QuestTrigger complete, List<Objective> objectives, Rewards rewards, String completeText,
                    List<ItemReward> choices) {
    /** 고르는 보상 없이 만드는 기존 형태. */
    public Quest(String id, String chapter, String title, String description, QuestTrigger start,
                 QuestTrigger complete, List<Objective> objectives, Rewards rewards, String completeText) {
        this(id, chapter, title, description, start, complete, objectives, rewards, completeText, List.of());
    }

    /** 디코드 결과: start/complete 가 JSON 에 있었는지 함께 들고 있다(없으면 줄이 기본값을 채운다). */
    public record Raw(Quest quest, boolean startSet, boolean completeSet) {}

    public static final Codec<Raw> RAW_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter((Raw r) -> r.quest().id()),
            Codec.STRING.optionalFieldOf("chapter", "").forGetter((Raw r) -> r.quest().chapter()),
            Codec.STRING.fieldOf("title").forGetter((Raw r) -> r.quest().title()),
            Codec.STRING.optionalFieldOf("description", "").forGetter((Raw r) -> r.quest().description()),
            QuestTrigger.CODEC.optionalFieldOf("start")
                    .forGetter((Raw r) -> r.startSet() ? Optional.of(r.quest().start()) : Optional.empty()),
            QuestTrigger.CODEC.optionalFieldOf("complete")
                    .forGetter((Raw r) -> r.completeSet() ? Optional.of(r.quest().complete()) : Optional.empty()),
            Objective.CODEC.listOf().optionalFieldOf("objectives", List.of())
                    .forGetter((Raw r) -> r.quest().objectives()),
            Rewards.CODEC.optionalFieldOf("rewards", Rewards.EMPTY).forGetter((Raw r) -> r.quest().rewards()),
            Codec.STRING.optionalFieldOf("completeText", "").forGetter((Raw r) -> r.quest().completeText()),
            ItemReward.CODEC.listOf().optionalFieldOf("choices", List.of())
                    .forGetter((Raw r) -> r.quest().choices())
    ).apply(i, (id, chapter, title, description, start, complete, objectives, rewards, completeText, choices) ->
            new Raw(new Quest(id, chapter, title, description, start.orElse(QuestTrigger.AUTO),
                    complete.orElse(QuestTrigger.AUTO), objectives, rewards, completeText, choices),
                    start.isPresent(), complete.isPresent())));

    /** 단독 코덱: 없는 트리거는 AUTO, 인코딩은 항상 start/complete 를 쓴다. */
    public static final Codec<Quest> CODEC = RAW_CODEC.xmap(Raw::quest, q -> new Raw(q, true, true));
}
