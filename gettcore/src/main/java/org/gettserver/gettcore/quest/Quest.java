package org.gettserver.gettcore.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;

public record Quest(String id, String chapter, String title, String description, QuestTrigger start,
                    QuestTrigger complete, List<Objective> objectives, Rewards rewards, String completeText) {
    public static final Codec<Quest> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(Quest::id),
            Codec.STRING.optionalFieldOf("chapter", "").forGetter(Quest::chapter),
            Codec.STRING.fieldOf("title").forGetter(Quest::title),
            Codec.STRING.optionalFieldOf("description", "").forGetter(Quest::description),
            QuestTrigger.CODEC.optionalFieldOf("start", QuestTrigger.AUTO).forGetter(Quest::start),
            QuestTrigger.CODEC.optionalFieldOf("complete", QuestTrigger.AUTO).forGetter(Quest::complete),
            Objective.CODEC.listOf().optionalFieldOf("objectives", List.of()).forGetter(Quest::objectives),
            Rewards.CODEC.optionalFieldOf("rewards", Rewards.EMPTY).forGetter(Quest::rewards),
            Codec.STRING.optionalFieldOf("completeText", "").forGetter(Quest::completeText)
    ).apply(i, Quest::new));
}
