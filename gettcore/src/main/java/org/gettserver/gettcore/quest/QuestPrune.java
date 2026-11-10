package org.gettserver.gettcore.quest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.data.RegistryChecks;

/** Builds the in-memory view of a quest line without references to items/entities missing from the registry. */
public final class QuestPrune {
    private QuestPrune() {}

    /**
     * 손으로 고친 파일이 편집기 규칙을 어긴 경우의 로드 시 보정: 완료가 AUTO 인 퀘스트의 고르는 보상 제거,
     * main 줄의 giver 무시, 형식이 틀린 requires 경고.
     */
    public static QuestLine sanitize(QuestLine line, List<String> warnings) {
        boolean changed = false;
        List<Quest> quests = new ArrayList<>();
        for (Quest q : line.quests()) {
            if (q.complete().type() == QuestTrigger.Type.AUTO && !q.choices().isEmpty()) {
                warnings.add("quest " + line.id() + "/" + q.id()
                        + ": choices need an NPC complete trigger (choices ignored)");
                q = new Quest(q.id(), q.chapter(), q.title(), q.description(), q.start(), q.complete(),
                        q.objectives(), q.rewards(), q.completeText(), List.of());
                changed = true;
            }
            quests.add(q);
        }
        Optional<String> giver = line.giver();
        if ("main".equals(line.id()) && giver.isPresent()) {
            warnings.add("quest line main: giver is not allowed on the main line (giver ignored)");
            giver = Optional.empty();
            changed = true;
        }
        line.requires().ifPresent(r -> {
            int i = r.indexOf(':');
            if (i <= 0 || i >= r.length() - 1 || r.indexOf(':', i + 1) >= 0) {
                warnings.add("quest line " + line.id() + ": requires '" + r + "' is not lineId:questId");
            }
        });
        return changed ? new QuestLine(line.id(), line.name(), line.endText(), List.copyOf(quests), giver,
                line.requires()) : line;
    }

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
        if (o.type() == Objective.Type.KILL && Objective.ANY_MONSTER.equals(o.target())) return true;
        ResourceLocation rl = ResourceLocation.tryParse(o.target());
        return switch (o.type()) {
            case COLLECT -> rl != null && checks.itemExists().test(rl);
            case KILL -> rl != null && checks.entityExists().test(rl);
            case ADVANCEMENT, TALK -> true;
        };
    }
}
