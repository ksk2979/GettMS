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
import net.minecraft.core.registries.BuiltInRegistries;
import org.gettserver.gettcore.enchant.EnchantConfig;
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
            "dialogs/enchanter.json"};
    /** Defaults shipped before the install marker existed (copied only into an empty dir). */
    private static final Set<String> LEGACY_FILES = Set.of(
            "economy.json", "npcs.json", "shops/farmer.json", "dialogs/farmer.json");
    /** Lists the bundled defaults already installed into the config dir. */
    static final String MARKER = ".defaults-installed";

    /** Callbacks run by /gett reload after data was re-read (e.g. Task 8: close open ShopMenu screens). */
    private static final Set<Runnable> RELOAD_HOOKS = new CopyOnWriteArraySet<>();

    private volatile Map<String, ShopDefinition> shops = Map.of();
    private volatile Map<String, DialogDefinition> dialogs = Map.of();
    private volatile Map<String, Path> shopFiles = Map.of();
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

    public record LoadReport(int shops, int dialogs, List<String> errors) {}

    public LoadReport load(Path dir) {
        List<String> errors = new ArrayList<>();
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
        for (Path p : jsonFiles(dir.resolve("dialogs"), errors)) {
            read(p, DialogDefinition.CODEC, errors).ifPresent(d -> {
                if (newDialogs.putIfAbsent(d.id(), d) != null) {
                    errors.add(p.getFileName() + ": duplicate dialog id " + d.id());
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

        shops = Map.copyOf(newShops);
        shopFiles = Map.copyOf(newShopFiles);
        dialogs = Map.copyOf(newDialogs);
        npcs = newNpcs;
        economy = newEco;
        enchant = newEnchant;
        if (!newEco.priceDynamics().enabled()) {
            for (ShopDefinition sd : List.copyOf(newShops.values())) {
                ShopDefinition norm = normalized(sd);
                if (!norm.equals(sd)) {
                    try {
                        saveShop(norm);
                    } catch (IOException e) {
                        errors.add("shop " + sd.id() + ": " + e.getMessage());
                        Map<String, ShopDefinition> m = new HashMap<>(shops);
                        m.put(sd.id(), norm);
                        shops = Map.copyOf(m);
                    }
                }
            }
        }
        return new LoadReport(newShops.size(), newDialogs.size(), List.copyOf(errors));
    }

    private static void validate(Map<String, ShopDefinition> shops, Map<String, DialogDefinition> dialogs, NpcMapping npcs) {
        for (ShopDefinition sh : shops.values()) {
            Set<String> tabIds = new java.util.HashSet<>();
            sh.tabs().forEach(t -> tabIds.add(t.id()));
            for (ShopEntry e : sh.entries()) {
                try {
                    if (!BuiltInRegistries.ITEM.containsKey(e.item())) {
                        LOGGER.warn("gettcore: shop {} has unknown item {}", sh.id(), e.item());
                    }
                } catch (Throwable t) {
                    // registry not bootstrapped (plain unit test): skip the item check
                }
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

    public Optional<ShopDefinition> shop(String id) {
        return Optional.ofNullable(shops.get(id));
    }

    public java.util.Collection<ShopDefinition> allShops() {
        return shops.values();
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
        Path dir = configDir;
        if (dir == null) throw new IOException("data not loaded");
        Path file = shopFiles.getOrDefault(shop.id(), dir.resolve("shops").resolve(shop.id() + ".json"));
        JsonElement json = ShopDefinition.CODEC.encodeStart(JsonOps.INSTANCE, shop)
                .getOrThrow(IOException::new);
        writeJson(file, json);
        Map<String, ShopDefinition> s = new HashMap<>(shops);
        s.put(shop.id(), shop);
        shops = Map.copyOf(s);
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
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json, w);
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
