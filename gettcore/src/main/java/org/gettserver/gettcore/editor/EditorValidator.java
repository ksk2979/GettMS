package org.gettserver.gettcore.editor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.gettserver.gettcore.data.ChoiceAction;
import org.gettserver.gettcore.data.DialogChoice;
import org.gettserver.gettcore.data.DialogDefinition;
import org.gettserver.gettcore.data.RegistryChecks;
import org.gettserver.gettcore.data.ShopDefinition;
import org.gettserver.gettcore.data.ShopEntry;
import org.gettserver.gettcore.quest.ItemReward;
import org.gettserver.gettcore.quest.Objective;
import org.gettserver.gettcore.quest.Quest;
import org.gettserver.gettcore.quest.QuestLine;
import org.gettserver.gettcore.quest.QuestPrune;
import org.gettserver.gettcore.quest.QuestTrigger;

/** Server-side gate for every editor save. Errors block the save; warnings are reported and cleaned away. */
public final class EditorValidator {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");
    /** QuestPrune's "quest line/id: " location prefix, rewritten to the dotted path style used here. */
    private static final Pattern PRUNE_PREFIX = Pattern.compile("^quest ([^/]+)/(\\S+): ");

    public record Result(List<String> errors, List<String> warnings, EditorBundle cleaned) {}

    private EditorValidator() {}

    public static Result validate(EditorBundle b, RegistryChecks checks) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Map<String, ShopDefinition> shops = new LinkedHashMap<>();
        b.shops().forEach((key, shop) -> {
            String p = "shops." + key;
            checkKey(p, key, shop.id(), errors);
            List<ShopEntry> kept = new ArrayList<>();
            for (int n = 0; n < shop.entries().size(); n++) {
                ShopEntry e = shop.entries().get(n);
                String ep = p + ".entries[" + n + "]";
                if (e.buyPrice() < 0 || e.sellPrice() < 0) errors.add(ep + ": price must be >= 0");
                if (!checks.itemExists().test(e.item())) {
                    warnings.add(ep + ": item " + e.item() + " is not installed on the server (removed)");
                } else {
                    kept.add(e);
                }
            }
            shops.put(key, kept.size() == shop.entries().size() ? shop
                    : new ShopDefinition(shop.id(), shop.name(), shop.tabs(), List.copyOf(kept)));
        });

        b.dialogs().forEach((key, d) -> validateDialog(key, d, b, errors));

        b.npcs().byName().forEach((name, dialog) -> {
            if (!b.dialogs().containsKey(dialog)) {
                errors.add("npcs.byName." + name + ": unknown dialog '" + dialog + "'");
            }
        });
        b.npcs().byUuid().forEach((uuid, dialog) -> {
            if (!b.dialogs().containsKey(dialog)) {
                errors.add("npcs.byUuid." + uuid + ": unknown dialog '" + dialog + "'");
            }
        });

        Map<String, QuestLine> quests = new LinkedHashMap<>();
        b.quests().forEach((key, line) -> {
            checkKey("quests." + key, key, line.id(), errors);
            validateLine(key, line, b, errors);
            List<String> pruneWarnings = new ArrayList<>();
            quests.put(key, QuestPrune.prune(line, checks, pruneWarnings));
            for (String w : pruneWarnings) warnings.add(PRUNE_PREFIX.matcher(w).replaceFirst("quests.$1.$2: "));
        });

        return new Result(errors, warnings, new EditorBundle(shops, b.dialogs(), b.npcs(), quests));
    }

    private static void checkKey(String path, String key, String id, List<String> errors) {
        if (!key.equals(id)) errors.add(path + ": key does not match inner id '" + id + "'");
        if (!ID.matcher(key).matches()) errors.add(path + ": id must match [a-z0-9_]+");
    }

    private static void validateDialog(String key, DialogDefinition d, EditorBundle b, List<String> errors) {
        String p = "dialogs." + key;
        checkKey(p, key, d.id(), errors);
        if (!d.nodes().containsKey(d.start())) errors.add(p + ".start: start node '" + d.start() + "' not found");
        d.nodes().forEach((nodeId, node) -> {
            for (int n = 0; n < node.choices().size(); n++) {
                DialogChoice c = node.choices().get(n);
                String cp = p + ".nodes." + nodeId + ".choices[" + n + "]";
                ChoiceAction a = c.action();
                String target = a.target().orElse(null);
                switch (a.type()) {
                    case NEXT -> {
                        if (target == null || !d.nodes().containsKey(target)) {
                            errors.add(cp + ": next node '" + target + "' not found");
                        }
                    }
                    case OPEN_SHOP_BUY, OPEN_SHOP_SELL -> {
                        if (target == null || !b.shops().containsKey(target)) {
                            errors.add(cp + ": shop '" + target + "' not found");
                        }
                    }
                    case QUEST -> {
                        String line = target == null ? "main" : target;
                        if (!b.quests().containsKey(line)) errors.add(cp + ": quest line '" + line + "' not found");
                    }
                    default -> { }
                }
            }
        });
    }

    private static void validateLine(String lineKey, QuestLine line, EditorBundle b, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (Quest q : line.quests()) {
            String p = "quests." + lineKey + "." + q.id();
            if (!seen.add(q.id())) errors.add(p + ": duplicate quest id");
            checkTrigger(p + ".start", q.start(), b, errors);
            checkTrigger(p + ".complete", q.complete(), b, errors);
            for (int n = 0; n < q.objectives().size(); n++) {
                Objective o = q.objectives().get(n);
                if (o.count() < 1) errors.add(p + ".objectives[" + n + "]: count must be >= 1");
            }
            if (q.rewards().gold() < 0) errors.add(p + ".rewards.gold: must be >= 0");
            if (q.rewards().xp() < 0) errors.add(p + ".rewards.xp: must be >= 0");
            for (int n = 0; n < q.rewards().items().size(); n++) {
                ItemReward r = q.rewards().items().get(n);
                if (r.count() < 1) errors.add(p + ".rewards.items[" + n + "]: count must be >= 1");
            }
        }
    }

    private static void checkTrigger(String path, QuestTrigger t, EditorBundle b, List<String> errors) {
        if (t.type() != QuestTrigger.Type.NPC) return;
        String npc = t.npc().orElse("");
        if (npc.isBlank()) {
            errors.add(path + ": npc trigger needs an npc name");
        } else if (!b.npcs().byName().containsKey(npc)) {
            errors.add(path + ": npc '" + npc + "' is not in npcs.byName");
        }
    }
}
