package org.gettserver.gettcore.editor.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.balance.BalanceService;
import org.gettserver.gettcore.balance.EpicFightBalance;
import org.gettserver.gettcore.balance.GunBalance;
import org.gettserver.gettcore.balance.SpawnBalance;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.data.RegistryChecks;
import org.gettserver.gettcore.data.ShopDefinition;
import org.gettserver.gettcore.data.ShopEntry;
import org.gettserver.gettcore.editor.EditorBundle;
import org.gettserver.gettcore.editor.EditorJson;
import org.gettserver.gettcore.editor.EditorRefs;
import org.gettserver.gettcore.editor.EditorValidator;
import org.gettserver.gettcore.pet.boss.BossPoint;

/**
 * Manual UI check for the web editor: serves a fake backend (example data, tiny catalog, no icons).
 * Run with {@code ./gradlew editorDevServer}. Nothing is written to disk; saves only update memory.
 */
public final class EditorDevServer {
    private static EditorBundle current;
    private static EditorRefs refs;
    /** Balance data pack overrides (guns / spawns), kept in memory only. */
    private static JsonObject datapack = new JsonObject();

    private EditorDevServer() {}

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("gett-editor-dev");
        copyTree(Path.of("examples/config/gettcore"), tmp);
        GettData data = GettData.get();
        data.setRegistryChecks(RegistryChecks.ALL_EXIST);
        GettData.LoadReport report = data.load(tmp);
        if (!report.errors().isEmpty()) System.out.println("load errors: " + report.errors());
        current = withExtraEntries(data.rawBundle());
        refs = new EditorRefs(
                List.of(new EditorRefs.AdvRef("minecraft:story/mine_diamond", "다이아몬드!"),
                        new EditorRefs.AdvRef("minecraft:story/mine_stone", "석기 시대"),
                        new EditorRefs.AdvRef("minecraft:story/upgrade_tools", "더 좋은 도구로"),
                        new EditorRefs.AdvRef("minecraft:story/smelt_iron", "금속 얻기"),
                        new EditorRefs.AdvRef("minecraft:story/iron_tools", "철로 만든 곡괭이"),
                        new EditorRefs.AdvRef("minecraft:story/obtain_armor", "장비 장착하기"),
                        new EditorRefs.AdvRef("minecraft:story/enter_the_nether", "더 깊은 곳으로"),
                        new EditorRefs.AdvRef("minecraft:nether/find_fortress", "끔찍한 요새"),
                        new EditorRefs.AdvRef("minecraft:story/follow_ender_eye", "눈 속의 눈"),
                        new EditorRefs.AdvRef("minecraft:story/enter_the_end", "끝?"),
                        new EditorRefs.AdvRef("minecraft:end/kill_dragon", "엔드 해방")),
                List.of("oldmod:ruby"), List.of());

