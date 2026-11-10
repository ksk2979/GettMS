package org.gettserver.gettcore.quest;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.data.RegistryChecks;

/** Builds the in-memory view of a quest line without references to items/entities missing from the registry. */
public final class QuestPrune {
    private QuestPrune() {}

    public static QuestLine prune(QuestLine line, RegistryChecks checks, List<String> warnings) {
        List<Quest> kept = new ArrayList<>();
        boolean changed = false;
        for (Quest q : line.quests()) {
            List<Objective> objectives = new ArrayList<>();
            for (Objective o : q.objectives()) {
                if (exists(o, checks)) {
                    objectives.add(o);
                } else {
                    warnings.add("quest " + line.id() + "/" + q.id() + ": missing " + o.type().getSerializedName()
                            + " target " + o.target() + " (objective skipped)");
                }
            }
            if (!q.objectives().isEmpty() && objectives.isEmpty()) {
                warnings.add("quest " + line.id() + "/" + q.id() + ": all objectives missing (quest skipped)");
                changed = true;
                continue;
            }
            List<ItemReward> items = new ArrayList<>();
            for (ItemReward r : q.rewards().items()) {
                if (checks.itemExists().test(r.item())) {
                    items.add(r);
                } else {
                    warnings.add("quest " + line.id() + "/" + q.id() + ": missing reward item " + r.item()
                            + " (skipped)");
                }
            }
            List<ItemReward> choices = new ArrayList<>();
            for (ItemReward r : q.choices()) {
                if (checks.itemExists().test(r.item())) {
                    choices.add(r);
                } else {
                    warnings.add("quest " + line.id() + "/" + q.id() + ": missing choice item " + r.item()
                            + " (skipped)");
                }
            }
            if (objectives.size() == q.objectives().size() && items.size() == q.rewards().items().size()
                    && choices.size() == q.choices().size()) {
                kept.add(q);
            } else {
                changed = true;
                kept.add(new Quest(q.id(), q.chapter(), q.title(), q.description(), q.start(), q.complete(),
                        List.copyOf(objectives), new Rewards(q.rewards().gold(), List.copyOf(items), q.rewards().xp()),
                        q.completeText(), List.copyOf(choices)));
            }
        }
        return changed ? new QuestLine(line.id(), line.name(), line.endText(), List.copyOf(kept), line.giver(),
                line.requires()) : line;
    }

    private static boolean exists(Objective o, RegistryChecks checks) {
        if (o.isTag()) return true;
        ResourceLocation rl = ResourceLocation.tryParse(o.target());
        return switch (o.type()) {
            case COLLECT -> rl != null && checks.itemExists().test(rl);
            case KILL -> rl != null && checks.entityExists().test(rl);
            case ADVANCEMENT, TALK -> true;
        };
    }
}
