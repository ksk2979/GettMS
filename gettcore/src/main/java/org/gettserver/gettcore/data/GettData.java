package org.gettserver.gettcore.data;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.editor.EditorBundle;
import org.gettserver.gettcore.editor.EditorMerge;
import org.gettserver.gettcore.editor.EditorValidator;
import org.gettserver.gettcore.enchant.EnchantConfig;
import org.gettserver.gettcore.equipment.EquipmentConfig;
import org.gettserver.gettcore.hunting.HuntingConfig;
import org.gettserver.gettcore.fieldgold.FieldGoldConfig;
import org.gettserver.gettcore.mobs.MobStatsConfig;
import org.gettserver.gettcore.skill.BossConfig;
import org.gettserver.gettcore.pet.PetConfig;
import org.gettserver.gettcore.pet.boss.BossPoint;
import org.gettserver.gettcore.protect.ProtectConfig;
import org.gettserver.gettcore.reinforce.ReinforceConfig;
import org.gettserver.gettcore.upgrade.UpgradeConfig;
import org.gettserver.gettcore.detector.DetectorConfig;
import org.gettserver.gettcore.quest.QuestLine;
import org.gettserver.gettcore.quest.QuestPrune;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads shops, dialogs, NPC mapping, economy and enchant config from JSON files under the config directory.
 * Free of Minecraft server classes so it can be unit tested.
 */
public final class GettData {
    private static final Logger LOGGER = LoggerFactory.getLogger("gettcore");
    private static final GettData INSTANCE = new GettData();
    private static final String[] DEFAULT_FILES = {
            "economy.json", "npcs.json", "enchant.json", "pets.json", "boss_points.json", "protect.json", "reinforce.json", "hunting.json", "mobs.json", "bosses.json", "equipment.json", "upgrade.json", "detector.json", "field_gold.json", "shops/farmer.json", "dialogs/farmer.json",
            "dialogs/enchanter.json", "dialogs/chief.json", "dialogs/blacksmith.json", "quests/main.json"};
    /** Defaults shipped before the install marker existed (copied only into an empty dir). */
    private static final Set<String> LEGACY_FILES = Set.of(
            "economy.json", "npcs.json", "shops/farmer.json", "dialogs/farmer.json");
    static final String BOSS_POINTS_FILE = "boss_points.json";
    static final String PETS_FILE = "pets.json";
    /** Lists the bundled defaults already installed into the config dir. */
    static final String MARKER = ".defaults-installed";
    /** One-shot upgrade (1.2.0): adds only the 이장 -> chief name mapping to an npcs.json installed earlier. */
    static final String NPC_CHIEF_UPGRADE = "npcs.json@chief";
    static final String PETS_BOSS_GIRAFFE_UPGRADE = "pets.json@boss_giraffe";
    private static final String BOSS_GIRAFFE_KEY = "gettcore:boss_giraffe";
    /** One-shot upgrade (1.11.0): fills an empty skill list of the boss bear in an installed bosses.json. */
    static final String BOSSES_BEAR_UPGRADE = "bosses.json@bear_skills";
    private static final String BOSS_BEAR_KEY = "gettcore:boss_bear";
    /**
     * One-shot upgrade (1.14.0): armor ability weights still at the old 40/30/20/10 default become equal (10 each).
     */
    private static final String ENCHANT_FILE = "enchant.json";
    static final String ENCHANT_ARMOR_EQUAL = "enchant.json@armor_equal";
    /** One-shot upgrade (reinforce): adds only the 대장장이 -> blacksmith name mapping to an earlier npcs.json. */
    static final String NPC_BLACKSMITH_UPGRADE = "npcs.json@blacksmith";

    /** Callbacks run by /gett reload after data was re-read (e.g. Task 8: close open ShopMenu screens). */
    private static final Set<Runnable> RELOAD_HOOKS = new CopyOnWriteArraySet<>();

    private volatile Map<String, ShopDefinition> shops = Map.of();
    /** Shops exactly as in the files (including items missing from the registry). */
    private volatile Map<String, ShopDefinition> rawShops = Map.of();
    private volatile RegistryChecks registryChecks = RegistryChecks.live();
    private volatile Map<String, QuestLine> questLines = Map.of();
    /** Quest lines exactly as in the files (including missing items/entities). */
    private volatile Map<String, QuestLine> rawQuestLines = Map.of();
    private volatile Map<String, DialogDefinition> dialogs = Map.of();
    private volatile Map<String, Path> shopFiles = Map.of();
    private volatile Map<String, Path> dialogFiles = Map.of();
    private volatile Map<String, Path> questFiles = Map.of();
    private volatile NpcMapping npcs = NpcMapping.EMPTY;
    private volatile EconomyConfig economy = EconomyConfig.DEFAULT;
    private volatile EnchantConfig enchant = EnchantConfig.DEFAULT;
    private volatile PetConfig pets = PetConfig.DEFAULT;
    private volatile List<BossPoint> bossPoints = List.of();
    private volatile ProtectConfig protect = ProtectConfig.DEFAULT;
    private volatile ReinforceConfig reinforce = ReinforceConfig.DEFAULT;
    private volatile HuntingConfig hunting = HuntingConfig.DEFAULT;
    private volatile MobStatsConfig mobs = MobStatsConfig.DEFAULT;
    private volatile BossConfig bosses = BossConfig.DEFAULT;
    private volatile FieldGoldConfig fieldGold = FieldGoldConfig.DEFAULT;
    private volatile EquipmentConfig equipment = EquipmentConfig.DEFAULT;
    private volatile UpgradeConfig upgrade = UpgradeConfig.DEFAULT;
    private volatile DetectorConfig detector = DetectorConfig.DEFAULT;
    private volatile Path configDir;

    @VisibleForTesting
    GettData() {}

    public static GettData get() {
        return INSTANCE;
    }

    public static void addReloadHook(Runnable hook) {
        RELOAD_HOOKS.add(hook);
    }

    public static void runReloadHooks() {
        for (Runnable r : RELOAD_HOOKS) r.run();
    }

    public record LoadReport(int shops, int dialogs, int quests, List<String> errors, List<String> warnings) {}

    public void setRegistryChecks(RegistryChecks checks) {
        this.registryChecks = checks;
    }

    public LoadReport load(Path dir) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        configDir = dir;
        try {
            Files.createDirectories(dir);
            copyDefaults(dir, errors);
        } catch (IOException e) {
            errors.add("config dir " + dir + ": " + e.getMessage());
        }

