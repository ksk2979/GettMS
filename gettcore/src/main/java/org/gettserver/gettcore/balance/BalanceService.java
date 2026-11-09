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
 * save is applied (copies written/deleted, pack enabled, /reload when gun data changed). Server thread only.
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

    /** Spawn copies changed since this server started: they wait for a restart (biome modifiers load once). */
    private static volatile boolean spawnRestartPending;

    public record Applied(boolean reloaded, List<String> messages) {}

    private BalanceService() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        spawnRestartPending = false;
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

    /** Adds the two sections to an editor snapshot. On failure the snapshot goes out without them. */
    public static String withSections(String snapshotJson, MinecraftServer server) {
        try {
            JsonObject root = JsonParser.parseString(snapshotJson).getAsJsonObject();
            Map<ResourceLocation, JsonObject> guns = originals(server.getResourceManager(), GunBalance.FOLDER);
            Map<ResourceLocation, JsonObject> mods = originals(server.getResourceManager(), SpawnBalance.FOLDER);
            Path pack = packDir(server);
            JsonObject state = new JsonObject();
            state.add(GUNS, GunBalance.state(guns, pack));
            state.add(SPAWN_RULES, SpawnBalance.ruleState(mods, pack));
            JsonObject mobState = SpawnBalance.mobState(pack);
            state.add(SPAWN_MOBS, mobState);
            JsonObject cat = new JsonObject();
            cat.addProperty("packEnabled", packEnabled(server));
            cat.addProperty("spawnRestartPending", spawnRestartPending);
            cat.add(GUNS, GunBalance.catalog(guns));
            cat.add(SPAWN_RULES, SpawnBalance.ruleCatalog(mods));
            cat.add(SPAWN_MOBS, SpawnBalance.mobRows(liveSpawns(server), new ArrayList<>(mobState.keySet())));
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
        List<String> errs = new ArrayList<>();
        JsonObject guns = section(desired, GUNS, errs);
        if (guns != null) errs.addAll(GunBalance.validate(guns));
        JsonObject rules = section(desired, SPAWN_RULES, errs);
        if (rules != null) errs.addAll(SpawnBalance.validateRules(rules));
        JsonObject mobs = section(desired, SPAWN_MOBS, errs);
        if (mobs != null) errs.addAll(SpawnBalance.validateMobs(mobs));
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
            if (gunsChanged) msgs.add("총 수치: 사본 " + o.written() + "개 저장, " + o.deleted() + "개 삭제(원래대로)");
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
        boolean reload = gunsChanged || enable;
        if (reload) {
            reload(server);
            msgs.add(enable ? "데이터팩 gett_balance 를 켜고 /reload 를 실행합니다"
                    : "총 수치 반영을 위해 /reload 를 실행합니다 (몇 초 걸릴 수 있음)");
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
