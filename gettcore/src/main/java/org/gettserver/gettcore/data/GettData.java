package org.gettserver.gettcore.data;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Stream;

/**
 * Loads shops, dialogs, NPC mapping and economy config from JSON files under the config directory.
 * Free of Minecraft server classes so it can be unit tested.
 */
public final class GettData {
    private static final GettData INSTANCE = new GettData();
    private static final String[] DEFAULT_FILES = {
            "economy.json", "npcs.json", "shops/farmer.json", "dialogs/farmer.json"};

    /** Callbacks run by /gett reload after data was re-read (e.g. Task 8: close open ShopMenu screens). */
    private static final Set<Runnable> RELOAD_HOOKS = new CopyOnWriteArraySet<>();

    private volatile Map<String, ShopDefinition> shops = Map.of();
    private volatile Map<String, DialogDefinition> dialogs = Map.of();
    private volatile Map<String, Path> shopFiles = Map.of();
    private volatile NpcMapping npcs = NpcMapping.EMPTY;
    private volatile EconomyConfig economy = EconomyConfig.DEFAULT;
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
            if (!hasAnyFile(dir)) copyDefaults(dir);
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

        shops = Map.copyOf(newShops);
        shopFiles = Map.copyOf(newShopFiles);
        dialogs = Map.copyOf(newDialogs);
        npcs = newNpcs;
        economy = newEco;
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

    /** Writes the shop as pretty JSON (to its original file if known) and updates the in-memory copy. */
    public synchronized void saveShop(ShopDefinition shop) throws IOException {
        if (!economy.priceDynamics().enabled()) shop = normalized(shop);
        Path dir = configDir;
        if (dir == null) throw new IOException("data not loaded");
        Path file = shopFiles.getOrDefault(shop.id(), dir.resolve("shops").resolve(shop.id() + ".json"));
        JsonElement json = ShopDefinition.CODEC.encodeStart(JsonOps.INSTANCE, shop)
                .getOrThrow(IOException::new);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json, w);
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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

    private static boolean hasAnyFile(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.anyMatch(Files::isRegularFile);
        }
    }

    private static void copyDefaults(Path dir) throws IOException {
        for (String f : DEFAULT_FILES) {
            try (InputStream in = GettData.class.getResourceAsStream("/gettcore_defaults/" + f)) {
                if (in == null) throw new IOException("missing bundled default: " + f);
                Path target = dir.resolve(f);
                Files.createDirectories(target.getParent());
                Files.copy(in, target);
            }
        }
    }
}