        Map<String, ShopDefinition> newShops = new HashMap<>();
        Map<String, Path> newShopFiles = new HashMap<>();
        for (Path p : jsonFiles(dir.resolve("shops"), errors)) {
            read(p, ShopDefinition.CODEC, errors).ifPresent(s -> {
                if (newShops.putIfAbsent(s.id(), s) != null) {
                    errors.add(p.getFileName() + ": duplicate shop id " + s.id());
                } else {
                    newShopFiles.put(s.id(), p);
                }
            });
        }
        Map<String, DialogDefinition> newDialogs = new HashMap<>();
        Map<String, Path> newDialogFiles = new HashMap<>();
        Map<String, Path> newQuestFiles = new HashMap<>();
        for (Path p : jsonFiles(dir.resolve("dialogs"), errors)) {
            read(p, DialogDefinition.CODEC, errors).ifPresent(d -> {
                if (newDialogs.putIfAbsent(d.id(), d) != null) {
                    errors.add(p.getFileName() + ": duplicate dialog id " + d.id());
                } else {
                    newDialogFiles.put(d.id(), p);
                }
            });
        }
        Map<String, QuestLine> newQuests = new java.util.TreeMap<>();
        for (Path p : jsonFiles(dir.resolve("quests"), errors)) {
            read(p, QuestLine.CODEC, errors).ifPresent(q -> {
                if (newQuests.putIfAbsent(q.id(), q) != null) {
                    errors.add(p.getFileName() + ": duplicate quest line id " + q.id());
                } else {
                    newQuestFiles.put(q.id(), p);
                }
            });
        }
        Path npcFile = dir.resolve("npcs.json");
        NpcMapping newNpcs = Files.isRegularFile(npcFile)
                ? read(npcFile, NpcMapping.CODEC, errors).orElse(NpcMapping.EMPTY) : NpcMapping.EMPTY;
        Path ecoFile = dir.resolve("economy.json");
        EconomyConfig newEco = Files.isRegularFile(ecoFile)
                ? read(ecoFile, EconomyConfig.CODEC, errors).orElse(EconomyConfig.DEFAULT) : EconomyConfig.DEFAULT;
        Path enchantFile = dir.resolve("enchant.json");
        EnchantConfig newEnchant = (Files.isRegularFile(enchantFile)
                ? read(enchantFile, EnchantConfig.CODEC, errors).orElse(EnchantConfig.DEFAULT) : EnchantConfig.DEFAULT)
                .sanitized(errors);
        // An unreadable pets.json keeps the config already loaded (DEFAULT only on the first load), like
        // boss_points.json below: a typo must not wipe the species whitelist and the capture odds.
        Path petsFile = dir.resolve(PETS_FILE);
        PetConfig newPets = Files.isRegularFile(petsFile)
                ? read(petsFile, PetConfig.CODEC, errors).map(c -> c.sanitized(errors)).orElse(pets)
                : PetConfig.DEFAULT.sanitized(errors);
        // An unreadable boss_points.json keeps the points already loaded (a typo must not strand the bosses).
        Path bossFile = dir.resolve(BOSS_POINTS_FILE);
        List<BossPoint> newBossPoints = Files.isRegularFile(bossFile)
                ? read(bossFile, BossPoint.CODEC.listOf(), errors).map(l -> uniqueBossPoints(l, errors, warnings))
                        .orElse(bossPoints)
                : List.of();
        // An unreadable protect.json keeps the regions already loaded (a typo must not unprotect the town).
        Path protectFile = dir.resolve(ProtectConfig.FILE);
        ProtectConfig newProtect = Files.isRegularFile(protectFile)
                ? read(protectFile, ProtectConfig.CODEC, errors).map(c -> c.sanitized(warnings)).orElse(protect)
                : ProtectConfig.DEFAULT;
        // An unreadable reinforce.json keeps the config already loaded (DEFAULT only on the first load).
        Path reinforceFile = dir.resolve(ReinforceConfig.FILE);
        ReinforceConfig newReinforce = Files.isRegularFile(reinforceFile)
                ? read(reinforceFile, ReinforceConfig.CODEC, errors).map(c -> c.sanitized(warnings)).orElse(reinforce)
                : ReinforceConfig.DEFAULT;
        // An unreadable hunting.json / mobs.json keeps the config already loaded (a typo must not open the zones).
        Path huntingFile = dir.resolve(HuntingConfig.FILE);
        HuntingConfig newHunting = Files.isRegularFile(huntingFile)
                ? read(huntingFile, HuntingConfig.CODEC, errors).map(c -> c.sanitized(warnings)).orElse(hunting)
                : HuntingConfig.DEFAULT;
        Path mobsFile = dir.resolve(MobStatsConfig.FILE);
        MobStatsConfig newMobs = Files.isRegularFile(mobsFile)
                ? read(mobsFile, MobStatsConfig.CODEC, errors).map(c -> c.sanitized(warnings)).orElse(mobs)
                : MobStatsConfig.DEFAULT;
        // An unreadable bosses.json keeps the config already loaded.
        Path bossesFile = dir.resolve(BossConfig.FILE);
        BossConfig newBosses = Files.isRegularFile(bossesFile)
                ? read(bossesFile, BossConfig.CODEC, errors).map(c -> c.sanitized(warnings)).orElse(bosses)
                : BossConfig.DEFAULT;
        // An unreadable field_gold.json keeps the config already loaded.
        Path fieldGoldFile = dir.resolve(FieldGoldConfig.FILE);
        FieldGoldConfig newFieldGold = Files.isRegularFile(fieldGoldFile)
                ? read(fieldGoldFile, FieldGoldConfig.CODEC, errors).map(c -> c.sanitized(warnings)).orElse(fieldGold)
                : FieldGoldConfig.DEFAULT;
        Path equipmentFile = dir.resolve(EquipmentConfig.FILE);
        EquipmentConfig newEquipment = Files.isRegularFile(equipmentFile)
                ? read(equipmentFile, EquipmentConfig.CODEC, errors).map(c -> c.sanitized(warnings)).orElse(equipment)
                : EquipmentConfig.DEFAULT;
        Path upgradeFile = dir.resolve(UpgradeConfig.FILE);
        UpgradeConfig newUpgrade = Files.isRegularFile(upgradeFile)
                ? read(upgradeFile, UpgradeConfig.CODEC, errors).map(c -> c.sanitized(warnings)).orElse(upgrade)
                : UpgradeConfig.DEFAULT;
        // An unreadable detector.json keeps the config already loaded.
        Path detectorFile = dir.resolve(DetectorConfig.FILE);
        DetectorConfig newDetector = Files.isRegularFile(detectorFile)
                ? read(detectorFile, DetectorConfig.CODEC, errors).map(c -> c.sanitized(warnings)).orElse(detector)
                : DetectorConfig.DEFAULT;

