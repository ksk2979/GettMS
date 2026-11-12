package org.gettserver.gettcore.skill;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class BossConfigTest {
    private static final ResourceLocation GIRAFFE = ResourceLocation.parse("gettcore:boss_giraffe");

    private static SkillSpec skill(String id, String type) {
        return new SkillSpec(id, type, true, false, "", 10, 1, 0, 20, 1, 10,
                new SkillSpec.Shape(10, 2, 6), new SkillSpec.Aftershock(0, 0, 0), new SkillSpec.Terrain(false, 0, 0));
    }

    private static BossConfig.Boss bossWith(List<SkillSpec> skills) {
        BossConfig.Boss d = BossConfig.Boss.DEFAULT;
        return new BossConfig.Boss(d.aggro(), d.searchRange(), d.releaseRange(), d.reaggroRange(), d.calmSeconds(),
                d.speed(), d.armor(), d.preferredDistance(), d.shoutRange(), d.enrageBelow(), d.enrageCooldownMult(),
                skills);
    }

    @Test
    void defaultHasGiraffeAfterAggroWithTwoSkills() {
        BossConfig.Boss g = BossConfig.DEFAULT.boss(GIRAFFE);
        assertEquals(BossConfig.Aggro.AFTER, g.aggro());
        assertEquals(List.of("ramgak", "stomp"), g.skills().stream().map(SkillSpec::id).toList());
        SkillSpec ramgak = g.skills().get(0);
        assertEquals(14, ramgak.damage());
        assertEquals(0, g.preferredDistance(), "chases into melee");
        assertEquals(0, ramgak.minRange());
        assertEquals(32, ramgak.maxRange());
        assertEquals(10, ramgak.cooldownSeconds());
        assertEquals(new SkillSpec.Terrain(true, 0, 2), ramgak.terrain(), "pierces, no crater");
        assertEquals(20, ramgak.chargeTicks());
        SkillSpec stomp = g.skills().get(1);
        assertEquals(6, stomp.cooldownSeconds());
        assertEquals(new SkillSpec.Terrain(true, 1, 6), stomp.terrain());
    }

    @Test
    void missingBossFallsBackToDefaultEntry() {
        assertEquals(BossConfig.DEFAULT.boss(GIRAFFE), new BossConfig(Map.of()).boss(GIRAFFE));
    }

    @Test
    void unknownEntityGetsFirstNoSkills() {
        BossConfig.Boss b = BossConfig.DEFAULT.boss(ResourceLocation.parse("minecraft:zombie"));
        assertEquals(BossConfig.Aggro.FIRST, b.aggro());
        assertTrue(b.skills().isEmpty());
    }

    @Test
    void codecRoundTripsDefault() {
        var json = BossConfig.CODEC.encodeStart(JsonOps.INSTANCE, BossConfig.DEFAULT).getOrThrow();
        assertEquals(BossConfig.DEFAULT, BossConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
    }

    @Test
    void emptyObjectDecodesToEmptyMap() {
        BossConfig c = BossConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}")).getOrThrow();
        assertTrue(c.bosses().isEmpty());
    }

    @Test
    void unknownSkillTypeIsDroppedWithWarning() {
        BossConfig c = new BossConfig(Map.of("gettcore:boss_giraffe",
                bossWith(List.of(skill("a", "laser"), skill("b", SkillSpec.STOMP)))));
        List<String> warnings = new ArrayList<>();
        BossConfig s = c.sanitized(warnings);
        assertEquals(List.of("b"), s.boss(GIRAFFE).skills().stream().map(SkillSpec::id).toList());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("unknown type laser"), warnings.get(0));
    }

    @Test
    void numbersAreClamped() {
        SkillSpec bad = new SkillSpec("a", SkillSpec.STOMP, true, false, "", Double.NaN, 1, 0, 20, 0, 10,
                new SkillSpec.Shape(0, 0, 6), new SkillSpec.Aftershock(0, 0, 0), new SkillSpec.Terrain(false, 0, 0));
        BossConfig.Boss b = bossWith(List.of(bad));
        b = new BossConfig.Boss(b.aggro(), b.searchRange(), b.releaseRange(), b.reaggroRange(), -5, b.speed(),
                b.armor(), b.preferredDistance(), b.shoutRange(), b.enrageBelow(), b.enrageCooldownMult(), b.skills());
        List<String> warnings = new ArrayList<>();
        BossConfig.Boss out = new BossConfig(Map.of("gettcore:boss_giraffe", b)).sanitized(warnings).boss(GIRAFFE);
        assertEquals(0.2, out.skills().get(0).chargeSeconds());
        assertEquals(0, out.skills().get(0).damage());
        assertEquals(0, out.calmSeconds());
        assertEquals(3, warnings.size(), warnings.toString());
    }

    @Test
    void duplicateSkillIdsKeepFirst() {
        SkillSpec first = skill("a", SkillSpec.STOMP);
        SkillSpec second = new SkillSpec("a", SkillSpec.WIND_LINE, true, false, "", 99, 1, 0, 20, 1, 10,
                first.shape(), first.aftershock(), first.terrain());
        List<String> warnings = new ArrayList<>();
        BossConfig s = new BossConfig(Map.of("gettcore:boss_giraffe", bossWith(List.of(first, second))))
                .sanitized(warnings);
        assertEquals(List.of(first), s.boss(GIRAFFE).skills());
        assertFalse(warnings.isEmpty());
    }

    @Test
    void bundledExampleEqualsDefault() throws IOException {
        // The test working directory is build/minecraft-junit, so walk up to the project dir.
        Path file = Path.of("examples/config/gettcore/bosses.json");
        for (Path dir = Path.of("").toAbsolutePath(); !Files.exists(file) && dir != null; dir = dir.getParent()) {
            file = dir.resolve("examples/config/gettcore/bosses.json");
        }
        String text = Files.readString(file);
        BossConfig c = BossConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(text)).getOrThrow();
        List<String> warnings = new ArrayList<>();
        assertEquals(BossConfig.DEFAULT, c.sanitized(warnings));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }
}
