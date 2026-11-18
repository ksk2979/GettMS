package org.gettserver.gettcore.preset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiPredicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Item presets: a saved item <b>with its components</b> (a scroll holding one spell, an enchanted sword, a potion)
 * under a name, usable anywhere gettcore takes an item id as {@code preset:<name>} — shop entries, quest rewards,
 * hunting and boss drops. Everything keyed by item id keeps working because the preset id is an ordinary
 * {@link ResourceLocation}; only the places that turn an id into a stack go through {@link #stack}.
 *
 * <p>{@code config/gettcore/presets.json}: {@code {"presets": {"fireball3": <ItemStack json>}}}. Written by
 * {@code /프리셋 저장 <이름>} from the held item. The server reads the file and decodes it with the world's
 * registries; clients get the decoded stacks by packet. Both ends keep them in the same static map (in single player
 * the two hold the same content).
 */
public final class ItemPresets {
    public static final String NAMESPACE = "preset";
    public static final String FILE = "presets.json";
    public static final int MAX_PRESETS = 2000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** name -> prototype stack (count 1). Replaced as a whole, never mutated. */
    private static volatile Map<String, ItemStack> stacks = Map.of();

    private ItemPresets() {}

    public static boolean isPreset(ResourceLocation id) {
        return id != null && NAMESPACE.equals(id.getNamespace());
    }

    /** A valid preset name is a resource path without slashes: lower case letters, digits, {@code _ - .} */
    public static boolean validName(String name) {
        return name != null && !name.isEmpty() && name.length() <= 64 && name.indexOf('/') < 0
                && ResourceLocation.isValidPath(name);
    }

    public static ResourceLocation idOf(String name) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, name);
    }

    public static boolean exists(ResourceLocation id) {
        return isPreset(id) && stacks.containsKey(id.getPath());
    }

    /** Names in order. */
    public static List<String> names() {
        return List.copyOf(stacks.keySet());
    }

    public static Map<String, ItemStack> all() {
        return stacks;
    }

    /**
     * The stack an item id stands for: the preset (with its components) for {@code preset:*}, a plain stack for a
     * registered item, {@link ItemStack#EMPTY} when the id is unknown.
     */
    public static ItemStack stack(ResourceLocation id, int count) {
        if (isPreset(id)) {
            ItemStack s = stacks.get(id.getPath());
            return s == null ? ItemStack.EMPTY : s.copyWithCount(count);
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item, count);
    }

    /** Display name of what the id stands for (the id itself when unknown). */
    public static String name(ResourceLocation id) {
        ItemStack s = stack(id, 1);
        return s.isEmpty() ? id.toString() : s.getHoverName().getString();
    }

    /**
     * Whether an inventory stack is "this item" for trading: a preset matches stacks with exactly its components;
     * a plain id is decided by {@code plain} (the caller's own rule).
     */
    public static boolean matches(ItemStack stack, ResourceLocation id, BiPredicate<ItemStack, ResourceLocation> plain) {
        if (!isPreset(id)) return plain.test(stack, id);
        ItemStack proto = stacks.get(id.getPath());
        return proto != null && !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, proto);
    }

    /** Client: the server's presets arrived. */
    public static void setAll(Map<String, ItemStack> received) {
        stacks = Collections.unmodifiableMap(new TreeMap<>(received));
    }

    /* ----- server: file ----- */

    /** Reads presets.json and decodes every stack. Bad entries are skipped with a line in the returned list. */
    public static List<String> load(Path dir, HolderLookup.Provider registries) {
        List<String> errors = new ArrayList<>();
        Map<String, ItemStack> out = new TreeMap<>();
        Path file = dir.resolve(FILE);
        if (Files.isRegularFile(file)) {
            try {
                JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                JsonElement presets = root.isJsonObject() ? root.getAsJsonObject().get("presets") : null;
                if (presets != null && presets.isJsonObject()) {
                    var ops = registries.createSerializationContext(JsonOps.INSTANCE);
                    for (Map.Entry<String, JsonElement> e : presets.getAsJsonObject().entrySet()) {
                        if (!validName(e.getKey())) {
                            errors.add(FILE + ": \"" + e.getKey() + "\" is not a valid preset name, skipped");
                            continue;
                        }
                        ItemStack.CODEC.parse(ops, e.getValue())
                                .resultOrPartial(msg -> errors.add(FILE + ": " + e.getKey() + ": " + msg))
                                .filter(s -> !s.isEmpty())
                                .ifPresent(s -> out.put(e.getKey(), s.copyWithCount(1)));
                    }
                }
            } catch (IOException | RuntimeException e) {
                errors.add(FILE + ": " + e);
            }
        }
        stacks = Collections.unmodifiableMap(out);
        return errors;
    }

    /** Adds or replaces a preset and writes the file. */
    public static void put(Path dir, HolderLookup.Provider registries, String name, ItemStack stack) throws IOException {
        Map<String, ItemStack> next = new TreeMap<>(stacks);
        next.put(name, stack.copyWithCount(1));
        write(dir, registries, next);
        stacks = Collections.unmodifiableMap(next);
    }

    /** Removes a preset and writes the file. False when there was none. */
    public static boolean remove(Path dir, HolderLookup.Provider registries, String name) throws IOException {
        if (!stacks.containsKey(name)) return false;
        Map<String, ItemStack> next = new TreeMap<>(stacks);
        next.remove(name);
        write(dir, registries, next);
        stacks = Collections.unmodifiableMap(next);
        return true;
    }

    private static void write(Path dir, HolderLookup.Provider registries, Map<String, ItemStack> map) throws IOException {
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        JsonObject presets = new JsonObject();
        for (Map.Entry<String, ItemStack> e : map.entrySet()) {
            JsonElement json = ItemStack.CODEC.encodeStart(ops, e.getValue()).result().orElse(null);
            if (json == null) throw new IOException("preset " + e.getKey() + " could not be encoded");
            presets.add(e.getKey(), json);
        }
        JsonObject root = new JsonObject();
        root.add("presets", presets);
        Files.createDirectories(dir);
        Path target = dir.resolve(FILE);
        Path tmp = dir.resolve(FILE + ".tmp");
        Files.writeString(tmp, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
