package org.gettserver.gettcore.balance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 데이터팩 사본 쓰기/지우기와 원본 고르기. */
class PackFilesTest {
    @TempDir Path tmp;

    private Path pack() {
        return tmp.resolve("datapacks").resolve(PackFiles.PACK_NAME);
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void packIdIsTheWorldFolderId() {
        assertEquals("file/gett_balance", PackFiles.PACK_ID);
    }

    @Test
    void ensurePackCreatesFolderAndMcmetaOnce() throws Exception {
        assertTrue(PackFiles.ensurePack(pack()));
        Path meta = pack().resolve("pack.mcmeta");
        assertTrue(Files.isRegularFile(meta));
        JsonObject m = json(Files.readString(meta, StandardCharsets.UTF_8));
        assertEquals(48, m.getAsJsonObject("pack").get("pack_format").getAsInt());
        assertFalse(PackFiles.ensurePack(pack()), "already there: nothing created");
    }

    @Test
    void existingPackKeepsItsMcmetaAndOtherFiles() throws Exception {
        Files.createDirectories(pack().resolve("data/foo/bar"));
        Files.writeString(pack().resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":48,\"description\":\"mine\"}}");
        Files.writeString(pack().resolve("data/foo/bar/keep.json"), "{\"a\":1}");
        assertFalse(PackFiles.ensurePack(pack()));
        PackFiles.write(pack(), ResourceLocation.parse("cgs:weapons/revolver.json"), json("{\"x\":1}"));
        PackFiles.delete(pack(), ResourceLocation.parse("cgs:weapons/revolver.json"));
        assertTrue(Files.readString(pack().resolve("pack.mcmeta")).contains("mine"));
        assertEquals("{\"a\":1}", Files.readString(pack().resolve("data/foo/bar/keep.json")));
    }

    @Test
    void writeReadDeleteRoundTrip() throws Exception {
        ResourceLocation loc = ResourceLocation.parse("ntgl:weapons/guns/shotgun.json");
        assertTrue(PackFiles.read(pack(), loc).isEmpty());
        JsonObject o = json("{\"general\":{\"rate\":15,\"weird\":[1,2]},\"zzz\":true}");
        assertTrue(PackFiles.write(pack(), loc, o), "new file");
        assertTrue(Files.isRegularFile(pack().resolve("data/ntgl/weapons/guns/shotgun.json")));
        assertEquals(o, PackFiles.read(pack(), loc).orElseThrow());
        assertFalse(PackFiles.write(pack(), loc, o.deepCopy()), "same content: not rewritten");
        assertTrue(PackFiles.delete(pack(), loc));
        assertFalse(Files.exists(pack().resolve("data/ntgl")), "empty folders pruned up to data/");
        assertTrue(Files.isDirectory(pack().resolve("data")));
        assertFalse(PackFiles.delete(pack(), loc), "already gone");
    }

    @Test
    void listUnderFindsOnlyThatFolder() throws Exception {
        PackFiles.write(pack(), ResourceLocation.parse("gettcore:neoforge/biome_modifier/spawn_tweak/minecraft/zombie.json"), json("{}"));
        PackFiles.write(pack(), ResourceLocation.parse("gettcore:neoforge/biome_modifier/spawn_tweak/wom/hollow.json"), json("{}"));
        PackFiles.write(pack(), ResourceLocation.parse("wom:neoforge/biome_modifier/spawn_hollow.json"), json("{}"));
        List<ResourceLocation> got = PackFiles.listUnder(pack(), "gettcore", "neoforge/biome_modifier/spawn_tweak");
        assertEquals(List.of(ResourceLocation.parse("gettcore:neoforge/biome_modifier/spawn_tweak/minecraft/zombie.json"),
                ResourceLocation.parse("gettcore:neoforge/biome_modifier/spawn_tweak/wom/hollow.json")), got);
        assertEquals(List.of(), PackFiles.listUnder(tmp.resolve("nothing"), "gettcore", "x"));
    }

    @Test
    void hasDataOnlyWhenACopyExists() throws Exception {
        assertFalse(PackFiles.hasData(pack()), "no folder");
        PackFiles.ensurePack(pack());
        assertFalse(PackFiles.hasData(pack()), "pack.mcmeta only");
        ResourceLocation loc = ResourceLocation.parse("cgs:weapons/revolver.json");
        PackFiles.write(pack(), loc, json("{}"));
        assertTrue(PackFiles.hasData(pack()));
        PackFiles.delete(pack(), loc);
        assertFalse(PackFiles.hasData(pack()));
    }

    @Test
    void rejectsPathsThatEscapeThePack() {
        assertThrows(IllegalArgumentException.class,
                () -> PackFiles.file(pack(), ResourceLocation.fromNamespaceAndPath("cgs", "../../evil.json")));
    }

    @Test
    void originalIsTheTopmostPackThatIsNotOurs() {
        assertEquals(1, PackFiles.originalIndex(List.of("mod/cgs", "mod/other", PackFiles.PACK_ID)));
        assertEquals(0, PackFiles.originalIndex(List.of("mod/cgs")));
        assertEquals(-1, PackFiles.originalIndex(List.of(PackFiles.PACK_ID)));
        assertEquals(-1, PackFiles.originalIndex(List.of()));
    }

    @Test
    void ticksAndSecondsRoundToNearestTick() {
        assertEquals(50, PackFiles.secondsToTicks(2.5));
        assertEquals(1, PackFiles.secondsToTicks(0.04));
        assertEquals(0, PackFiles.secondsToTicks(0));
        assertEquals(3, PackFiles.secondsToTicks(0.149));
        assertEquals(2.5, PackFiles.ticksToSeconds(50), 1e-9);
        assertEquals(0.05, PackFiles.ticksToSeconds(1), 1e-9);
    }
}
