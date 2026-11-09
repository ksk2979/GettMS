package org.gettserver.gettcore.balance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;

/**
 * The gettcore balance data pack ({@code <world>/datapacks/gett_balance/}): copies of other mods' data files with a
 * few values changed. Plain file IO, no Minecraft server needed (tests use a temp folder).
 */
public final class PackFiles {
    public static final String PACK_NAME = "gett_balance";
    /** Id of a world-folder pack: "file/" + folder name. */
    public static final String PACK_ID = "file/" + PACK_NAME;
    /** Data pack format of 1.21.1. */
    public static final int PACK_FORMAT = 48;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private PackFiles() {}

    /** Creates the pack folder and pack.mcmeta when missing. An existing pack.mcmeta is left alone. */
    public static boolean ensurePack(Path pack) throws IOException {
        Path meta = pack.resolve("pack.mcmeta");
        if (Files.isRegularFile(meta)) return false;
        Files.createDirectories(pack.resolve("data"));
        JsonObject inner = new JsonObject();
        inner.addProperty("pack_format", PACK_FORMAT);
        inner.addProperty("description", "Gett balance (gettcore editor copies of mod data)");
        JsonObject root = new JsonObject();
        root.add("pack", inner);
        writeAtomic(meta, GSON.toJson(root));
        return true;
    }

    /** {@code data/<namespace>/<path>} inside the pack. The path is a full resource path including ".json". */
    public static Path file(Path pack, ResourceLocation loc) {
        Path data = pack.resolve("data").normalize();
        Path f = data.resolve(loc.getNamespace()).resolve(loc.getPath()).normalize();
        if (!f.startsWith(data.resolve(loc.getNamespace()))) {
            throw new IllegalArgumentException("path escapes the pack: " + loc);
        }
        return f;
    }

    public static Optional<JsonObject> read(Path pack, ResourceLocation loc) {
        Path f = file(pack, loc);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            JsonElement e = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            return e.isJsonObject() ? Optional.of(e.getAsJsonObject()) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Writes the copy; returns false (and does not touch the file) when it already has exactly this content. */
    public static boolean write(Path pack, ResourceLocation loc, JsonObject content) throws IOException {
        if (read(pack, loc).filter(content::equals).isPresent()) return false;
        Path f = file(pack, loc);
        Files.createDirectories(f.getParent());
        writeAtomic(f, GSON.toJson(content));
        return true;
    }

    /** Deletes the copy and any folders left empty below {@code data/}. Returns whether a file was removed. */
    public static boolean delete(Path pack, ResourceLocation loc) throws IOException {
        Path f = file(pack, loc);
        if (!Files.deleteIfExists(f)) return false;
        Path data = pack.resolve("data").normalize();
        for (Path dir = f.getParent(); dir != null && dir.startsWith(data) && !dir.equals(data); dir = dir.getParent()) {
            try (Stream<Path> s = Files.list(dir)) {
                if (s.findAny().isPresent()) break;
            }
            Files.delete(dir);
        }
        return true;
    }

    /** Whether the pack holds any file under {@code data/} (a copy of ours or anything else). */
    public static boolean hasData(Path pack) {
        Path data = pack.resolve("data");
        if (!Files.isDirectory(data)) return false;
        try (Stream<Path> s = Files.walk(data)) {
            return s.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** All .json files under {@code data/<namespace>/<folder>/}, as resource locations, sorted. */
    public static List<ResourceLocation> listUnder(Path pack, String namespace, String folder) {
        Path root = pack.resolve("data").resolve(namespace);
        Path dir = root.resolve(folder);
        if (!Files.isDirectory(dir)) return List.of();
        List<ResourceLocation> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json")).forEach(p -> {
                String rel = root.relativize(p).toString().replace('\\', '/');
                ResourceLocation loc = ResourceLocation.tryBuild(namespace, rel);
                if (loc != null) out.add(loc);
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(ResourceLocation::compareTo);
        return out;
    }

    /** {@link #listUnder} for every namespace in the pack. */
    public static List<ResourceLocation> listFolder(Path pack, String folder) {
        Path data = pack.resolve("data");
        if (!Files.isDirectory(data)) return List.of();
        List<ResourceLocation> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(data)) {
            s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted()
                    .forEach(ns -> out.addAll(listUnder(pack, ns, folder)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /**
     * Index (into a resource stack, lowest priority first) of the file the mod itself provides: the topmost one that
     * is not from our pack. -1 when only our pack has it.
     */
    public static int originalIndex(List<String> sourcePackIds) {
        for (int i = sourcePackIds.size() - 1; i >= 0; i--) {
            if (!PACK_ID.equals(sourcePackIds.get(i))) return i;
        }
        return -1;
    }

    /** Editor seconds to game ticks, nearest tick (2.5 s = 50 ticks). */
    public static int secondsToTicks(double seconds) {
        return (int) Math.round(seconds * 20.0);
    }

    public static double ticksToSeconds(double ticks) {
        return ticks / 20.0;
    }

    private static void writeAtomic(Path target, String text) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, text + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
