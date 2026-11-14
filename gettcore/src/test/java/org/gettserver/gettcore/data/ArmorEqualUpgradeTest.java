package org.gettserver.gettcore.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One-shot enchant.json patch: only armor parts still at the old 40/30/20/10 default become equal. */
class ArmorEqualUpgradeTest {

    private static JsonObject weights(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject()
                .getAsJsonObject("armor").getAsJsonObject("abilityWeights");
    }

    @Test
    void oldDefaultsBecomeEqualAndEditedPartsStay(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("enchant.json");
        Files.writeString(file, "{\"harvest\":{\"radius\":[1,2,4]},\"armor\":{\"abilityWeights\":{"
                + "\"helmet\":{\"swift\":40,\"goggles\":30,\"sense\":20,\"nightvision\":10},"
                + "\"chestplate\":{\"swift\":40,\"vitality\":30,\"regen\":20,\"undying\":5},"
                + "\"boots\":{\"swift\":40,\"farmstep\":30,\"climb\":20,\"landing\":10}}}}", StandardCharsets.UTF_8);
        assertTrue(GettData.equalizeArmorWeights(file));
        JsonObject w = weights(file);
        for (String id : new String[] {"swift", "goggles", "sense", "nightvision"}) {
            assertEquals(10, w.getAsJsonObject("helmet").get(id).getAsInt(), id);
        }
        assertEquals(10, w.getAsJsonObject("boots").get("swift").getAsInt());
        // 사용자가 고친 부위는 그대로
        assertEquals(40, w.getAsJsonObject("chestplate").get("swift").getAsInt());
        assertEquals(5, w.getAsJsonObject("chestplate").get("undying").getAsInt());
        // 다른 내용도 그대로
        assertEquals(4, JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject()
                .getAsJsonObject("harvest").getAsJsonArray("radius").get(2).getAsInt());
    }

    @Test
    void fileWithoutArmorWeightsIsLeftAlone(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("enchant.json");
        String text = "{\"harvest\":{\"radius\":[1,2,3]}}";
        Files.writeString(file, text, StandardCharsets.UTF_8);
        assertTrue(GettData.equalizeArmorWeights(file));
        assertEquals(text, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void unreadableFileIsRetriedLater(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("enchant.json");
        Files.writeString(file, "{ not json", StandardCharsets.UTF_8);
        assertFalse(GettData.equalizeArmorWeights(file));
    }
}
