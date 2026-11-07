package org.gettserver.gettcore.editor;

import java.util.List;
import java.util.TreeSet;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.data.RegistryChecks;
import org.gettserver.gettcore.data.ShopDefinition;
import org.gettserver.gettcore.data.ShopEntry;
import org.gettserver.gettcore.quest.ItemReward;
import org.gettserver.gettcore.quest.Objective;
import org.gettserver.gettcore.quest.Quest;
import org.gettserver.gettcore.quest.QuestLine;

/** Ids used by a bundle that the given registries do not know (tags are never reported). Sorted, unique. */
public final class MissingRefs {
    private MissingRefs() {}

    public static List<String> items(EditorBundle bundle, RegistryChecks checks) {
        TreeSet<String> out = new TreeSet<>();
        for (ShopDefinition shop : bundle.shops().values()) {
            for (ShopEntry e : shop.entries()) check(e.item().toString(), checks, false, out);
        }
        for (QuestLine line : bundle.quests().values()) {
            for (Quest q : line.quests()) {
                for (Objective o : q.objectives()) {
                    if (o.type() == Objective.Type.COLLECT && !o.isTag()) check(o.target(), checks, false, out);
                }
                for (ItemReward r : q.rewards().items()) check(r.item().toString(), checks, false, out);
            }
        }
        return List.copyOf(out);
    }

    public static List<String> entities(EditorBundle bundle, RegistryChecks checks) {
        TreeSet<String> out = new TreeSet<>();
        for (QuestLine line : bundle.quests().values()) {
            for (Quest q : line.quests()) {
                for (Objective o : q.objectives()) {
                    if (o.type() == Objective.Type.KILL && !o.isTag()) check(o.target(), checks, true, out);
                }
            }
        }
        return List.copyOf(out);
    }

    private static void check(String id, RegistryChecks checks, boolean entity, TreeSet<String> out) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return; // malformed ids are the validator's business
        if (!(entity ? checks.entityExists() : checks.itemExists()).test(rl)) out.add(id);
    }
}
