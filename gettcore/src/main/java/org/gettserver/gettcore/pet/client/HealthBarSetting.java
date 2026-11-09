package org.gettserver.gettcore.pet.client;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * 몬스터 머리 위 체력바를 보일지: {@code config/gettcore-client.properties} 의 {@code mobHealthBar} = auto(기본)|on|off.
 * auto 는 Epic Fight 가 있으면 끈다(Epic Fight 가 자체 체력바를 띄움) — 모드를 빼면 저절로 다시 켜진다. 순수 판정은 여기,
 * 클라이언트 클래스를 끌어오지 않는다.
 */
public final class HealthBarSetting {
    public static final String KEY = "mobHealthBar";

    private HealthBarSetting() {}

    public static boolean show(String setting, boolean epicFightLoaded) {
        String s = setting == null ? "auto" : setting.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "on" -> true;
            case "off" -> false;
            default -> !epicFightLoaded;
        };
    }

    /** 설정 파일에서 값 읽기(없거나 못 읽으면 null = auto). */
    public static String read(Path file) {
        if (!Files.isRegularFile(file)) return null;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        return p.getProperty(KEY);
    }
}