        String catalog = catalog();
        EditorBackend backend = new EditorBackend() {
            @Override public String snapshotJson() {
                synchronized (EditorDevServer.class) { return withBalance(EditorJson.encode(current, refs)); }
            }
            @Override public String catalogJson() { return catalog; }
            @Override public Optional<byte[]> iconPng(String namespace, String path) { return Optional.empty(); }
            @Override public CompletableFuture<String> save(String bundleJson) {
                return CompletableFuture.completedFuture(handleSave(bundleJson));
            }
        };
        EditorHttpServer server = EditorHttpServer.start(backend);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        System.out.println("Editor dev server: " + server.url());
        Thread.currentThread().join();
    }

    private static synchronized String handleSave(String bundleJson) {
        JsonArray errors = new JsonArray();
        JsonArray warnings = new JsonArray();
        String snapshot = null;
        try {
            EditorBundle decoded = EditorJson.decode(bundleJson);
            EditorValidator.Result r = EditorValidator.validate(decoded, RegistryChecks.ALL_EXIST);
            r.errors().forEach(errors::add);
            JsonObject balance = BalanceService.desiredOf(bundleJson);
            if (balance != null) BalanceService.validate(balance).forEach(errors::add);
            r.warnings().forEach(warnings::add);
            if (errors.isEmpty()) {
                current = r.cleaned();
                if (balance != null) {
                    datapack = balance;
                    warnings.add("(dev) 총·등장·Epic Fight 는 메모리에만 저장 — 실제 서버는 데이터팩 사본을 씀");
                }
                snapshot = withBalance(EditorJson.encode(current, refs));
            }
            System.out.println("save: " + bundleJson.length() + " bytes, errors=" + r.errors()
                    + " warnings=" + r.warnings());
        } catch (IllegalArgumentException | IllegalStateException e) {
            errors.add(String.valueOf(e.getMessage()));
            System.out.println("save rejected: " + e.getMessage());
        }
        JsonObject o = new JsonObject();
        o.addProperty("saved", errors.isEmpty());
        o.add("errors", errors);
        o.add("warnings", warnings);
        o.add("snapshot", snapshot == null ? JsonNull.INSTANCE : JsonParser.parseString(snapshot));
        return o.toString();
    }

    /**
     * Adds an uninstalled item (listed in refs.missingItems) and one unknown to the catalog to the farmer shop, and a
     * sample boss point when the example data has none.
     */
    private static EditorBundle withExtraEntries(EditorBundle b) {
        ShopDefinition farmer = b.shops().get("farmer");
        if (farmer == null) return b;
        String tab = farmer.tabs().isEmpty() ? "crops" : farmer.tabs().get(0).id();
        farmer = farmer.withEntry(new ShopEntry(ResourceLocation.parse("oldmod:ruby"), tab, 500, 100, true, true, 500, 100));
        farmer = farmer.withEntry(new ShopEntry(ResourceLocation.parse("servermod:gem"), tab, 900, 300, true, true, 900, 300));
        var shops = new LinkedHashMap<>(b.shops());
        shops.put("farmer", farmer);
        List<BossPoint> points = new ArrayList<>(b.bossPoints());
        if (points.isEmpty()) {
            points.add(new BossPoint("lion_den",
                    ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld")), new BlockPos(120, 68, -340), 16,
                    ResourceLocation.parse("gettcore:boss_lion"), 1, 30));
        }
        org.gettserver.gettcore.protect.ProtectConfig protect = b.protect();
        if (protect.regions().isEmpty()) {
            protect = protect.withRegions(List.of(new org.gettserver.gettcore.protect.ProtectConfig.Region("town",
                    ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld")),
                    new BlockPos(-40, 50, -40), new BlockPos(40, 140, 40),
                    new org.gettserver.gettcore.protect.ProtectConfig.Flags(false, false, false))));
        }
        return new EditorBundle(shops, b.dialogs(), b.npcs(), b.quests(), List.copyOf(points), b.pets(), protect,
                b.reinforce(), b.hunting(), b.mobs(), b.equipment());
    }

    /** Snapshot + the balance sections with a few real-looking mod files (trimmed). */
    private static String withBalance(String snapshotJson) {
        java.util.Map<ResourceLocation, JsonObject> guns = new LinkedHashMap<>();
        guns.put(ResourceLocation.parse("cgs:weapons/revolver.json"), JsonParser.parseString(
                "{\"general\":{\"rate\":10,\"maxAmmo\":6,\"equipTime\":0,\"reloadTime\":60,\"recoilAngle\":-7.0,\"spread\":1.0},"
                        + "\"ammoData\":{\"cgs:round_revolver\":{\"projectile\":{\"damage\":8.0}},"
                        + "\"cgs:round_revolver_piercing\":{\"projectile\":{\"damage\":12.0}}}}").getAsJsonObject());
        guns.put(ResourceLocation.parse("ntgl:weapons/guns/shotgun.json"), JsonParser.parseString(
                "{\"general\":{\"rate\":15,\"maxAmmo\":5,\"reloadTime\":10,\"recoilAngle\":-15.0,\"spread\":10.0},"
                        + "\"ammoData\":{\"ntgl:shotshell\":{\"projectile\":{\"damage\":1.0}}}}").getAsJsonObject());
        java.util.Map<ResourceLocation, JsonObject> rules = new LinkedHashMap<>();
        rules.put(ResourceLocation.parse("wom:neoforge/biome_modifier/spawn_hollow.json"), JsonParser.parseString(
                "{\"type\":\"neoforge:add_spawns\",\"biomes\":\"#minecraft:is_overworld\","
                        + "\"spawners\":{\"type\":\"wom:hollow\",\"weight\":60,\"minCount\":1,\"maxCount\":1}}").getAsJsonObject());
        JsonObject cat = new JsonObject();
        cat.addProperty("packEnabled", true);
        cat.addProperty("spawnRestartPending", datapack.size() > 0);
        cat.add(BalanceService.GUNS, GunBalance.catalog(guns));
        java.util.Map<ResourceLocation, JsonObject> ef = new LinkedHashMap<>();
        ef.put(ResourceLocation.parse("epicfight:capabilities/weapons/diamond_dagger.json"), JsonParser.parseString(
                "{\"attributes\":{\"one_hand\":{\"armor_negation\":10.0,\"max_strikes\":0,\"impact\":0.8},"
                        + "\"two_hand\":{\"armor_negation\":10.0,\"max_strikes\":1,\"impact\":0.8}},\"type\":\"epicfight:dagger\"}").getAsJsonObject());
        ef.put(ResourceLocation.parse("epicfight:capabilities/weapons/bokken.json"), JsonParser.parseString(
                "{\"type\":\"epicfight:bokken\"}").getAsJsonObject());
        ef.put(ResourceLocation.parse("magistuarmory:capabilities/weapons/bronze_pike.json"), JsonParser.parseString(
                "{\"type\":\"magistuarmory:pike\",\"attributes\":{\"common\":{\"impact\":2.6,\"max_strikes\":2,\"speed_bonus\":-0.2}}}").getAsJsonObject());
        ef.put(ResourceLocation.parse("minecraft:capabilities/weapons/diamond_sword.json"), JsonParser.parseString(
                "{\"attributes\":{\"common\":{\"impact\":1.1,\"max_strikes\":1}},\"type\":\"epicfight:sword\"}").getAsJsonObject());
        cat.add(BalanceService.EPICFIGHT, EpicFightBalance.catalog(ef));
        cat.add(BalanceService.SPAWN_RULES, SpawnBalance.ruleCatalog(rules));
        cat.add(BalanceService.SPAWN_MOBS, SpawnBalance.mobRows(List.of(
                new SpawnBalance.Spawn("minecraft:zombie", "monster", 95, 4, 4),
                new SpawnBalance.Spawn("minecraft:zombie", "monster", 19, 4, 4),
                new SpawnBalance.Spawn("minecraft:skeleton", "monster", 80, 4, 4),
                new SpawnBalance.Spawn("wom:hollow", "monster", 60, 1, 1),
                new SpawnBalance.Spawn("minecraft:cow", "creature", 8, 4, 4)), List.of()));
        return BalanceService.addSections(JsonParser.parseString(snapshotJson).getAsJsonObject(),
                datapack.deepCopy(), cat);
    }

    private static String catalog() {
        String[][] items = {
            {"apple", "사과"}, {"bread", "빵"}, {"carrot", "당근"}, {"potato", "감자"}, {"beetroot", "비트"},
            {"wheat", "밀"}, {"wheat_seeds", "밀 씨앗"}, {"diamond", "다이아몬드"}, {"emerald", "에메랄드"},
            {"gold_ingot", "금괴"}, {"iron_ingot", "철괴"}, {"coal", "석탄"}, {"oak_log", "참나무 원목"},
            {"birch_log", "자작나무 원목"}, {"oak_planks", "참나무 판자"}, {"stick", "막대기"}, {"cobblestone", "조약돌"},
            {"stone", "돌"}, {"dirt", "흙"}, {"sand", "모래"}, {"glass", "유리"}, {"iron_sword", "철 검"},
            {"diamond_sword", "다이아몬드 검"}, {"iron_pickaxe", "철 곡괭이"}, {"bow", "활"}, {"arrow", "화살"},
            {"cooked_beef", "익힌 소고기"}, {"beef", "소고기"}, {"egg", "달걀"}, {"milk_bucket", "우유 양동이"},
            {"bucket", "양동이"}, {"torch", "횃불"}, {"crafting_table", "작업대"}, {"chest", "상자"},
            {"redstone", "레드스톤 가루"}, {"lapis_lazuli", "청금석"}, {"book", "책"},
            {"enchanted_book", "마법이 부여된 책"}, {"experience_bottle", "경험치 병"}, {"golden_apple", "황금 사과"}
        };
        List<CatalogJson.Entry> list = new ArrayList<>();
        for (String[] i : items) list.add(new CatalogJson.Entry("minecraft:" + i[0], i[1], "Minecraft"));
        list.add(new CatalogJson.Entry("gettcore:enchant_stone", "마법부여석", "Gett Core"));
        List<CatalogJson.Entry> entities = new ArrayList<>(List.of(
                new CatalogJson.Entry("minecraft:zombie", "좀비", "Minecraft"),
                new CatalogJson.Entry("minecraft:skeleton", "스켈레톤", "Minecraft"),
                new CatalogJson.Entry("minecraft:creeper", "크리퍼", "Minecraft"),
                new CatalogJson.Entry("minecraft:ender_dragon", "엔더 드래곤", "Minecraft"),
                new CatalogJson.Entry("naturalist:lion", "사자", "Naturalist"),
                new CatalogJson.Entry("naturalist:vulture", "독수리", "Naturalist")));
        String[][] bosses = {{"lion", "보스 사자"}, {"tiger", "보스 호랑이"}, {"bear", "보스 곰"},
                {"elephant", "보스 코끼리"}, {"mammoth", "보스 매머드"}, {"vulture", "보스 독수리"}};
        List<String> bossTypes = new ArrayList<>();
        for (String[] b : bosses) {
            entities.add(new CatalogJson.Entry("gettcore:boss_" + b[0], b[1], "Gett Core"));
            bossTypes.add("gettcore:boss_" + b[0]);
        }
        return CatalogJson.build(list, entities,
                List.of("minecraft:logs", "minecraft:planks", "minecraft:coals", "c:ingots/iron"),
                List.of("minecraft:skeletons"), bossTypes,
                List.of(new CatalogJson.Entry("minecraft:speed", "신속", "Minecraft"),
                        new CatalogJson.Entry("minecraft:strength", "힘", "Minecraft"),
                        new CatalogJson.Entry("minecraft:resistance", "저항", "Minecraft"),
                        new CatalogJson.Entry("minecraft:regeneration", "재생", "Minecraft")),
                List.of(new CatalogJson.MobStat("minecraft:zombie", "좀비", "Minecraft", "monster", 20.0, 3.0, 0.23, 2.0),
                        new CatalogJson.MobStat("minecraft:skeleton", "스켈레톤", "Minecraft", "monster", 20.0, 2.0, 0.25, 0.0),
                        new CatalogJson.MobStat("minecraft:creeper", "크리퍼", "Minecraft", "monster", 20.0, null, 0.25, 0.0),
                        new CatalogJson.MobStat("minecraft:cow", "소", "Minecraft", "creature", 10.0, null, 0.2, 0.0),
                        new CatalogJson.MobStat("naturalist:lion", "사자", "Naturalist", "creature", 30.0, 6.0, 0.3, 0.0),
                        new CatalogJson.MobStat("gettcore:boss_lion", "보스 사자", "Gett Core", "creature", 30.0, 6.0, 0.3, 0.0)),
                List.of(new CatalogJson.Gear("minecraft:diamond_sword", "다이아몬드 검", "Minecraft", "melee", "mainhand", 7.0, 1.6, null, null, null),
                        new CatalogJson.Gear("minecraft:iron_axe", "철 도끼", "Minecraft", "melee", "mainhand", 9.0, 0.9, null, null, null),
                        new CatalogJson.Gear("minecraft:netherite_chestplate", "네더라이트 흉갑", "Minecraft", "armor", "chest", null, null, 8.0, 3.0, 0.1),
                        new CatalogJson.Gear("minecraft:iron_helmet", "철 투구", "Minecraft", "armor", "head", null, null, 2.0, null, null),
                        new CatalogJson.Gear("minecraft:shield", "방패", "Minecraft", "shield", "offhand", null, null, null, null, null),
                        new CatalogJson.Gear("magistuarmory:steel_longsword", "강철 롱소드", "Epic Knights", "melee", "mainhand", 8.0, 1.2, null, null, null)));
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