        validate(newShops, newDialogs, newNpcs);
        warnUnmappedQuestNpcs(newQuests, newNpcs, warnings);

        shopFiles = Map.copyOf(newShopFiles);
        dialogFiles = Map.copyOf(newDialogFiles);
        questFiles = Map.copyOf(newQuestFiles);
        dialogs = Map.copyOf(newDialogs);
        npcs = newNpcs;
        economy = newEco;
        enchant = newEnchant;
        pets = newPets;
        bossPoints = newBossPoints;
        protect = newProtect;
        reinforce = newReinforce;
        hunting = newHunting;
        mobs = newMobs;
        bosses = newBosses;
        fieldGold = newFieldGold;
        equipment = newEquipment;
        upgrade = newUpgrade;
        detector = newDetector;
        // Normalization applies to the raw (file) shops so entries for missing items are never dropped from disk.
        if (!newEco.priceDynamics().enabled()) {
            for (Map.Entry<String, ShopDefinition> en : List.copyOf(newShops.entrySet())) {
                ShopDefinition sd = en.getValue();
                if (newEco.fluctuates(sd)) continue;   // timed market prices live in the current prices
                ShopDefinition norm = normalized(sd);
                if (norm.equals(sd)) continue;
                newShops.put(en.getKey(), norm);
                try {
                    writeShopFile(norm);
                } catch (IOException e) {
                    errors.add("shop " + sd.id() + ": " + e.getMessage());
                }
            }
        }
        Map<String, ShopDefinition> pruned = new HashMap<>();
        for (ShopDefinition sd : newShops.values()) pruned.put(sd.id(), prune(sd, warnings));
        Map<String, QuestLine> prunedQuests = new java.util.TreeMap<>();
        for (QuestLine ql : newQuests.values()) {
            prunedQuests.put(ql.id(), QuestPrune.prune(QuestPrune.sanitize(ql, warnings), registryChecks, warnings));
        }
        rawQuestLines = Map.copyOf(newQuests);
        questLines = Map.copyOf(prunedQuests);
        rawShops = Map.copyOf(newShops);
        shops = Map.copyOf(pruned);
        warnings.forEach(w -> LOGGER.warn("gettcore: {}", w));
        return new LoadReport(newShops.size(), newDialogs.size(), newQuests.size(), List.copyOf(errors), List.copyOf(warnings));
    }

    /** Drops duplicate ids (errors) and clamps out-of-range values (warnings). */
    private static List<BossPoint> uniqueBossPoints(List<BossPoint> points, List<String> errors,
                                                    List<String> warnings) {
        Map<String, BossPoint> byId = new LinkedHashMap<>();
        for (BossPoint p : points) {
            if (byId.containsKey(p.id())) {
                errors.add(BOSS_POINTS_FILE + ": duplicate boss point id " + p.id() + " (skipped)");
            } else {
                byId.put(p.id(), p.sanitized(warnings));
            }
        }
        return List.copyOf(byId.values());
    }

    private static void validate(Map<String, ShopDefinition> shops, Map<String, DialogDefinition> dialogs, NpcMapping npcs) {
        for (ShopDefinition sh : shops.values()) {
            Set<String> tabIds = new java.util.HashSet<>();
            sh.tabs().forEach(t -> tabIds.add(t.id()));
            for (ShopEntry e : sh.entries()) {
                if (!tabIds.contains(e.tab())) {
                    LOGGER.warn("gettcore: shop {} entry {} uses unknown tab '{}'", sh.id(), e.item(), e.tab());
                }
            }
        }
        for (DialogDefinition d : dialogs.values()) {
            if (!d.nodes().containsKey(d.start())) {
                LOGGER.warn("gettcore: dialog {} start node '{}' is missing", d.id(), d.start());
            }
            d.nodes().forEach((nodeId, node) -> node.choices().forEach(c -> {
                if (c.action().type() == ChoiceAction.Type.NEXT
                        && !d.nodes().containsKey(c.action().target().orElse(""))) {
                    LOGGER.warn("gettcore: dialog {} node {} points to missing node '{}'",
                            d.id(), nodeId, c.action().target().orElse("(none)"));
                }
            }));
        }
        Stream.concat(npcs.byUuid().entrySet().stream(), npcs.byName().entrySet().stream()).forEach(en -> {
            if (!dialogs.containsKey(en.getValue())) {
                LOGGER.warn("gettcore: npc mapping '{}' references missing dialog '{}'", en.getKey(), en.getValue());
            }
        });
    }

    /** Quest start/complete/talk NPC names that no NPC is mapped to by name can never be triggered. */
    private static void warnUnmappedQuestNpcs(Map<String, QuestLine> quests, NpcMapping npcs, List<String> warnings) {
        for (QuestLine ql : quests.values()) {
            for (org.gettserver.gettcore.quest.Quest q : ql.quests()) {
                Set<String> names = new java.util.LinkedHashSet<>();
                q.start().npc().ifPresent(names::add);
                q.complete().npc().ifPresent(names::add);
                for (org.gettserver.gettcore.quest.Objective o : q.objectives()) {
                    if (o.type() == org.gettserver.gettcore.quest.Objective.Type.TALK) names.add(o.target());
                }
                for (String n : names) {
                    if (!n.isBlank() && !npcs.byName().containsKey(n)) {
                        warnings.add("quest " + ql.id() + "/" + q.id() + ": npc '" + n
                                + "' is not mapped in npcs.json byName");
                    }
                }
            }
        }
    }

    private ShopDefinition prune(ShopDefinition sd, List<String> warnings) {
        RegistryChecks checks = registryChecks;
        List<ShopEntry> kept = new ArrayList<>();
        for (ShopEntry e : sd.entries()) {
            if (checks.itemExists().test(e.item())) {
                kept.add(e);
            } else {
                warnings.add("shop " + sd.id() + ": missing item " + e.item() + " (skipped)");
            }
        }
        if (kept.size() == sd.entries().size()) return sd;
        return sd.withEntries(List.copyOf(kept));
    }

    /** The shop exactly as stored in its file (entries for items missing from the registry included). */
    public Optional<ShopDefinition> rawShop(String id) {
        return Optional.ofNullable(rawShops.get(id));
    }

    public Optional<ShopDefinition> shop(String id) {
        return Optional.ofNullable(shops.get(id));
    }

    public java.util.Collection<ShopDefinition> allShops() {
        return shops.values();
    }

    /** Quest line with entries for missing items/entities removed. */
    public Optional<QuestLine> questLine(String id) {
        return Optional.ofNullable(questLines.get(id));
    }

    /** The quest line exactly as stored in its file. */
    public Optional<QuestLine> rawQuestLine(String id) {
        return Optional.ofNullable(rawQuestLines.get(id));
    }

    public java.util.Collection<QuestLine> allQuestLines() {
        return questLines.values();
    }

    public Optional<DialogDefinition> dialog(String id) {
        return Optional.ofNullable(dialogs.get(id));
    }

    /** UUID mapping wins over name mapping. Returns a dialog id. */
    public Optional<String> dialogForNpc(UUID uuid, String name) {
        NpcMapping m = npcs;
        String byUuid = uuid == null ? null : m.byUuid().get(uuid.toString());
        if (byUuid != null) return Optional.of(byUuid);
        return Optional.ofNullable(name == null ? null : m.byName().get(name));
    }

    public NpcMapping npcMapping() {
        return npcs;
    }

    /** Everything the editor edits, exactly as in the files (no registry pruning). */
    public EditorBundle rawBundle() {
        return new EditorBundle(new java.util.TreeMap<>(rawShops), new java.util.TreeMap<>(dialogs), npcs,
                new java.util.TreeMap<>(rawQuestLines), bossPoints, pets, protect, reinforce, hunting, mobs,
                equipment, upgrade, bosses, detector, enchant);
    }

    /** {@code removedBossPoints}: ids of boss points that existed before a successful save and no longer do. */
    public record SaveResult(boolean saved, List<String> errors, List<String> warnings, LoadReport reload,
                             List<String> removedBossPoints) {
        public SaveResult(boolean saved, List<String> errors, List<String> warnings, LoadReport reload) {
            this(saved, errors, warnings, reload, List.of());
        }
    }

    private static final int BACKUP_KEEP = 20;
    private static final java.time.format.DateTimeFormatter BACKUP_STAMP =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Validates the whole bundle; if clean, backs up the current files, writes every shop/dialog/quest file,
     * npcs.json, boss_points.json, pets.json, protect.json, reinforce.json, hunting.json, mobs.json, equipment.json, upgrade.json, bosses.json, detector.json and enchant.json (all to .tmp first, then moved), deletes
     * files of ids no longer present and reloads. Boss points, pets, protect and reinforce are replaced as a whole; the ids of boss points that are gone
     * afterwards are reported in {@link SaveResult#removedBossPoints} (the caller drops their runtime state).
     * Nothing is touched when validation fails.
     */
    public synchronized SaveResult saveBundle(EditorBundle bundle, RegistryChecks checks) {
        Path dir = configDir;
        if (dir == null) return new SaveResult(false, List.of("data not loaded"), List.of(), null);
        EditorValidator.Result v = EditorValidator.validate(bundle, checks);
        if (!v.errors().isEmpty()) return new SaveResult(false, v.errors(), v.warnings(), null);
        EditorBundle b = v.cleaned();

        // Target file per id: the tracked file if known, else <dir>/<kind>/<id>.json.
        Set<String> used = new java.util.HashSet<>();
        for (Map<String, Path> files : List.of(shopFiles, dialogFiles, questFiles)) {
            for (Path f : files.values()) used.add(pathKey(f));
        }
        Map<String, Path> shopTargets = planTargets(shopFiles, b.shops().keySet(), dir.resolve("shops"), used);
        Map<String, Path> dialogTargets = planTargets(dialogFiles, b.dialogs().keySet(), dir.resolve("dialogs"), used);
        Map<String, Path> questTargets = planTargets(questFiles, b.quests().keySet(), dir.resolve("quests"), used);
        Map<Path, JsonElement> writes = new LinkedHashMap<>();
        Set<Path> keep = new java.util.HashSet<>();
        try {
            for (ShopDefinition sd : b.shops().values()) {
                ShopDefinition merged = EditorMerge.keepCurrentPrices(sd, rawShops.get(sd.id()));
                Path f = shopTargets.get(sd.id());
                writes.put(f, ShopDefinition.CODEC.encodeStart(JsonOps.INSTANCE, merged).getOrThrow(IOException::new));
                keep.add(f);
            }
            for (DialogDefinition d : b.dialogs().values()) {
                Path f = dialogTargets.get(d.id());
                writes.put(f, DialogDefinition.CODEC.encodeStart(JsonOps.INSTANCE, d).getOrThrow(IOException::new));
                keep.add(f);
            }
            for (QuestLine q : b.quests().values()) {
                Path f = questTargets.get(q.id());
                writes.put(f, QuestLine.CODEC.encodeStart(JsonOps.INSTANCE, q).getOrThrow(IOException::new));
                keep.add(f);
            }
            writes.put(dir.resolve("npcs.json"),
                    NpcMapping.CODEC.encodeStart(JsonOps.INSTANCE, b.npcs()).getOrThrow(IOException::new));
            writes.put(dir.resolve(BOSS_POINTS_FILE),
                    BossPoint.CODEC.listOf().encodeStart(JsonOps.INSTANCE, b.bossPoints()).getOrThrow(IOException::new));
            // Species sorted by key so the file stays diff-friendly.
            PetConfig pets = new PetConfig(b.pets().healthbar(), b.pets().capture(),
                    new java.util.TreeMap<>(b.pets().species()));
            writes.put(dir.resolve(PETS_FILE), PetConfig.CODEC.encodeStart(JsonOps.INSTANCE, pets)
                    .getOrThrow(IOException::new));
            writes.put(dir.resolve(ProtectConfig.FILE), ProtectConfig.CODEC.encodeStart(JsonOps.INSTANCE, b.protect())
                    .getOrThrow(IOException::new));
            writes.put(dir.resolve(ReinforceConfig.FILE), ReinforceConfig.CODEC.encodeStart(JsonOps.INSTANCE,
                    b.reinforce()).getOrThrow(IOException::new));
            writes.put(dir.resolve(HuntingConfig.FILE), HuntingConfig.CODEC.encodeStart(JsonOps.INSTANCE,
                    b.hunting()).getOrThrow(IOException::new));
            writes.put(dir.resolve(MobStatsConfig.FILE), MobStatsConfig.CODEC.encodeStart(JsonOps.INSTANCE,
                    b.mobs()).getOrThrow(IOException::new));
            writes.put(dir.resolve(EquipmentConfig.FILE), EquipmentConfig.CODEC.encodeStart(JsonOps.INSTANCE,
                    b.equipment()).getOrThrow(IOException::new));
            writes.put(dir.resolve(UpgradeConfig.FILE), UpgradeConfig.CODEC.encodeStart(JsonOps.INSTANCE,
                    b.upgrade()).getOrThrow(IOException::new));
            writes.put(dir.resolve(BossConfig.FILE), BossConfig.CODEC.encodeStart(JsonOps.INSTANCE,
                    b.bosses()).getOrThrow(IOException::new));
            writes.put(dir.resolve(DetectorConfig.FILE), DetectorConfig.CODEC.encodeStart(JsonOps.INSTANCE,
                    b.detector()).getOrThrow(IOException::new));
            writes.put(dir.resolve(ENCHANT_FILE), EnchantConfig.CODEC.encodeStart(JsonOps.INSTANCE,
                    b.enchant()).getOrThrow(IOException::new));
        } catch (IOException e) {
            return new SaveResult(false, List.of("encode failed: " + e.getMessage()), v.warnings(), null);
        }

        List<String> warnings = new ArrayList<>(v.warnings());
        Path backup;
        try {
            backup = backup(dir, warnings);
        } catch (IOException e) {
            return new SaveResult(false, List.of("backup failed, nothing was written: " + e.getMessage()),
                    warnings, null);
        }

        // Phase 1: everything to .tmp (a failure here leaves the real files untouched).
        List<Path> tmps = new ArrayList<>();
        try {
            for (Map.Entry<Path, JsonElement> en : writes.entrySet()) {
                tmps.add(writeTmp(en.getKey(), en.getValue()));
            }
        } catch (IOException e) {
            for (Path t : tmps) deleteQuietly(t);
            return new SaveResult(false, List.of("write failed, nothing was changed: " + e.getMessage()),
                    warnings, null);
        }
        // Phase 2: move into place, then delete files of removed ids.
        List<Path> targets = new ArrayList<>(writes.keySet());
        try {
            for (int n = 0; n < targets.size(); n++) {
                Files.move(tmps.get(n), targets.get(n), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            for (Map<String, Path> files : List.of(shopFiles, dialogFiles, questFiles)) {
                for (Path f : files.values()) if (!keep.contains(f)) Files.deleteIfExists(f);
            }
        } catch (IOException e) {
            for (Path t : tmps) deleteQuietly(t);
            return new SaveResult(false, List.of("save failed midway (" + e.getMessage()
                    + "); restore from backup " + backup), warnings, load(dir));
        }
        List<String> pointsBefore = bossPoints.stream().map(BossPoint::id).toList();
        LoadReport reload = load(dir);
        Set<String> pointsAfter = new java.util.HashSet<>();
        for (BossPoint p : bossPoints) pointsAfter.add(p.id());
        List<String> removed = pointsBefore.stream().filter(id -> !pointsAfter.contains(id)).toList();
        return new SaveResult(true, List.of(), warnings, reload, removed);
    }

    private static String pathKey(Path p) {
        return p.toAbsolutePath().normalize().toString().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Kept ids reuse their tracked file; new ids get {@code <id>.json}, or {@code <id>-2.json}, ... when that name
     * is (case-insensitively) taken by another id's file, a planned write or any file already on disk.
     */
    private static Map<String, Path> planTargets(Map<String, Path> tracked, java.util.Collection<String> ids,
                                                 Path kindDir, Set<String> used) {
        Map<String, Path> out = new HashMap<>();
        List<String> fresh = new ArrayList<>();
        for (String id : ids) {
            Path t = tracked.get(id);
            if (t != null) out.put(id, t);
            else fresh.add(id);
        }
        for (String id : fresh) {
            Path c = kindDir.resolve(id + ".json");
            for (int n = 2; used.contains(pathKey(c)) || Files.exists(c); n++) {
                c = kindDir.resolve(id + "-" + n + ".json");
            }
            used.add(pathKey(c));
            out.put(id, c);
        }
        return out;
    }

    /** Writes the JSON to a sibling .tmp file and returns it. */
    private static Path writeTmp(Path file, JsonElement json) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json, w);
        }
        return tmp;
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort
        }
    }

    /**
     * Copies shops/, dialogs/, quests/, npcs.json, boss_points.json, pets.json, protect.json and
     * reinforce.json to .backup/yyyyMMdd-HHmmss[-n]/
     * and prunes old backups.
     */
    private static Path backup(Path dir, List<String> pruneWarnings) throws IOException {
        Path root = dir.resolve(".backup");
        Files.createDirectories(root);
        String stamp = java.time.LocalDateTime.now().format(BACKUP_STAMP);
        Path target = root.resolve(stamp);
        for (int n = 2; Files.exists(target); n++) target = root.resolve(stamp + String.format("-%02d", n));
        Files.createDirectories(target);
        for (String sub : List.of("shops", "dialogs", "quests")) {
            Path src = dir.resolve(sub);
            if (!Files.isDirectory(src)) continue;
            try (Stream<Path> s = Files.walk(src)) {
                for (Path p : s.toList()) {
                    Path dst = target.resolve(sub).resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(dst);
                    else Files.copy(p, dst);
                }
            }
        }
        for (String file : List.of("npcs.json", BOSS_POINTS_FILE, PETS_FILE, ProtectConfig.FILE,
                ReinforceConfig.FILE, HuntingConfig.FILE, MobStatsConfig.FILE, BossConfig.FILE, EquipmentConfig.FILE, UpgradeConfig.FILE, DetectorConfig.FILE,
                ENCHANT_FILE)) {
            Path f = dir.resolve(file);
            if (Files.isRegularFile(f)) Files.copy(f, target.resolve(file));
        }
        List<Path> all;
        try (Stream<Path> s = Files.list(root)) {
            all = s.filter(Files::isDirectory).sorted().toList();
        }
        for (int n = 0; n < all.size() - BACKUP_KEEP; n++) {
            try {
                deleteTree(all.get(n));
            } catch (IOException e) {
                pruneWarnings.add("old backup " + all.get(n).getFileName() + " could not be deleted: " + e.getMessage());
            }
        }
        return target;
    }

    private static void deleteTree(Path p) throws IOException {
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
    }

    /** With dynamics disabled, current prices always equal base prices. */
    private static ShopDefinition normalized(ShopDefinition shop) {
        return shop.withEntries(shop.entries().stream().map(e -> e.withPrices(e.buyPrice(), e.sellPrice())).toList());
    }

    public EconomyConfig economy() {
        return economy;
    }

    public EnchantConfig enchant() {
        return enchant;
    }

    public PetConfig pets() {
        return pets;
    }

    /** False until the first {@link #load} (e.g. while spawn chunks load before ServerStartingEvent). */
    public boolean loaded() {
        return configDir != null;
    }

    /** Boss points from boss_points.json, in file order (immutable). */
    public List<BossPoint> bossPoints() {
        return bossPoints;
    }

    /** Writes boss_points.json as pretty JSON and replaces the in-memory list (no reload). */
    public synchronized void saveBossPoints(List<BossPoint> points) throws IOException {
        Path dir = configDir;
        if (dir == null) throw new IOException("data not loaded");
        List<BossPoint> copy = List.copyOf(points);
        JsonElement json = BossPoint.CODEC.listOf().encodeStart(JsonOps.INSTANCE, copy).getOrThrow(IOException::new);
        writeJson(dir.resolve(BOSS_POINTS_FILE), json);
        bossPoints = copy;
    }

    /** Global protection rules and regions from protect.json. */
    public ProtectConfig protect() {
        return protect;
    }

    /** Writes protect.json as pretty JSON and replaces the in-memory config (no reload). */
    public synchronized void saveProtect(ProtectConfig config) throws IOException {
        Path dir = configDir;
        if (dir == null) throw new IOException("data not loaded");
        JsonElement json = ProtectConfig.CODEC.encodeStart(JsonOps.INSTANCE, config).getOrThrow(IOException::new);
        writeJson(dir.resolve(ProtectConfig.FILE), json);
        protect = config;
    }

    /** Equipment reinforce numbers from reinforce.json (sanitized). */
    public ReinforceConfig reinforce() {
        return reinforce;
    }

    /** Hunting zones from hunting.json (sanitized). */
    public HuntingConfig hunting() {
        return hunting;
    }

    /** Writes hunting.json as pretty JSON and replaces the in-memory config (no reload). */
    public synchronized void saveHunting(HuntingConfig config) throws IOException {
        Path dir = configDir;
        if (dir == null) throw new IOException("data not loaded");
        JsonElement json = HuntingConfig.CODEC.encodeStart(JsonOps.INSTANCE, config).getOrThrow(IOException::new);
        writeJson(dir.resolve(HuntingConfig.FILE), json);
        hunting = config;
    }

    /** Field monster kill gold from field_gold.json (sanitized). */
    public FieldGoldConfig fieldGold() {
        return fieldGold;
    }

    /** Per entity type base stat overrides from mobs.json (sanitized). */
    public MobStatsConfig mobs() {
        return mobs;
    }

    /** Boss aggro and skill settings from bosses.json (sanitized). */
    public BossConfig bosses() {
        return bosses;
    }

    /** Per item attribute overrides from equipment.json (sanitized). */
    public EquipmentConfig equipment() {
        return equipment;
    }

    /** Equipment tier upgrade steps from upgrade.json (sanitized). */
    public UpgradeConfig upgrade() {
        return upgrade;
    }

    /** Detector range, durations and the item-to-ore target list from detector.json (sanitized). */
    public DetectorConfig detector() {
        return detector;
    }

    /** Writes the shop as pretty JSON (to its original file if known) and updates the in-memory copy. */
    public synchronized void saveShop(ShopDefinition shop) throws IOException {
        if (!economy.priceDynamics().enabled() && !economy.fluctuates(shop)) shop = normalized(shop);
        writeShopFile(shop);
        Map<String, ShopDefinition> s = new HashMap<>(shops);
        s.put(shop.id(), shop);
        shops = Map.copyOf(s);
        Map<String, ShopDefinition> raw = new HashMap<>(rawShops);
        raw.put(shop.id(), shop);
        rawShops = Map.copyOf(raw);
    }

    public record CurrentPrices(long buy, long sell) {}

    /**
     * Price-only update (dynamics): patches the current prices into the raw shop (entries for missing items kept),
     * writes that raw shop and updates both the raw and pruned copies. Returns the updated pruned shop.
     */
    public synchronized Optional<ShopDefinition> updateCurrentPrices(
            String shopId, Map<ResourceLocation, CurrentPrices> prices) throws IOException {
        ShopDefinition raw = rawShops.get(shopId);
        ShopDefinition pruned = shops.get(shopId);
        if (raw == null || pruned == null) return Optional.empty();
        for (Map.Entry<ResourceLocation, CurrentPrices> en : prices.entrySet()) {
            raw = raw.withCurrentPrices(en.getKey(), en.getValue().buy(), en.getValue().sell());
            pruned = pruned.withCurrentPrices(en.getKey(), en.getValue().buy(), en.getValue().sell());
        }
        writeShopFile(raw);
        Map<String, ShopDefinition> s = new HashMap<>(shops);
        s.put(shopId, pruned);
        shops = Map.copyOf(s);
        Map<String, ShopDefinition> r = new HashMap<>(rawShops);
        r.put(shopId, raw);
        rawShops = Map.copyOf(r);
        return Optional.of(pruned);
    }

    /** Writes the shop JSON to its original file (or shops/<id>.json) and remembers the file. */
    private synchronized void writeShopFile(ShopDefinition shop) throws IOException {
        Path dir = configDir;
        if (dir == null) throw new IOException("data not loaded");
        Path file = shopFiles.getOrDefault(shop.id(), dir.resolve("shops").resolve(shop.id() + ".json"));
        JsonElement json = ShopDefinition.CODEC.encodeStart(JsonOps.INSTANCE, shop)
                .getOrThrow(IOException::new);
        writeJson(file, json);
        Map<String, Path> f = new HashMap<>(shopFiles);
        f.put(shop.id(), file);
        shopFiles = Map.copyOf(f);
    }

    private static <T> Optional<T> read(Path p, Codec<T> codec, List<String> errors) {
        String name = p.getFileName().toString();
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            JsonElement json = JsonParser.parseReader(r);
            var result = codec.parse(JsonOps.INSTANCE, json);
            if (result.error().isPresent()) {
                errors.add(name + ": " + result.error().get().message());
                return Optional.empty();
            }
            return result.result();
        } catch (Exception e) {
            errors.add(name + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private static List<Path> jsonFiles(Path dir, List<String> errors) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".json") && Files.isRegularFile(p))
                    .sorted().toList();
        } catch (IOException e) {
            errors.add(dir.getFileName() + ": " + e.getMessage());
            return List.of();
        }
    }

    /** Pretty JSON via tmp file + move, so a crash never leaves a half-written file. */
    private static void writeJson(Path file, JsonElement json) throws IOException {
        Files.move(writeTmp(file, json), file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Installs each bundled default once, tracked in {@link #MARKER} (one relative path per line): a missing file is
     * copied, an existing npcs.json only gains the bundled byName keys it lacks. Files listed in the marker are never
     * touched again, so samples an operator deleted stay deleted. A non-empty dir without a marker predates the
     * marker: its {@link #LEGACY_FILES} were offered by the old first-run copy and are only recorded, except that an
     * existing npcs.json is still merged. Failures are reported per file and retried on the next load.
     */
    private static void copyDefaults(Path dir, List<String> errors) throws IOException {
        Path marker = dir.resolve(MARKER);
        boolean legacy = !Files.exists(marker) && hasAnyFile(dir);
        Set<String> installed = new java.util.HashSet<>();
        if (Files.exists(marker)) {
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) installed.add(line.strip());
            }
        }
        for (String f : DEFAULT_FILES) {
            if (installed.contains(f)) continue;
            Path target = dir.resolve(f);
            boolean isNpcs = f.equals("npcs.json");
            try {
                if (Files.exists(target)) {
                    if (isNpcs && !mergeNpcDefaults(target)) continue; // unreadable: retry next load
                } else if (!(legacy && LEGACY_FILES.contains(f))) {
                    copyBundled(f, target);
                }
                Files.writeString(marker, f + "\n", StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                errors.add(f + ": installing default failed: " + e.getMessage());
            }
        }
        applyNpcUpgrade(dir, marker, installed, NPC_CHIEF_UPGRADE, "이장", "chief", errors);
        applyNpcUpgrade(dir, marker, installed, NPC_BLACKSMITH_UPGRADE, "대장장이", "blacksmith", errors);
        applyBossGiraffeUpgrade(dir, marker, installed, errors);
        applyBearSkillsUpgrade(dir, marker, installed, errors);
        applyArmorEqualUpgrade(dir, marker, installed, errors);
    }

    /**
     * One-shot enchant.json patch: an armor part whose ability weights are exactly the old default (40/30/20/10)
     * gets 10 for each ability. A part the operator changed is left alone, as is everything else in the file. A
     * freshly copied enchant.json is already equal; a deleted one is not resurrected. An unreadable enchant.json is
     * retried on the next load.
     */
    private static void applyArmorEqualUpgrade(Path dir, Path marker, Set<String> installed, List<String> errors) {
        if (installed.contains(ENCHANT_ARMOR_EQUAL)) return;
        try {
            Path file = dir.resolve("enchant.json");
            if (Files.isRegularFile(file) && !equalizeArmorWeights(file)) return;
            Files.writeString(marker, ENCHANT_ARMOR_EQUAL + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            errors.add("enchant.json: making armor ability weights equal failed: " + e.getMessage());
        }
    }

    /** The armor ability weights shipped before 1.14.0, per part, in order of weight 40/30/20/10. */
    private static final java.util.Map<String, List<String>> OLD_ARMOR_WEIGHTS = java.util.Map.of(
            "helmet", List.of("swift", "goggles", "sense", "nightvision"),
            "chestplate", List.of("swift", "vitality", "regen", "undying"),
            "leggings", List.of("swift", "leap", "steadfast", "dodge"),
            "boots", List.of("swift", "farmstep", "climb", "landing"));
    private static final int[] OLD_ARMOR_VALUES = {40, 30, 20, 10};

    /** Edits the JSON tree so the rest of the file stays as the operator wrote it. False if the file is unreadable. */
    static boolean equalizeArmorWeights(Path file) throws IOException {
        com.google.gson.JsonObject root;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement json = JsonParser.parseReader(r);
            if (!json.isJsonObject()) return false;
            root = json.getAsJsonObject();
        } catch (com.google.gson.JsonParseException e) {
            return false;
        }
        JsonElement armor = root.get("armor");
        if (armor == null || !armor.isJsonObject()) return true;
        JsonElement weights = armor.getAsJsonObject().get("abilityWeights");
        if (weights == null || !weights.isJsonObject()) return true;
        boolean changed = false;
        for (java.util.Map.Entry<String, List<String>> part : OLD_ARMOR_WEIGHTS.entrySet()) {
            JsonElement element = weights.getAsJsonObject().get(part.getKey());
            if (element == null || !element.isJsonObject()) continue;
            com.google.gson.JsonObject byAbility = element.getAsJsonObject();
            List<String> ids = part.getValue();
            boolean old = byAbility.size() == ids.size();
            for (int i = 0; old && i < ids.size(); i++) {
                JsonElement v = byAbility.get(ids.get(i));
                old = v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()
                        && v.getAsDouble() == OLD_ARMOR_VALUES[i];
            }
            if (!old) continue;
            for (String id : ids) byAbility.addProperty(id, 10);
            changed = true;
        }
        if (changed) writeJson(file, root);
        return true;
    }

    /**
     * One-shot npcs.json patch recorded as {@code upgrade} in the marker. A freshly installed/merged npcs.json already
     * has the mapping, so only an earlier install ({@code npcs.json} already in the marker) is patched.
     */
    private static void applyNpcUpgrade(Path dir, Path marker, Set<String> installed, String upgrade, String npcName,
                                        String dialogId, List<String> errors) {
        if (installed.contains(upgrade)) return;
        try {
            if (!installed.contains("npcs.json") || addNameMapping(dir, npcName, dialogId)) {
                Files.writeString(marker, upgrade + "\n", StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            errors.add("npcs.json: adding " + npcName + " mapping failed: " + e.getMessage());
        }
    }

    /**
     * One-shot pets.json patch: adds the bundled boss_giraffe species when the file lacks it (an existing entry is never
     * overwritten). A freshly copied pets.json already has it; a deleted pets.json is not resurrected. An unreadable
     * pets.json is retried on the next load.
     */
    private static void applyBossGiraffeUpgrade(Path dir, Path marker, Set<String> installed, List<String> errors) {
        if (installed.contains(PETS_BOSS_GIRAFFE_UPGRADE)) return;
        try {
            Path file = dir.resolve(PETS_FILE);
            if (Files.isRegularFile(file) && !addBossGiraffeSpecies(file)) return;
            Files.writeString(marker, PETS_BOSS_GIRAFFE_UPGRADE + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            errors.add(PETS_FILE + ": adding boss_giraffe failed: " + e.getMessage());
        }
    }

    /**
     * One-shot bosses.json patch: the boss bear's entry gets the bundled skills (and speed, if unset) when the entry
     * is missing or its skill list is empty. A bear with any skill is left alone, as are all other entries and
     * fields. A freshly copied bosses.json already has them; a deleted bosses.json is not resurrected. An unreadable
     * bosses.json is retried on the next load.
     */
    private static void applyBearSkillsUpgrade(Path dir, Path marker, Set<String> installed, List<String> errors) {
        if (installed.contains(BOSSES_BEAR_UPGRADE)) return;
        try {
            Path file = dir.resolve(BossConfig.FILE);
            if (Files.isRegularFile(file) && !addBearSkills(file)) return;
            Files.writeString(marker, BOSSES_BEAR_UPGRADE + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            errors.add(BossConfig.FILE + ": adding boss bear skills failed: " + e.getMessage());
        }
    }

    /** Edits the JSON tree so other bosses stay as the operator wrote them. False if the file is unreadable. */
    private static boolean addBearSkills(Path file) throws IOException {
        com.google.gson.JsonObject root;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement json = JsonParser.parseReader(r);
            if (!json.isJsonObject()) return false;
            root = json.getAsJsonObject();
        } catch (com.google.gson.JsonParseException e) {
            return false;
        }
        JsonElement bossesElement = root.get("bosses");
        if (bossesElement != null && !bossesElement.isJsonObject()) return false;
        com.google.gson.JsonObject bosses = bossesElement == null ? new com.google.gson.JsonObject()
                : bossesElement.getAsJsonObject();
        JsonElement entry = bosses.get(BOSS_BEAR_KEY);
        if (entry != null && !entry.isJsonObject()) return false;
        com.google.gson.JsonObject bundled;
        try (InputStream in = GettData.class.getResourceAsStream("/gettcore_defaults/" + BossConfig.FILE)) {
            if (in == null) throw new IOException("missing bundled default: " + BossConfig.FILE);
            bundled = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject()
                    .getAsJsonObject("bosses").getAsJsonObject(BOSS_BEAR_KEY);
        }
        if (entry == null) {
            bosses.add(BOSS_BEAR_KEY, bundled.deepCopy());
        } else {
            com.google.gson.JsonObject bear = entry.getAsJsonObject();
            JsonElement skills = bear.get("skills");
            if (skills != null && !(skills.isJsonArray() && skills.getAsJsonArray().isEmpty())) return true;
            bear.add("skills", bundled.get("skills").deepCopy());
            if (!bear.has("speed")) bear.add("speed", bundled.get("speed").deepCopy());
        }
        root.add("bosses", bosses);
        writeJson(file, root);
        return true;
    }

    /** Edits the JSON tree so other species stay as the operator wrote them. False if the file is unreadable. */
    private static boolean addBossGiraffeSpecies(Path file) throws IOException {
        com.google.gson.JsonObject root;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement json = JsonParser.parseReader(r);
            if (!json.isJsonObject()) return false;
            root = json.getAsJsonObject();
        } catch (com.google.gson.JsonParseException e) {
            return false;
        }
        if (!(root.get("species") instanceof com.google.gson.JsonObject species)) return false;
        if (species.has(BOSS_GIRAFFE_KEY)) return true;
        try (InputStream in = GettData.class.getResourceAsStream("/gettcore_defaults/" + PETS_FILE)) {
            if (in == null) throw new IOException("missing bundled default: " + PETS_FILE);
            JsonElement bundled = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            species.add(BOSS_GIRAFFE_KEY, bundled.getAsJsonObject().getAsJsonObject("species").get(BOSS_GIRAFFE_KEY));
        }
        writeJson(file, root);
        return true;
    }

    /**
     * Adds only {@code npcName -> dialogId} to an existing npcs.json when the key is absent and
     * dialogs/{@code dialogId}.json exists; other (possibly deliberately deleted) defaults are left alone. Returns
     * false if npcs.json is unreadable.
     */
    private static boolean addNameMapping(Path dir, String npcName, String dialogId) throws IOException {
        Path file = dir.resolve("npcs.json");
        if (!Files.isRegularFile(file) || !Files.isRegularFile(dir.resolve("dialogs/" + dialogId + ".json"))) {
            return true;
        }
        Optional<NpcMapping> existing = read(file, NpcMapping.CODEC, new ArrayList<>());
        if (existing.isEmpty()) return false;
        if (existing.get().byName().containsKey(npcName)) return true;
        Map<String, String> byName = new LinkedHashMap<>(existing.get().byName());
        byName.put(npcName, dialogId);
        NpcMapping patched = new NpcMapping(existing.get().byUuid(), byName);
        writeJson(file, NpcMapping.CODEC.encodeStart(JsonOps.INSTANCE, patched).getOrThrow(IOException::new));
        return true;
    }

    private static void copyBundled(String f, Path target) throws IOException {
        try (InputStream in = GettData.class.getResourceAsStream("/gettcore_defaults/" + f)) {
            if (in == null) throw new IOException("missing bundled default: " + f);
            Files.createDirectories(target.getParent());
            Files.copy(in, target);
        }
    }

    private static boolean hasAnyFile(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.anyMatch(Files::isRegularFile);
        }
    }

    /** Returns false (nothing written) if the existing file cannot be read. */
    private static boolean mergeNpcDefaults(Path file) throws IOException {
        // An unreadable npcs.json is left untouched; load() reports its error when it reads the file.
        Optional<NpcMapping> existing = read(file, NpcMapping.CODEC, new ArrayList<>());
        if (existing.isEmpty()) return false;
        NpcMapping defaults;
        try (InputStream in = GettData.class.getResourceAsStream("/gettcore_defaults/npcs.json")) {
            if (in == null) throw new IOException("missing bundled default: npcs.json");
            JsonElement json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            defaults = NpcMapping.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(IOException::new);
        }
        Map<String, String> byName = new LinkedHashMap<>(existing.get().byName());
        boolean changed = false;
        for (Map.Entry<String, String> e : defaults.byName().entrySet()) {
            changed |= byName.putIfAbsent(e.getKey(), e.getValue()) == null;
        }
        if (!changed) return true;
        NpcMapping merged = new NpcMapping(existing.get().byUuid(), byName);
        writeJson(file, NpcMapping.CODEC.encodeStart(JsonOps.INSTANCE, merged).getOrThrow(IOException::new));
        return true;
    }
}
