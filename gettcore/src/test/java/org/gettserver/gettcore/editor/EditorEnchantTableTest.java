package org.gettserver.gettcore.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;
import org.gettserver.gettcore.enchant.SpecialEnchants.Tool;
import org.junit.jupiter.api.Test;

/** The 마법부여 tab's hard-coded ability table (app.js ENCHANT_ABILITIES) against {@link Ability} and ko_kr.json. */
class EditorEnchantTableTest {
    /** One row: {@code ['harvest', '수확', 3, ['hoe']],} */
    private static final Pattern ROW = Pattern.compile("\\['([a-z_]+)', '([^']+)', (\\d), \\[([^\\]]*)\\]\\],");

    @Test
    void appJsAbilityTableMatchesEnum() throws Exception {
        String js = text("/gettcore_editor/app.js");
        int start = js.indexOf("const ENCHANT_ABILITIES = [");
        assertTrue(start >= 0, "ENCHANT_ABILITIES not found in app.js");
        String table = js.substring(start, js.indexOf("];", start));
        JsonObject lang = JsonParser.parseString(text("/assets/gettcore/lang/ko_kr.json")).getAsJsonObject();

        List<String> actual = new ArrayList<>();
        Matcher m = ROW.matcher(table);
        while (m.find()) {
            actual.add(m.group(1) + "|" + m.group(2) + "|" + m.group(3) + "|"
                    + m.group(4).replace("'", "").replace(" ", ""));
        }
        List<String> expected = new ArrayList<>();
        for (Ability a : Ability.values()) {
            List<String> tools = new ArrayList<>();
            for (Tool t : Tool.values()) if (a.fits(t)) tools.add(t.id());
            expected.add(a.id() + "|" + lang.get("enchantment.gettcore." + a.id()).getAsString() + "|" + a.maxLevel()
                    + "|" + String.join(",", tools));
        }
        assertEquals(expected, actual);
    }

    private static String text(String path) throws Exception {
        try (InputStream in = EditorEnchantTableTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
