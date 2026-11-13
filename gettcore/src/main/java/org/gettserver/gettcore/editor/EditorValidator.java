package org.gettserver.gettcore.editor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.data.ChoiceAction;
import org.gettserver.gettcore.data.DialogChoice;
import org.gettserver.gettcore.data.DialogDefinition;
import org.gettserver.gettcore.data.RegistryChecks;
import org.gettserver.gettcore.data.ShopDefinition;
import org.gettserver.gettcore.data.ShopEntry;
import org.gettserver.gettcore.equipment.EquipmentConfig;
import org.gettserver.gettcore.hunting.HuntingConfig;
import org.gettserver.gettcore.mobs.MobStatsConfig;
import org.gettserver.gettcore.pet.PetConfig;
import org.gettserver.gettcore.pet.PetSpecies;
import org.gettserver.gettcore.pet.boss.BossPoint;
import org.gettserver.gettcore.protect.ProtectConfig;
import org.gettserver.gettcore.quest.ItemReward;
import org.gettserver.gettcore.quest.Objective;
import org.gettserver.gettcore.quest.Quest;
import org.gettserver.gettcore.quest.QuestLine;
import org.gettserver.gettcore.quest.QuestPrune;
import org.gettserver.gettcore.quest.QuestTrigger;
import org.gettserver.gettcore.reinforce.ReinforceConfig;
import org.gettserver.gettcore.skill.BossConfig;
import org.gettserver.gettcore.skill.SkillSpec;
import org.gettserver.gettcore.upgrade.UpgradeConfig;
import org.gettserver.gettcore.detector.DetectorConfig;

