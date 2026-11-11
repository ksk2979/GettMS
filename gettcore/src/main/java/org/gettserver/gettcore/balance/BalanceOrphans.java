package org.gettserver.gettcore.balance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

/**
 * Copies in our pack whose original is gone (the mod was removed, or an update renamed / dropped the file). The
 * normal tabs only list files that have an original, so these would be invisible and never cleaned up; the editor
 * shows them as "원본 없음 · 삭제" rows and a save can remove them.
 *
 * <p>Catalog: {@code datapackCatalog.orphans = [{kind, id}]}. Save: {@code datapack.removeOrphans = [{kind, id}]};
 * only entries that are orphans at save time are deleted (a request cannot delete a copy that has an original).
 */
public final class BalanceOrphans {
    public static final String CATALOG_KEY = "orphans";
    public static final String REMOVE_KEY = "removeOrphans";
    static final List<String> KINDS = List.of(BalanceService.GUNS, BalanceService.EPICFIGHT,
            BalanceService.SPAWN_RULES, BalanceService.SPAWN_MOBS, BalanceService.RECIPES, BalanceService.FISHING);

    public record Orphan(String kind, String id, ResourceLocation file) {}

    private BalanceOrphans() {}

    /**
     * @param originalFiles kind (guns / epicfight / spawnRules / recipes / fishing) -> the files the mods currently provide
     * @param entityExists  whether a mob id is registered (spawnMobs tweaks for unknown mobs are orphans)
     */
    public static List<Orphan> find(Path pack, Map<String, Set<ResourceLocation>> originalFiles,
                                    Predicate<ResourceLocation> entityExists) {
        List<Orphan> out = new ArrayList<>();
        Set<ResourceLocation> guns = originalFiles.getOrDefault(BalanceService.GUNS, Set.of());
        for (ResourceLocation f : PackFiles.listFolder(pack, GunBalance.FOLDER)) {
            if (!guns.contains(f)) out.add(new Orphan(BalanceService.GUNS, GunBalance.idOf(f), f));
        }
        Set<ResourceLocation> ef = originalFiles.getOrDefault(BalanceService.EPICFIGHT, Set.of());
        for (ResourceLocation f : PackFiles.listFolder(pack, EpicFightBalance.FOLDER)) {
            String item = EpicFightBalance.itemOf(f);
            if (item != null && !ef.contains(f)) out.add(new Orphan(BalanceService.EPICFIGHT, item, f));
        }
        Set<ResourceLocation> rules = originalFiles.getOrDefault(BalanceService.SPAWN_RULES, Set.of());
        for (ResourceLocation f : PackFiles.listFolder(pack, SpawnBalance.FOLDER)) {
            if (SpawnBalance.isTweakFile(f) || rules.contains(f)) continue;
            String id = SpawnBalance.ruleIdOf(f);
            if (id != null) out.add(new Orphan(BalanceService.SPAWN_RULES, id, f));
        }
        Set<ResourceLocation> recipes = originalFiles.getOrDefault(BalanceService.RECIPES, Set.of());
        for (ResourceLocation f : PackFiles.listFolder(pack, RecipeBalance.FOLDER)) {
            String id = RecipeBalance.idOf(f);
            if (id != null && !recipes.contains(f)) out.add(new Orphan(BalanceService.RECIPES, id, f));
        }
        Set<ResourceLocation> fishing = originalFiles.getOrDefault(BalanceService.FISHING, Set.of());
        for (String folder : List.of(FishingBalance.FISH_FOLDER, FishingBalance.LOOT_FOLDER, FishingBalance.CRATE_FOLDER,
                FishingBalance.TABLE_FOLDER)) {
            for (ResourceLocation f : PackFiles.listFolder(pack, folder)) {
                if (!fishing.contains(f)) out.add(new Orphan(BalanceService.FISHING, FishingBalance.idOf(f), f));
            }
        }
        for (String mob : SpawnBalance.mobState(pack).keySet()) {
            ResourceLocation e = ResourceLocation.tryParse(mob);
            ResourceLocation f = SpawnBalance.tweakFileOf(e);
            if (f != null && !entityExists.test(e)) out.add(new Orphan(BalanceService.SPAWN_MOBS, mob, f));
        }
        return out;
    }

    public static JsonArray toJson(List<Orphan> orphans) {
        JsonArray out = new JsonArray();
        for (Orphan o : orphans) {
            JsonObject r = new JsonObject();
            r.addProperty("kind", o.kind());
            r.addProperty("id", o.id());
            out.add(r);
        }
        return out;
    }

    /** Errors for a {@code removeOrphans} value (null = absent = fine). */
    public static List<String> validate(JsonElement req) {
        List<String> errs = new ArrayList<>();
        if (req == null || req.isJsonNull()) return errs;
        if (!req.isJsonArray()) {
            errs.add(BalanceService.STATE + "." + REMOVE_KEY + ": 목록이어야 합니다");
            return errs;
        }
        int i = 0;
        for (JsonElement e : req.getAsJsonArray()) {
            i++;
            if (!e.isJsonObject() || !(e.getAsJsonObject().get("kind") instanceof com.google.gson.JsonPrimitive k)
                    || !(e.getAsJsonObject().get("id") instanceof com.google.gson.JsonPrimitive id)
                    || !KINDS.contains(k.getAsString()) || id.getAsString().isEmpty()) {
                errs.add("원본 없는 사본 삭제 " + i + "번: {kind, id} 형식이 아닙니다");
            }
        }
        return errs;
    }

    /** Deletes the requested copies that are in {@code current}; returns how many files were removed. */
    public static int remove(Path pack, List<Orphan> current, JsonArray req) throws IOException {
        int n = 0;
        for (JsonElement e : req) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String kind = o.has("kind") ? o.get("kind").getAsString() : "";
            String id = o.has("id") ? o.get("id").getAsString() : "";
            for (Orphan c : current) {
                if (c.kind().equals(kind) && c.id().equals(id) && PackFiles.delete(pack, c.file())) n++;
            }
        }
        return n;
    }
}
