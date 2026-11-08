package org.gettserver.gettcore.pet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** ko_kr / en_us 언어 파일 키 일치 및 코드에서 쓰는 번역 키 존재 확인. */
class PetLangTest {

    private static final Pattern LITERAL = Pattern.compile(
            "\"((?:gettcore|key\\.gettcore|entity\\.gettcore|item\\.gettcore|itemGroup\\.gettcore)[A-Za-z0-9_.]*)\"");

    private static final String[] ENTITY_PATHS = {
            "thrown_capture_ball",
            "pet_lion", "pet_tiger", "pet_bear", "pet_black_bear", "pet_hippo",
            "pet_elephant", "pet_mammoth", "pet_giraffe", "pet_ostrich", "pet_vulture",
            "boss_lion", "boss_tiger", "boss_bear", "boss_elephant", "boss_mammoth", "boss_vulture"};

    private static final String[] ITEM_PATHS = {
            "check", "xp_orb_item", "return_stone", "enchant_stone", "captured_mob",
            "capture_ball_iron", "capture_ball_gold", "capture_ball_diamond", "capture_ball_netherite"};

    private static Set<String> keys(String file) throws Exception {
        try (InputStream in = PetLangTest.class.getResourceAsStream("/assets/gettcore/lang/" + file)) {
            assertNotNull(in, file);
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            return new TreeSet<>(o.keySet());
        }
    }

    @Test
    void koAndEnHaveSameKeys() throws Exception {
        Set<String> ko = keys("ko_kr.json");
        Set<String> en = keys("en_us.json");
        Set<String> onlyKo = new TreeSet<>(ko);
        onlyKo.removeAll(en);
        Set<String> onlyEn = new TreeSet<>(en);
        onlyEn.removeAll(ko);
        assertTrue(onlyKo.isEmpty() && onlyEn.isEmpty(), "only in ko: " + onlyKo + " / only in en: " + onlyEn);
    }

    @Test
    void javaTranslationKeysExistInKo() throws Exception {
        Set<String> ko = keys("ko_kr.json");
        Path root = Path.of("src/main/java");
        if (!Files.exists(root)) root = Path.of("gettcore/src/main/java");
        // 테스트 작업 디렉터리가 build/minecraft-junit 일 수 있어 상위로 거슬러 올라가며 찾는다.
        for (Path dir = Path.of("").toAbsolutePath(); !Files.exists(root) && dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve("src/main/java"))) root = dir.resolve("src/main/java");
        }
        assertTrue(Files.exists(root), "source root not found from " + Path.of("").toAbsolutePath());

        List<String> complete = new ArrayList<>();
        Set<String> prefixes = new TreeSet<>();
        int scanned = 0;
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) {
            files = s.filter(p -> p.toString().endsWith(".java")).toList();
        }
        for (Path f : files) {
            Matcher m = LITERAL.matcher(Files.readString(f, StandardCharsets.UTF_8));
            while (m.find()) {
                String k = m.group(1);
                scanned++;
                if (k.endsWith(".")) prefixes.add(k);
                else if (k.indexOf('.') > 0) complete.add(k);
            }
        }
        assertTrue(scanned > 0, "no literals scanned in " + files.size() + " files");

        List<String> missing = new ArrayList<>();
        for (String k : complete) if (!ko.contains(k)) missing.add(k);

        for (CaptureRules.Verdict v : CaptureRules.Verdict.values())
            need(ko, missing, "gettcore.msg.capture." + v.name().toLowerCase(Locale.ROOT));
        assertEquals(5, CaptureRules.Verdict.values().length);
        for (String k : new String[]{"fighter", "mount", "flying_mount"}) need(ko, missing, "gettcore.kind." + k);
        for (String k : new String[]{"success", "escaped", "duplicate", "unknown_species"})
            need(ko, missing, "gettcore.msg.obtain." + k);
        for (String p : ENTITY_PATHS) need(ko, missing, "entity.gettcore." + p);
        for (String p : ITEM_PATHS) need(ko, missing, "item.gettcore." + p);
        for (CaptureTier t : CaptureTier.values()) need(ko, missing, "gettcore.tier." + t.key());
        // Non-base coats of the whitelisted Naturalist species (only the tiger has any in Naturalist 2.0.7).
        for (String v : new String[]{"white_tiger", "leopard", "black_panther"}) need(ko, missing, "gettcore.variant." + v);

        System.out.println("PetLangTest: scanned " + scanned + " literals in " + files.size()
                + " files (" + complete.size() + " complete keys, prefixes " + prefixes + ")");
        assertTrue(missing.isEmpty(), "missing in ko_kr.json (scanned " + scanned + " literals): " + missing);
    }

    private static void need(Set<String> ko, List<String> missing, String key) {
        if (!ko.contains(key)) missing.add(key);
    }
}
