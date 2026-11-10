package org.gettserver.gettcore.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Per-player quest preferences: order in which sub lines were first started, and lines hidden from the HUD. Mutable. */
public final class QuestPrefs {
    private final List<String> order;
    private final Set<String> hidden;

    public QuestPrefs() {
        this(List.of(), List.of());
    }

    private QuestPrefs(List<String> order, List<String> hidden) {
        this.order = new ArrayList<>(new LinkedHashSet<>(order));
        this.hidden = new LinkedHashSet<>(hidden);
    }

    public static final Codec<QuestPrefs> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.listOf().optionalFieldOf("order", List.of()).forGetter(p -> p.order),
            Codec.STRING.listOf().optionalFieldOf("hidden", List.of()).forGetter(p -> new ArrayList<>(p.hidden))
    ).apply(i, QuestPrefs::new));

    public List<String> order() {
        return order;
    }

    public Set<String> hidden() {
        return hidden;
    }

    public void noteStarted(String lineId) {
        if (!order.contains(lineId)) order.add(lineId);
    }

    /** Started sub lines in first-started order; ones not recorded come after, by id. */
    public List<String> ordered(Collection<String> startedSubIds) {
        Set<String> started = new LinkedHashSet<>(startedSubIds);
        List<String> out = new ArrayList<>();
        for (String id : order) if (started.contains(id)) out.add(id);
        out.addAll(new TreeSet<>(started).stream().filter(id -> !order.contains(id)).toList());
        return out;
    }
}
