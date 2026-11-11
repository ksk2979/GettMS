package org.gettserver.gettcore.balance;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.gettserver.gettcore.GettCore;

/**
 * Editor side of the balance data pack: what the server sends (original values + our current overrides) and how a
 * save is applied (copies written/deleted, pack enabled, /reload when gun or Epic Fight data changed).
 * Server thread only.
 *
 * <p>Snapshot sections: {@code datapack} (editable overrides, sent back on save) and {@code datapackCatalog}
 * (read-only original values). A save without a {@code datapack} section leaves the pack untouched.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class BalanceService {
    public static final String STATE = "datapack";
    public static final String CATALOG = "datapackCatalog";
    public static final String GUNS = "guns";
    public static final String SPAWN_RULES = "spawnRules";
    public static final String SPAWN_MOBS = "spawnMobs";
    public static final String EPICFIGHT = "epicfight";
    public static final String RECIPES = "recipes";
    public static final String FISHING = "fishing";

    /** Spawn copies changed since this server started: they wait for a restart (biome modifiers load once). */
    private static volatile boolean spawnRestartPending;

    public record Applied(boolean reloaded, List<String> messages) {}

    private BalanceService() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        spawnRestartPending = false;
        // Old spawn rule copies (plain neoforge:add_spawns) loaded fine this time; rewrite them to our tolerant type
        // now, so a later removal of their mod cannot stop the world from loading (same spawns, no restart needed).
        try {
            int n = SpawnBalance.migrateLegacyRuleCopies(packDir(event.getServer()));
            if (n > 0) GettCore.LOGGER.info("balance: {} spawn rule copies switched to {}", n, SpawnBalance.SAFE_ADD_SPAWNS);
        } catch (IOException | RuntimeException e) {
            GettCore.LOGGER.warn("balance: could not migrate spawn rule copies", e);
        }
    }

    /** Copies whose original is gone (see {@link BalanceOrphans}). */
    static List<BalanceOrphans.Orphan> orphans(MinecraftServer server) {
        ResourceManager rm = server.getResourceManager();
        Map<String, java.util.Set<ResourceLocation>> files = Map.of(
                GUNS, originals(rm, GunBalance.FOLDER).keySet(),
                EPICFIGHT, originals(rm, EpicFightBalance.FOLDER).keySet(),
                SPAWN_RULES, originals(rm, SpawnBalance.FOLDER).keySet(),
                RECIPES, originalFiles(rm, RecipeBalance.FOLDER),
                FISHING, fishingFiles(rm));
        return BalanceOrphans.find(packDir(server), files,
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE::containsKey);
    }

    public static Path packDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PackFiles.PACK_NAME);
    }

    public static boolean packEnabled(MinecraftServer server) {
        return server.getPackRepository().getSelectedIds().contains(PackFiles.PACK_ID);
    }

    /**
     * The mods' own versions of every .json under {@code data/<ns>/<folder>/}: for each file the topmost one that is
     * not from our pack (so our copies never show up as "original"). Unreadable files are skipped.
     */
    public static Map<ResourceLocation, JsonObject> originals(ResourceManager rm, String folder) {
        Map<ResourceLocation, JsonObject> out = new HashMap<>();
        for (Map.Entry<ResourceLocation, List<Resource>> e
                : rm.listResourceStacks(folder, l -> l.getPath().endsWith(".json")).entrySet()) {
            List<Resource> stack = e.getValue();
            int i = PackFiles.originalIndex(stack.stream().map(Resource::sourcePackId).toList());
            if (i < 0) continue;
            try (Reader r = stack.get(i).openAsReader()) {
                JsonElement el = JsonParser.parseReader(r);
                if (el.isJsonObject()) out.put(e.getKey(), el.getAsJsonObject());
            } catch (IOException | RuntimeException ex) {
                GettCore.LOGGER.debug("balance: skipped unreadable {}: {}", e.getKey(), ex.toString());
            }
        }
        return out;
    }

    /** One file's original (topmost version that is not from our pack), or null. */
    static JsonObject originalOf(ResourceManager rm, ResourceLocation file) {
        List<Resource> stack = rm.getResourceStack(file);
        int i = PackFiles.originalIndex(stack.stream().map(Resource::sourcePackId).toList());
        if (i < 0) return null;
        try (Reader r = stack.get(i).openAsReader()) {
            JsonElement el = JsonParser.parseReader(r);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (IOException | RuntimeException ex) {
            GettCore.LOGGER.debug("balance: skipped unreadable {}: {}", file, ex.toString());
            return null;
        }
    }

    /** Tide's fishing data files and the loot tables its junk / treasure groups use. Empty without Tide. */
    static FishingBalance.Originals fishingOriginals(ResourceManager rm) {
        Map<ResourceLocation, JsonObject> loot = originals(rm, FishingBalance.LOOT_FOLDER);
        Map<String, JsonObject> tables = new HashMap<>();
        for (String id : FishingBalance.tableIds(loot)) {
            ResourceLocation file = FishingBalance.tableFile(id);
            JsonObject table = file == null ? null : originalOf(rm, file);
            if (table != null) tables.put(id, table);
        }
        return new FishingBalance.Originals(originals(rm, FishingBalance.FISH_FOLDER), loot,
                originals(rm, FishingBalance.CRATE_FOLDER), tables);
    }

    /** Every file a fishing copy can stand for (data files + all loot tables), for the orphan check. */
    private static java.util.Set<ResourceLocation> fishingFiles(ResourceManager rm) {
        java.util.Set<ResourceLocation> out = new java.util.HashSet<>();
        for (FishingBalance.Kind k : FishingBalance.KINDS) out.addAll(originalFiles(rm, k.folder()));
        out.addAll(originalFiles(rm, FishingBalance.TABLE_FOLDER));
        return out;
    }

    private static boolean itemExists(ResourceLocation id) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id);
    }

    /** Like {@link #originals} but only which files exist (no parsing): for the orphan check of large folders. */
    static java.util.Set<ResourceLocation> originalFiles(ResourceManager rm, String folder) {
        java.util.Set<ResourceLocation> out = new java.util.HashSet<>();
        rm.listResourceStacks(folder, l -> l.getPath().endsWith(".json")).forEach((loc, stack) -> {
            if (PackFiles.originalIndex(stack.stream().map(Resource::sourcePackId).toList()) >= 0) out.add(loc);
        });
        return out;
    }

    /** Adds the two sections to an editor snapshot. On failure the snapshot goes out without them. */
    public static String withSections(String snapshotJson, MinecraftServer server) {
        try {
            JsonObject root = JsonParser.parseString(snapshotJson).getAsJsonObject();
            Map<ResourceLocation, JsonObject> guns = originals(server.getResourceManager(), GunBalance.FOLDER);
            Map<ResourceLocation, JsonObject> mods = originals(server.getResourceManager(), SpawnBalance.FOLDER);
            Map<ResourceLocation, JsonObject> ef = originals(server.getResourceManager(), EpicFightBalance.FOLDER);
            Map<ResourceLocation, JsonObject> recipes = originals(server.getResourceManager(), RecipeBalance.FOLDER);
            FishingBalance.Originals fishing = fishingOriginals(server.getResourceManager());
            Path pack = packDir(server);
            JsonObject state = new JsonObject();
            state.add(FISHING, FishingBalance.state(fishing, pack));
            state.add(RECIPES, RecipeBalance.state(recipes, RecipeItems::kindOf, pack));
            state.add(GUNS, GunBalance.state(guns, pack));
            state.add(EPICFIGHT, EpicFightBalance.state(ef, pack));
            state.add(SPAWN_RULES, SpawnBalance.ruleState(mods, pack));
            JsonObject mobState = SpawnBalance.mobState(pack);
            state.add(SPAWN_MOBS, mobState);
            List<BalanceOrphans.Orphan> orphans = orphans(server);
            JsonObject cat = new JsonObject();
            cat.addProperty("packEnabled", packEnabled(server));
            cat.addProperty("spawnRestartPending", spawnRestartPending);
            cat.add(GUNS, GunBalance.catalog(guns));
            cat.add(FISHING, FishingBalance.catalog(fishing, BalanceService::itemExists));
            cat.add(EPICFIGHT, EpicFightBalance.catalog(ef));
            cat.add(RECIPES, RecipeBalance.catalog(RecipeBalance.rows(recipes, RecipeItems::kindOf, pack)));
            cat.add(SPAWN_RULES, SpawnBalance.ruleCatalog(mods));
            List<String> extraMobs = new ArrayList<>(mobState.keySet());
            orphans.forEach(o -> {
                if (o.kind().equals(SPAWN_MOBS)) extraMobs.remove(o.id());   // shown as an orphan row instead
            });
            cat.add(SPAWN_MOBS, SpawnBalance.mobRows(liveSpawns(server), extraMobs));
            cat.add(BalanceOrphans.CATALOG_KEY, BalanceOrphans.toJson(orphans));
            return addSections(root, state, cat);
        } catch (RuntimeException e) {
            GettCore.LOGGER.error("balance: editor sections could not be built", e);
            return snapshotJson;
        }
    }

    public static String addSections(JsonObject root, JsonObject state, JsonObject catalog) {
        root.add(STATE, state);
        root.add(CATALOG, catalog);
        return root.toString();
    }

    /** The {@code datapack} section of a save, or null when absent / not an object (pack is then left alone). */
    public static JsonObject desiredOf(String bundleJson) {
        try {
            JsonElement root = JsonParser.parseString(bundleJson);
            if (!root.isJsonObject()) return null;
            JsonElement d = root.getAsJsonObject().get(STATE);
            return d != null && d.isJsonObject() ? d.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static List<String> validate(JsonObject desired) {
        return validate(desired, Map.of());
    }

    /** {@code spawnOriginals}: the mods' biome modifier files, so spawn rule edits are checked merged with them. */
    public static List<String> validate(JsonObject desired, Map<ResourceLocation, JsonObject> spawnOriginals) {
        List<String> errs = new ArrayList<>();
        JsonObject guns = section(desired, GUNS, errs);
        if (guns != null) errs.addAll(GunBalance.validate(guns));
        JsonObject rules = section(desired, SPAWN_RULES, errs);
        if (rules != null) errs.addAll(SpawnBalance.validateRules(spawnOriginals, rules));
        JsonObject mobs = section(desired, SPAWN_MOBS, errs);
        if (mobs != null) errs.addAll(SpawnBalance.validateMobs(mobs));
        JsonObject ef = section(desired, EPICFIGHT, errs);
        if (ef != null) errs.addAll(EpicFightBalance.validate(ef));
        JsonObject recipes = section(desired, RECIPES, errs);
        if (recipes != null) errs.addAll(RecipeBalance.validate(recipes));
        JsonObject fishing = section(desired, FISHING, errs);
        if (fishing != null) errs.addAll(FishingBalance.validate(fishing));
        errs.addAll(BalanceOrphans.validate(desired.get(BalanceOrphans.REMOVE_KEY)));
        return errs;
    }

    /** Writes/deletes copies to match {@code desired}, then enables the pack and reloads when needed. */
    public static Applied apply(MinecraftServer server, JsonObject desired) throws IOException {
        List<String> msgs = new ArrayList<>();
        Path pack = packDir(server);
        boolean gunsChanged = false;
        JsonObject guns = section(desired, GUNS, new ArrayList<>());
        if (guns != null) {
            GunBalance.Outcome o = GunBalance.reconcile(
                    originals(server.getResourceManager(), GunBalance.FOLDER), pack, guns);
            msgs.addAll(o.warnings());
            gunsChanged = o.changed();
            // NTGL re-sends every weapon config to all players on the datapack sync that ends /reload
            // (NetworkManagerHandler.onDatapackSync -> S2CMessageUpdateWeapons -> IWeapon.setConfig), and reads it
            // live every tick (no per-stack cache for normal guns), so a held gun changes without re-equipping.
            if (gunsChanged) {
                msgs.add("총 수치: 사본 " + o.written() + "개 저장, " + o.deleted() + "개 삭제(원래대로)"
                        + " — /reload 가 끝나면 NTGL 이 접속자에게 총 설정을 다시 보내 손에 든 총도 바로 바뀝니다"
                        + " (다시 들기·재접속 불필요, 이미 진행 중인 장전 한 번은 예전 시간으로 끝남)."
                        + " 단 부착물이 장전 방식·시간을 고정하는 총(예: 리볼버 탄띠)은 그 값이 우선합니다");
            }
        }
        boolean efChanged = false;
        JsonObject ef = section(desired, EPICFIGHT, new ArrayList<>());
        if (ef != null) {
            EpicFightBalance.Outcome o = EpicFightBalance.reconcile(
                    originals(server.getResourceManager(), EpicFightBalance.FOLDER), pack, ef);
            msgs.addAll(o.warnings());
            efChanged = o.changed();
            if (efChanged) {
                msgs.add("Epic Fight 무기 특성: 사본 " + o.written() + "개 저장, " + o.deleted() + "개 삭제(원래대로)"
                        + " — /reload 뒤 접속자에게 다시 보내집니다. 손에 든 무기는 다시 들어야 반영될 수 있습니다");
            }
        }
        boolean recipesChanged = false;
        JsonObject recipes = section(desired, RECIPES, new ArrayList<>());
        if (recipes != null) {
            RecipeBalance.Outcome o = RecipeBalance.reconcile(
                    originals(server.getResourceManager(), RecipeBalance.FOLDER), RecipeItems::kindOf, pack, recipes);
            msgs.addAll(o.warnings());
            recipesChanged = o.changed();
            if (recipesChanged) {
                msgs.add("제작법: 막는 사본 " + o.written() + "개 저장, " + o.deleted() + "개 삭제(다시 제작 가능)"
                        + " — /reload 뒤 막은 제작법은 작업대·대장장이 작업대 등에서 더는 만들어지지 않습니다."
                        + " 이미 가진 장비와 상점·보상으로 받는 장비는 그대로입니다");
            }
        }
        boolean fishingChanged = false;
        JsonObject fishing = section(desired, FISHING, new ArrayList<>());
        if (fishing != null) {
            FishingBalance.Outcome o = FishingBalance.reconcile(fishingOriginals(server.getResourceManager()), pack,
                    fishing, BalanceService::itemExists);
            msgs.addAll(o.warnings());
            fishingChanged = o.changed();
            if (fishingChanged) {
                msgs.add("낚시: 사본 " + o.written() + "개 저장, " + o.deleted() + "개 삭제(원래대로)"
                        + " — /reload 가 끝나면 다음에 던지는 찌부터 새 가중치로 낚입니다");
            }
        }
        int spawnWritten = 0;
        int spawnDeleted = 0;
        JsonObject rules = section(desired, SPAWN_RULES, new ArrayList<>());
        if (rules != null) {
            SpawnBalance.Outcome o = SpawnBalance.reconcileRules(
                    originals(server.getResourceManager(), SpawnBalance.FOLDER), pack, rules);
            msgs.addAll(o.warnings());
            spawnWritten += o.written();
            spawnDeleted += o.deleted();
        }
        JsonObject mobs = section(desired, SPAWN_MOBS, new ArrayList<>());
        if (mobs != null) {
            SpawnBalance.Outcome o = SpawnBalance.reconcileMobs(pack, mobs);
            spawnWritten += o.written();
            spawnDeleted += o.deleted();
        }
        // Orphan copies last, so a mob tweak still listed in spawnMobs is removed too when asked.
        JsonElement remove = desired.get(BalanceOrphans.REMOVE_KEY);
        if (remove != null && remove.isJsonArray() && remove.getAsJsonArray().size() > 0) {
            int n = BalanceOrphans.remove(pack, orphans(server), remove.getAsJsonArray());
            if (n > 0) msgs.add("원본 없는 사본 " + n + "개를 삭제했습니다");
        }
        if (rules != null || mobs != null) SpawnBalance.migrateLegacyRuleCopies(pack);
        if (spawnWritten + spawnDeleted > 0) {
            spawnRestartPending = true;
            msgs.add("등장 설정: 파일 " + spawnWritten + "개 저장, " + spawnDeleted
                    + "개 삭제 — 서버를 재시작해야 적용됩니다 (지금 세계의 등장은 그대로)");
        }
        // The pack is only created / switched on once it holds a copy: saving the editor with nothing changed here
        // must not add an empty data pack or trigger a /reload.
        boolean hasData = PackFiles.hasData(pack);
        if (hasData && PackFiles.ensurePack(pack)) msgs.add("데이터팩 gett_balance 를 새로 만들었습니다");
        boolean enable = hasData && !packEnabled(server);
        boolean reload = gunsChanged || efChanged || recipesChanged || fishingChanged || enable;
        if (reload) {
            reload(server);
            msgs.add(enable ? "데이터팩 gett_balance 를 켜고 /reload 를 실행합니다"
                    : "총·Epic Fight·제작법·낚시 반영을 위해 /reload 를 실행합니다 (몇 초 걸릴 수 있음)");
        }
        if (!msgs.isEmpty()) GettCore.LOGGER.info("balance save: {}", msgs);
        return new Applied(reload, msgs);
    }

    /** Same as /datapack enable + /reload: rescans packs, selects ours, reloads all data. */
    private static void reload(MinecraftServer server) {
        PackRepository repo = server.getPackRepository();
        repo.reload();
        List<String> ids = new ArrayList<>(repo.getSelectedIds());
        if (!ids.contains(PackFiles.PACK_ID)) ids.add(PackFiles.PACK_ID);
        server.reloadResources(ids).exceptionally(e -> {
            GettCore.LOGGER.error("balance: /reload after editor save failed", e);
            return null;
        });
    }

    /** Every biome's current natural spawn list (after all biome modifiers, ours included). */
    private static List<SpawnBalance.Spawn> liveSpawns(MinecraftServer server) {
        List<SpawnBalance.Spawn> out = new ArrayList<>();
        for (Biome biome : server.registryAccess().registryOrThrow(Registries.BIOME)) {
            MobSpawnSettings settings = biome.getMobSettings();
            for (MobCategory c : MobCategory.values()) {
                for (MobSpawnSettings.SpawnerData d : settings.getMobs(c).unwrap()) {
                    out.add(new SpawnBalance.Spawn(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                            .getKey(d.type).toString(), c.getName(), d.getWeight().asInt(), d.minCount, d.maxCount));
                }
            }
        }
        return out;
    }

    private static JsonObject section(JsonObject desired, String key, List<String> errs) {
        JsonElement e = desired.get(key);
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonObject()) {
            errs.add(STATE + "." + key + ": 객체여야 합니다");
            return null;
        }
        return e.getAsJsonObject();
    }
}
