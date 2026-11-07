package org.gettserver.gettcore.editor.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EditorResultJsonTest {
    @Test
    void snapshotObjectIsReturnedAsText() {
        String r = "{\"saved\":true,\"errors\":[],\"snapshot\":{\"shops\":{\"a\":{}},\"npcs\":{}},\"warnings\":[]}";
        Optional<String> s = EditorResultJson.snapshotOf(r);
        assertTrue(s.isPresent());
        assertEquals(JsonParser.parseString("{\"shops\":{\"a\":{}},\"npcs\":{}}"), JsonParser.parseString(s.get()));
    }

    @Test
    void snapshotStringIsAccepted() {
        assertEquals(Optional.of("{\"shops\":{}}"),
                EditorResultJson.snapshotOf("{\"saved\":true,\"snapshot\":\"{\\\"shops\\\":{}}\"}"));
    }

    @Test
    void nullMissingOrMalformedGiveEmpty() {
        assertTrue(EditorResultJson.snapshotOf("{\"saved\":false,\"snapshot\":null}").isEmpty());
        assertTrue(EditorResultJson.snapshotOf("{\"saved\":false}").isEmpty());
        assertTrue(EditorResultJson.snapshotOf("not json {").isEmpty());
        assertTrue(EditorResultJson.snapshotOf("[1]").isEmpty());
        assertTrue(EditorResultJson.snapshotOf("{\"snapshot\":5}").isEmpty());
    }

    @Test
    void failureHasResultShape() {
        JsonObject o = JsonParser.parseString(EditorResultJson.failure("boom \"x\"")).getAsJsonObject();
        assertFalse(o.get("saved").getAsBoolean());
        assertEquals("boom \"x\"", o.getAsJsonArray("errors").get(0).getAsString());
        assertEquals(0, o.getAsJsonArray("warnings").size());
        assertTrue(o.get("snapshot").isJsonNull());
    }

    private static final java.util.function.UnaryOperator<String> TR =
            k -> k.equals("advancements.story.mine_diamond.title") ? "다이아몬드!" : k;

    @Test
    void advancementTitlesAreTranslated() {
        String snap = "{\"shops\":{},\"refs\":{\"advancements\":["
                + "{\"id\":\"minecraft:story/mine_diamond\",\"title\":\"advancements.story.mine_diamond.title\"},"
                + "{\"id\":\"x:y\",\"title\":\"Plain title\"}]}}";
        JsonObject out = JsonParser.parseString(EditorResultJson.localizeSnapshot(snap, TR)).getAsJsonObject();
        var advs = out.getAsJsonObject("refs").getAsJsonArray("advancements");
        assertEquals("다이아몬드!", advs.get(0).getAsJsonObject().get("title").getAsString());
        assertEquals("Plain title", advs.get(1).getAsJsonObject().get("title").getAsString());

        String result = "{\"saved\":true,\"errors\":[],\"snapshot\":" + snap + ",\"warnings\":[]}";
        String localized = EditorResultJson.localizeResult(result, TR);
        assertTrue(EditorResultJson.snapshotOf(localized).orElseThrow().contains("다이아몬드!"));
        assertTrue(JsonParser.parseString(localized).getAsJsonObject().get("saved").getAsBoolean());
    }

    @Test
    void localizeLeavesOtherInputUnchanged() {
        assertEquals("not json {", EditorResultJson.localizeSnapshot("not json {", TR));
        assertEquals("{\"shops\":{}}", EditorResultJson.localizeSnapshot("{\"shops\":{}}", TR));
        String failed = EditorResultJson.failure("x");
        assertEquals(failed, EditorResultJson.localizeResult(failed, TR));
    }
}
