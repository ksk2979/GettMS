package org.gettserver.gettcore.balance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 원본이 사라진 사본(모드 삭제·업데이트): 편집기에 "원본 없음"으로 보이고 지울 수 있으며, 등장 사본은 세계 로딩을 막지 않는다. */
class BalanceOrphansTest {
    @TempDir Path pack;

    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    static final ResourceLocation GUN = ResourceLocation.parse("cgs:weapons/revolver.json");
    static final ResourceLocation GONE_GUN = ResourceLocation.parse("oldguns:weapons/musket.json");
    static final ResourceLocation EF = ResourceLocation.parse("epicfight:capabilities/weapons/bokken.json");
    static final ResourceLocation GONE_EF = ResourceLocation.parse("wom:capabilities/weapons/solar.json");
    static final ResourceLocation RULE = ResourceLocation.parse("x:neoforge/biome_modifier/pair.json");
    static final ResourceLocation GONE_RULE = ResourceLocation.parse("wom:neoforge/biome_modifier/spawn_hollow.json");

    private void fill() throws Exception {
        for (ResourceLocation f : List.of(GUN, GONE_GUN, EF, GONE_EF, RULE, GONE_RULE)) {
            PackFiles.write(pack, f, json("{\"type\":\"neoforge:none\"}"));
        }
        SpawnBalance.reconcileMobs(pack, json("{\"minecraft:zombie\":0,\"wom:hollow\":2}"));
    }

    private List<BalanceOrphans.Orphan> find() {
        Map<String, Set<ResourceLocation>> originals = Map.of(
                BalanceService.GUNS, Set.of(GUN),
                BalanceService.EPICFIGHT, Set.of(EF),
                BalanceService.SPAWN_RULES, Set.of(RULE));
        return BalanceOrphans.find(pack, originals, id -> id.getNamespace().equals("minecraft"));
    }

    @Test
    void copiesWithoutAnOriginalAreListedPerTab() throws Exception {
        fill();
        JsonArray rows = BalanceOrphans.toJson(find());
        assertEquals(JsonParser.parseString("""
                [{"kind":"guns","id":"oldguns:weapons/musket"},
                 {"kind":"epicfight","id":"wom:solar"},
                 {"kind":"spawnRules","id":"wom:spawn_hollow"},
                 {"kind":"spawnMobs","id":"wom:hollow"}]"""), rows);
    }

    @Test
    void removeDeletesOnlyRequestedCurrentOrphans() throws Exception {
        fill();
        JsonArray req = JsonParser.parseString("""
                [{"kind":"guns","id":"oldguns:weapons/musket"},{"kind":"guns","id":"cgs:weapons/revolver"},
                 {"kind":"spawnRules","id":"wom:spawn_hollow"},{"kind":"spawnMobs","id":"wom:hollow"}]""").getAsJsonArray();
        assertEquals(3, BalanceOrphans.remove(pack, find(), req), "revolver still has its original: kept");
        assertTrue(PackFiles.read(pack, GONE_GUN).isEmpty());
        assertTrue(PackFiles.read(pack, GUN).isPresent());
        assertTrue(PackFiles.read(pack, GONE_RULE).isEmpty());
        assertTrue(PackFiles.read(pack, GONE_EF).isPresent(), "not requested");
        assertEquals(json("{\"minecraft:zombie\":0.0}"), SpawnBalance.mobState(pack));
    }

    @Test
    void validateRemoveRequests() {
        assertEquals(List.of(), BalanceOrphans.validate(JsonParser.parseString("[{\"kind\":\"guns\",\"id\":\"a:b\"}]")));
        assertEquals(1, BalanceOrphans.validate(JsonParser.parseString("{}")).size());
        assertEquals(2, BalanceOrphans.validate(JsonParser.parseString("[{\"kind\":\"x\",\"id\":\"a:b\"},3]")).size());
    }

    @Test
    void editedRuleCopiesUseTheTolerantTypeAndDecodeWithAnUnknownEntity() throws Exception {
        Map<ResourceLocation, JsonObject> originals = SpawnBalanceTest.originals();
        SpawnBalance.reconcileRules(originals, pack, json("{\"wom:spawn_hollow\":{\"spawners\":[{\"weight\":20}]}}"));
        JsonObject copy = PackFiles.read(pack, SpawnBalanceTest.HOLLOW_LOC).orElseThrow();
        assertEquals(SpawnBalance.SAFE_ADD_SPAWNS, copy.get("type").getAsString());
        assertEquals(json("{\"wom:spawn_hollow\":{\"spawners\":[{\"weight\":20,\"minCount\":1,\"maxCount\":1}]}}"),
                SpawnBalance.ruleState(originals, pack), "state reads it back as before");
        // wom:hollow does not exist here: the vanilla codec would fail, ours decodes (resolved later, skipped).
        SafeAddSpawnsModifier m = SafeAddSpawnsModifier.CODEC.codec().parse(JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString(copy.toString())).getOrThrow();
        assertEquals(1, m.spawners().size());
        assertEquals(20, m.spawners().get(0).weight());
    }

    @Test
    void resolveSpawnersSkipsUnknownEntitiesAndBadNumbers() {
        List<String> warnings = new ArrayList<>();
        List<SpawnerData> out = SafeAddSpawnsModifier.resolveSpawners(List.of(
                new SafeAddSpawnsModifier.Spawner(ResourceLocation.parse("minecraft:zombie"), 10, 1, 3),
                new SafeAddSpawnsModifier.Spawner(ResourceLocation.parse("gone:mob"), 10, 1, 3),
                new SafeAddSpawnsModifier.Spawner(ResourceLocation.parse("minecraft:husk"), 10, 4, 2)),
                id -> BuiltInRegistries.ENTITY_TYPE.containsKey(id)
                        ? Optional.<EntityType<?>>of(BuiltInRegistries.ENTITY_TYPE.get(id)) : Optional.empty(),
                warnings::add);
        assertEquals(1, out.size());
        assertSame(EntityType.ZOMBIE, out.get(0).type);
        assertEquals(2, warnings.size(), warnings.toString());
    }

    @Test
    void legacyAddSpawnsCopiesAreMigratedToTheTolerantType() throws Exception {
        PackFiles.write(pack, GONE_RULE, json(SpawnBalanceTest.HOLLOW));
        PackFiles.write(pack, RULE, json("{\"type\":\"neoforge:none\",\"biomes\":[]}"));
        SpawnBalance.reconcileMobs(pack, json("{\"minecraft:zombie\":0}"));
        assertEquals(1, SpawnBalance.migrateLegacyRuleCopies(pack));
        JsonObject migrated = PackFiles.read(pack, GONE_RULE).orElseThrow();
        JsonObject expected = json(SpawnBalanceTest.HOLLOW);
        expected.addProperty("type", SpawnBalance.SAFE_ADD_SPAWNS);
        assertEquals(expected, migrated);
        assertEquals("neoforge:none", PackFiles.read(pack, RULE).orElseThrow().get("type").getAsString());
        assertEquals(0, SpawnBalance.migrateLegacyRuleCopies(pack), "idempotent");
    }
}
