package org.gettserver.gettcore.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Stored progress of one player in one quest line: completed quest ids, the accepted current quest, and kill/talk
 * records for that accepted quest. Everything else (objective done, ready to report) is recomputed by
 * {@link QuestEngine}. Mutable.
 */
public final class QuestProgress {
    private final Set<String> completed;
    private String accepted;
    private final Map<String, Integer> kills;
    private final Set<String> talked;

    public QuestProgress() {
        this(List.of(), Optional.empty(), Map.of(), List.of());
    }

    private QuestProgress(List<String> completed, Optional<String> accepted, Map<String, Integer> kills,
                          List<String> talked) {
        this.completed = new LinkedHashSet<>(completed);
        this.accepted = accepted.orElse(null);
        this.kills = new HashMap<>(kills);
        this.talked = new LinkedHashSet<>(talked);
    }

    public static final Codec<QuestProgress> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.listOf().optionalFieldOf("completed", List.of()).forGetter(p -> new ArrayList<>(p.completed)),
            Codec.STRING.optionalFieldOf("accepted").forGetter(QuestProgress::accepted),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("kills", Map.of()).forGetter(p -> p.kills),
            Codec.STRING.listOf().optionalFieldOf("talked", List.of()).forGetter(p -> new ArrayList<>(p.talked))
    ).apply(i, QuestProgress::new));

    /** Ids of completed quests (mutable, insertion ordered). */
    public Set<String> completed() {
        return completed;
    }

    public Optional<String> accepted() {
        return Optional.ofNullable(accepted);
    }

    public void setAccepted(String questId) {
        this.accepted = questId;
    }

    /** Kill counts for the accepted quest, keyed by the kill objective's target string (mutable). */
    public Map<String, Integer> kills() {
        return kills;
    }

    /** NPC names talked to for the accepted quest's talk objectives (mutable). */
    public Set<String> talked() {
        return talked;
    }
}