/** Server-side gate for every editor save. Errors block the save; warnings are reported and cleaned away. */
public final class EditorValidator {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");
    /** QuestPrune's "quest line/id: " location prefix, rewritten to the dotted path style used here. */
    private static final Pattern PRUNE_PREFIX = Pattern.compile("^quest ([^/]+)/(\\S+): ");
    /** A day; BossPoint itself only requires >= 0. */
    static final int MAX_RESPAWN_MINUTES = 1440;

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
                    : shop.withEntries(List.copyOf(kept)));
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
            validateLine(key, line, b, errors, warnings);
            List<String> pruneWarnings = new ArrayList<>();
            quests.put(key, QuestPrune.prune(line, checks, pruneWarnings));
            for (String w : pruneWarnings) warnings.add(PRUNE_PREFIX.matcher(w).replaceFirst("quests.$1.$2: "));
        });

        validateBossPoints(b, errors);
        validatePetEntities(b, checks, errors, warnings);
        List<String> petWarnings = new ArrayList<>();
        PetConfig pets = b.pets().sanitized(petWarnings);
        for (String w : petWarnings) warnings.add(w.startsWith("pets.json: ") ? "pets: " + w.substring(11) : w);

        ProtectConfig protect = validateProtect(b.protect(), errors, warnings);

        List<String> reinforceWarnings = new ArrayList<>();
        ReinforceConfig reinforce = b.reinforce().sanitized(reinforceWarnings);
        String rp = ReinforceConfig.FILE + ": ";
        for (String w : reinforceWarnings) warnings.add(w.startsWith(rp) ? "reinforce: " + w.substring(rp.length()) : w);

        validateHunting(b.hunting(), checks, errors);
        List<String> huntingWarnings = new ArrayList<>();
        HuntingConfig hunting = b.hunting().sanitized(huntingWarnings);
        addPrefixed(huntingWarnings, HuntingConfig.FILE, "hunting", warnings);
        b.mobs().overrides().keySet().forEach(key -> {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id != null && !checks.entityExists().test(id)) {
                warnings.add("mobs: " + key + " is not a registered entity");
            }
        });
        List<String> mobWarnings = new ArrayList<>();
        MobStatsConfig mobs = b.mobs().sanitized(mobWarnings);
        addPrefixed(mobWarnings, MobStatsConfig.FILE, "mobs", warnings);

        // Out-of-range values are errors here (the load path clamps them); an unknown item only warns (kept so a
        // removed mod's overrides come back with it).
        for (String e : b.equipment().rangeErrors()) errors.add("equipment.items." + e);
        b.equipment().items().keySet().forEach(key -> {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id != null && !checks.itemExists().test(id)) {
                warnings.add("equipment: " + key + " is not a registered item");
            }
        });
        List<String> equipmentWarnings = new ArrayList<>();
        EquipmentConfig equipment = b.equipment().sanitized(equipmentWarnings);
        addPrefixed(equipmentWarnings, EquipmentConfig.FILE, "equipment", warnings);

        // Bad ids, count/gold, duplicate from and cycles block the save; an unregistered item only warns (kept so a
        // removed mod's chains come back with it; the server skips such a step at use time).
        for (String e : b.upgrade().errors()) errors.add("upgrade." + e);
        b.upgrade().itemIds().stream().sorted().forEach(key -> {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id != null && !checks.itemExists().test(id)) {
                warnings.add("upgrade: " + key + " is not a registered item");
            }
        });
        UpgradeConfig upgrade = b.upgrade().sanitized(new ArrayList<>());

        // Same for detector.json: bad targets block the save, an unregistered item/block only warns.
        for (String e : b.detector().errors()) errors.add("detector." + e);
        b.detector().targets().forEach(t -> {
            for (String key : t.items()) {
                ResourceLocation id = ResourceLocation.tryParse(key);
                if (id != null && !checks.itemExists().test(id)) {
                    warnings.add("detector: " + key + " is not a registered item");
                }
            }
            for (String key : t.blocks()) {
                ResourceLocation id = ResourceLocation.tryParse(key);
                if (id != null && !checks.blockExists().test(id)) {
                    warnings.add("detector: " + key + " is not a registered block");
                }
            }
        });
        List<String> detectorWarnings = new ArrayList<>();
        DetectorConfig detector = b.detector().sanitized(detectorWarnings);
        addPrefixed(detectorWarnings, DetectorConfig.FILE, "detector", warnings);

        // Anything the load path would drop or clamp blocks the save here.
        b.bosses().bosses().forEach((key, boss) -> validateBoss("bosses." + key, boss, errors));
        List<String> bossWarnings = new ArrayList<>();
        BossConfig bosses = b.bosses().sanitized(bossWarnings);
        addPrefixed(bossWarnings, BossConfig.FILE, "bosses", warnings);

        return new Result(errors, warnings,
                new EditorBundle(shops, b.dialogs(), b.npcs(), quests, b.bossPoints(), pets, protect, reinforce,
                        hunting, mobs, equipment, upgrade, bosses, detector));
    }

    /** Ranges as in {@link BossConfig.Boss}'s / {@link SkillSpec}'s sanitizing, plus the cross-field rules. */
    private static void validateBoss(String p, BossConfig.Boss b, List<String> errors) {
        within(p, "searchRange", b.searchRange(), 0, 128, errors);
        within(p, "releaseRange", b.releaseRange(), 0, 128, errors);
        within(p, "reaggroRange", b.reaggroRange(), 0, 128, errors);
        within(p, "calmSeconds", b.calmSeconds(), 0, 3600, errors);
        b.speed().ifPresent(v -> within(p, "speed", v, 0, 1024, errors));
        b.armor().ifPresent(v -> within(p, "armor", v, 0, 30, errors));
        within(p, "preferredDistance", b.preferredDistance(), 0, 128, errors);
        within(p, "shoutRange", b.shoutRange(), 0, 128, errors);
        within(p, "enrageBelow", b.enrageBelow(), 0, 1, errors);
        within(p, "enrageCooldownMult", b.enrageCooldownMult(), 0.1, 1, errors);
        // Only 후공 bosses re-acquire by range (the editor hides that field for 선공).
        if (b.aggro() == BossConfig.Aggro.AFTER && b.releaseRange() < b.reaggroRange()) {
            errors.add(p + ": 해제 거리 " + num(b.releaseRange()) + " 이 다시 잡히는 거리 " + num(b.reaggroRange())
                    + " 보다 작습니다");
        }
        validateSkills(p, b.skills(), errors);
    }

    /** Per-skill checks shared by boss and hunting-variant skill lists. */
    private static void validateSkills(String p, List<SkillSpec> skills, List<String> errors) {
        Set<String> ids = new HashSet<>();
        for (int n = 0; n < skills.size(); n++) {
            SkillSpec s = skills.get(n);
            String sp = p + ".skills[" + n + "]";
            if (!s.knownType()) {
                errors.add(sp + ": 알 수 없는 스킬 종류 '" + s.type() + "' (" + SkillSpec.WIND_LINE + " 또는 "
                        + SkillSpec.STOMP + ")");
                continue;
            }
            if (s.id().isBlank()) {
                errors.add(sp + ": 스킬 id 가 비어 있습니다");
            } else if (!ids.add(s.id())) {
                errors.add(sp + ": 스킬 id '" + s.id() + "' 가 겹칩니다");
            }
            if (s.shout() && s.shoutText().isBlank()) errors.add(sp + ": 외침이 켜져 있으면 외칠 글자가 필요합니다");
            within(sp, "damage", s.damage(), 0, 2048, errors);
            within(sp, "knockback", s.knockback(), 0, 10, errors);
            within(sp, "minRange", s.minRange(), 0, 64, errors);
            within(sp, "maxRange", s.maxRange(), 0, 64, errors);
            if (s.minRange() > s.maxRange()) {
                errors.add(sp + ": 최소 거리 " + num(s.minRange()) + " 가 최대 거리 " + num(s.maxRange()) + " 보다 큽니다");
            }
            within(sp, "chargeSeconds", s.chargeSeconds(), 0.2, 10, errors);
            within(sp, "cooldownSeconds", s.cooldownSeconds(), 0.5, 600, errors);
            // Only the shape fields the type uses (a stomp has no length, a line no radius).
            if (SkillSpec.WIND_LINE.equals(s.type()) || SkillSpec.CHARGE.equals(s.type())) {
                within(sp, "shape.length", s.shape().length(), 1, 64, errors);
                within(sp, "shape.width", s.shape().width(), 0.5, 32, errors);
                // A charge's radius is how far it runs past its aim point (0 = stops there).
                if (SkillSpec.CHARGE.equals(s.type())) within(sp, "shape.radius", s.shape().radius(), 0, 32, errors);
            } else {
                within(sp, "shape.radius", s.shape().radius(), 0.5, 32, errors);
            }
            if (SkillSpec.SHOCKWAVE.equals(s.type()) || SkillSpec.CHARGE.equals(s.type())) {
                within(sp, "shape.speed", s.shape().speed(), 1, 100, errors);
            }
            within(sp, "aftershock.damage", s.aftershock().damage(), 0, 2048, errors);
            within(sp, "aftershock.delaySeconds", s.aftershock().delaySeconds(), 0, 10, errors);
            within(sp, "aftershock.width", s.aftershock().width(), 0, 32, errors);
            within(sp, "terrain.depth", s.terrain().depth(), 0, 16, errors);
            within(sp, "terrain.width", s.terrain().width(), 0, 32, errors);
            within(sp, "farTargetChance", s.farTargetChance(), 0, 1, errors);
        }
    }

    private static void within(String path, String field, double v, double min, double max, List<String> errors) {
        if (Double.isNaN(v) || v < min || v > max) {
            errors.add(path + ": " + field + " " + num(v) + " 은(는) " + num(min) + "~" + num(max) + " 사이여야 합니다");
        }
    }

    /** 12.0 as "12", 1.5 as "1.5". */
    private static String num(double v) {
        return v == Math.rint(v) && !Double.isInfinite(v) ? Long.toString((long) v) : Double.toString(v);
    }

    /** Adds {@code from}'s warnings to {@code out}, "{@code file}: ..." rewritten to "{@code section}: ...". */
    private static void addPrefixed(List<String> from, String file, String section, List<String> out) {
        String prefix = file + ": ";
        for (String w : from) out.add(w.startsWith(prefix) ? section + ": " + w.substring(prefix.length()) : w);
    }

    /** Zone ids and entry names non-empty and unique, entries' entities registered (errors). */
    private static void validateHunting(HuntingConfig c, RegistryChecks checks, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (int n = 0; n < c.zones().size(); n++) {
            HuntingConfig.Zone z = c.zones().get(n);
            if (z.id().isBlank()) {
                errors.add("hunting.zones[" + n + "]: id must not be empty");
                continue;
            }
            String p = "hunting.zones." + z.id();
            if (!seen.add(z.id())) errors.add(p + ": duplicate id");
            Set<String> names = new HashSet<>();
            for (int i = 0; i < z.entries().size(); i++) {
                HuntingConfig.Entry e = z.entries().get(i);
                if (e.name().isBlank()) {
                    errors.add(p + ".entries[" + i + "]: name must not be empty");
                    continue;
                }
                String ep = p + ".entries." + e.name();
                if (!names.add(e.name())) errors.add(ep + ": duplicate name");
                if (!checks.entityExists().test(e.entity())) {
                    errors.add(ep + ": entity " + e.entity() + " is not a registered entity");
                }
                validateSkills(ep, e.skills(), errors);
            }
        }
    }

    /** Region ids non-empty and unique (errors); min/max swapped per axis where needed (warning). */
    private static ProtectConfig validateProtect(ProtectConfig c, List<String> errors, List<String> warnings) {
        Set<String> seen = new HashSet<>();
        List<ProtectConfig.Region> out = new ArrayList<>();
        boolean changed = false;
        for (int n = 0; n < c.regions().size(); n++) {
            ProtectConfig.Region r = c.regions().get(n);
            if (r.id().isBlank()) {
                errors.add("protect.regions[" + n + "]: id must not be empty");
                continue;
            }
            String p = "protect.regions." + r.id();
            if (!seen.add(r.id())) errors.add(p + ": duplicate id");
            ProtectConfig.Region norm = r.normalized();
            if (norm != r) {
                warnings.add(p + ": min/max swapped to " + norm.min().toShortString() + " ~ "
                        + norm.max().toShortString());
                changed = true;
            }
            out.add(norm);
        }
        return changed ? c.withRegions(out) : c;
    }

    /** Each species' petEntity must be a registered entity (error); the wild key itself should be one (warning). */
    private static void validatePetEntities(EditorBundle b, RegistryChecks checks, List<String> errors,
                                            List<String> warnings) {
        b.pets().species().forEach((key, s) -> {
            String p = "pets." + key;
            if (!checks.entityExists().test(s.petEntity())) {
                errors.add(p + ": petEntity '" + s.petEntity() + "' is not a registered entity");
            }
            ResourceLocation wild = ResourceLocation.tryParse(key);
            if (wild == null || !checks.entityExists().test(wild)) {
                warnings.add(p + ": species '" + key + "' is not a registered entity");
            }
        });
    }

    /** Ids non-empty and unique, ranges as in BossPoint (respawn capped at a day), bossType a boss species. */
    private static void validateBossPoints(EditorBundle b, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (int n = 0; n < b.bossPoints().size(); n++) {
            BossPoint pt = b.bossPoints().get(n);
            if (pt.id().isBlank()) {
                errors.add("boss_points[" + n + "]: id must not be empty");
                continue;
            }
            String p = "boss_points." + pt.id();
            if (!seen.add(pt.id())) errors.add(p + ": duplicate id");
            range(p, "radius", pt.radius(), BossPoint.MIN_RADIUS, BossPoint.MAX_RADIUS, errors);
            range(p, "maxCount", pt.maxCount(), 0, BossPoint.MAX_COUNT, errors);
            range(p, "respawnMinutes", pt.respawnMinutes(), 0, MAX_RESPAWN_MINUTES, errors);
            PetSpecies s = b.pets().species().get(pt.bossType().toString());
            if (s == null || !s.boss()) {
                errors.add(p + ": bossType " + pt.bossType() + " is not a boss species in pets.species");
            }
        }
    }

    private static void range(String path, String field, int v, int min, int max, List<String> errors) {
        if (v < min || v > max) errors.add(path + ": " + field + " " + v + " must be within " + min + ".." + max);
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

    private static void validateLine(String lineKey, QuestLine line, EditorBundle b, List<String> errors,
                                     List<String> warnings) {
        String lp = "quests." + lineKey;
        line.giver().ifPresent(giver -> {
            if (lineKey.equals("main")) {
                errors.add(lp + ": main line cannot have a giver");
            } else if (giver.isBlank()) {
                errors.add(lp + ".giver: giver needs an npc name");
            } else if (!b.npcs().byName().containsKey(giver)) {
                errors.add(lp + ".giver: npc '" + giver + "' is not in npcs.byName");
            }
        });
        // requires 가 틀려도 저장은 허용한다(서버는 조건 미충족으로 취급) — 경고만.
        line.requires().ifPresent(req -> {
            int colon = req.indexOf(':');
            if (colon <= 0 || colon == req.length() - 1) {
                warnings.add(lp + ".requires: '" + req + "' must be lineId:questId");
                return;
            }
            QuestLine target = b.quests().get(req.substring(0, colon));
            String questId = req.substring(colon + 1);
            if (target == null || target.quests().stream().noneMatch(q -> q.id().equals(questId))) {
                warnings.add(lp + ".requires: quest '" + req + "' not found");
            }
        });
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
            if (q.complete().type() == QuestTrigger.Type.AUTO && !q.choices().isEmpty()) {
                errors.add(p + ": choices need an npc report (complete must be npc)");
            }
            for (int n = 0; n < q.choices().size(); n++) {
                if (q.choices().get(n).count() < 1) errors.add(p + ".choices[" + n + "]: count must be >= 1");
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
