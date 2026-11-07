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
            "economy.json", "npcs.json", "enchant.json", "shops/farmer.json", "dialogs/farmer.json",
            "dialogs/enchanter.json", "dialogs/chief.json", "quests/main.json"};
    /** Defaults shipped before the install marker existed (copied only into an empty dir). */
    private static final Set<String> LEGACY_FILES = Set.of(
            "economy.json", "npcs.json", "shops/farmer.json", "dialogs/farmer.json");
    /** Lists the bundled defaults already installed into the config dir. */
    static final String MARKER = ".defaults-installed";
    /** One-shot upgrade (1.2.0): adds only the 이장 -> chief name mapping to an npcs.json installed earlier. */
    static final String NPC_CHIEF_UPGRADE = "npcs.json@chief";

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

        validate(newShops, newDialogs, newNpcs);
        warnUnmappedQuestNpcs(newQuests, newNpcs, warnings);

        shopFiles = Map.copyOf(newShopFiles);
        dialogFiles = Map.copyOf(newDialogFiles);
        questFiles = Map.copyOf(newQuestFiles);
        dialogs = Map.copyOf(newDialogs);
        npcs = newNpcs;
        economy = newEco;
        enchant = newEnchant;
        // Normalization applies to the raw (file) shops so entries for missing items are never dropped from disk.
        if (!newEco.priceDynamics().enabled()) {
            for (Map.Entry<String, ShopDefinition> en : List.copyOf(newShops.entrySet())) {
                ShopDefinition sd = en.getValue();
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
            prunedQuests.put(ql.id(), QuestPrune.prune(ql, registryChecks, warnings));
        }
        rawQuestLines = Map.copyOf(newQuests);
        questLines = Map.copyOf(prunedQuests);
        rawShops = Map.copyOf(newShops);
        shops = Map.copyOf(pruned);
        warnings.forEach(w -> LOGGER.warn("gettcore: {}", w));
        return new LoadReport(newShops.size(), newDialogs.size(), newQuests.size(), List.copyOf(errors), List.copyOf(warnings));
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
        return new ShopDefinition(sd.id(), sd.name(), sd.tabs(), List.copyOf(kept));
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
                new java.util.TreeMap<>(rawQuestLines));
    }

    public record SaveResult(boolean saved, List<String> errors, List<String> warnings, LoadReport reload) {}

    private static final int BACKUP_KEEP = 20;
    private static final java.time.format.DateTimeFormatter BACKUP_STAMP =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Validates the whole bundle; if clean, backs up the current files, writes every shop/dialog/quest file and
     * npcs.json (all to .tmp first, then moved), deletes files of ids no longer present and reloads.
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
        LoadReport reload = load(dir);
        return new SaveResult(true, List.of(), warnings, reload);
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

    /** Copies shops/, dialogs/, quests/ and npcs.json to .backup/yyyyMMdd-HHmmss[-n]/ and prunes old backups. */
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
        Path npc = dir.resolve("npcs.json");
        if (Files.isRegularFile(npc)) Files.copy(npc, target.resolve("npcs.json"));
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
        return new ShopDefinition(shop.id(), shop.name(), shop.tabs(),
                shop.entries().stream().map(e -> e.withPrices(e.buyPrice(), e.sellPrice())).toList());
    }

    public EconomyConfig economy() {
        return economy;
    }

    public EnchantConfig enchant() {
        return enchant;
    }

    /** Writes the shop as pretty JSON (to its original file if known) and updates the in-memory copy. */
    public synchronized void saveShop(ShopDefinition shop) throws IOException {
        if (!economy.priceDynamics().enabled()) shop = normalized(shop);
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
        if (!installed.contains(NPC_CHIEF_UPGRADE)) {
            try {
                // A freshly installed/merged npcs.json already has 이장; only an earlier install needs the patch.
                if (!installed.contains("npcs.json") || addChiefMapping(dir)) {
                    Files.writeString(marker, NPC_CHIEF_UPGRADE + "\n",StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                }
            } catch (IOException e) {
                errors.add("npcs.json: adding 이장 mapping failed: " + e.getMessage());
            }
        }
    }

    /**
     * Adds only {@code "이장": "chief"} to an existing npcs.json when the key is absent and dialogs/chief.json exists;
     * other (possibly deliberately deleted) defaults are left alone. Returns false if npcs.json is unreadable.
     */
    private static boolean addChiefMapping(Path dir) throws IOException {
        Path file = dir.resolve("npcs.json");
        if (!Files.isRegularFile(file) || !Files.isRegularFile(dir.resolve("dialogs/chief.json"))) return true;
        Optional<NpcMapping> existing = read(file, NpcMapping.CODEC, new ArrayList<>());
        if (existing.isEmpty()) return false;
        if (existing.get().byName().containsKey("이장")) return true;
        Map<String, String> byName = new LinkedHashMap<>(existing.get().byName());
        byName.put("이장", "chief");
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
