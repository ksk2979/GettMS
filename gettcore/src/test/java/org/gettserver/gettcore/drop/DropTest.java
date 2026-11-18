package org.gettserver.gettcore.drop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.gettserver.gettcore.hunting.HuntingConfig;
import org.gettserver.gettcore.mobs.MobStatsConfig;
import org.junit.jupiter.api.Test;

/** 처치 드롭: 확률·개수 굴림, 설정 파일 형식(mobs.json 의 drops, 사냥터 변종의 drops), 값 다듬기. */
class DropTest {
    static final ResourceLocation DIAMOND = ResourceLocation.withDefaultNamespace("diamond");

    @Test
    void codecDefaults() {
        Drop d = Drop.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"item\":\"minecraft:diamond\"}")).getOrThrow();
        assertEquals(new Drop(DIAMOND, 100, 1, 1), d);
        Drop ranged = Drop.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"item\":\"preset:fire_scroll\",\"chance\":2.5,\"min\":2,\"max\":5}")).getOrThrow();
        assertEquals(new Drop(ResourceLocation.parse("preset:fire_scroll"), 2.5, 2, 5), ranged);
        // max missing = min
        assertEquals(3, Drop.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"item\":\"minecraft:diamond\",\"min\":3}")).getOrThrow().max());
    }

    @Test
    void rollRespectsChanceAndRange() {
        RandomSource r = RandomSource.create(42);
        assertEquals(0, new Drop(DIAMOND, 0, 1, 1).roll(r));
        for (int i = 0; i < 200; i++) {
            assertEquals(4, new Drop(DIAMOND, 100, 4, 4).roll(r));
            int n = new Drop(DIAMOND, 100, 2, 5).roll(r);
            assertTrue(n >= 2 && n <= 5, "count " + n);
        }
        int hits = 0;
        for (int i = 0; i < 4000; i++) if (new Drop(DIAMOND, 25, 1, 1).roll(r) > 0) hits++;
        assertTrue(hits > 850 && hits < 1150, "25% of 4000 rolled " + hits);
    }

    @Test
    void rollGivesStacksSplitByMaxStackSizeAndSkipsUnknownItems() {
        List<ItemStack> got = DropEvents.roll(List.of(new Drop(DIAMOND, 100, 150, 150),
                new Drop(ResourceLocation.parse("nomod:nothing"), 100, 1, 1),
                new Drop(ResourceLocation.withDefaultNamespace("iron_sword"), 100, 2, 2)), RandomSource.create(1));
        assertEquals(List.of(64, 64, 22, 1, 1), got.stream().map(ItemStack::getCount).toList());
        assertEquals(Items.DIAMOND, got.get(0).getItem());
        assertEquals(Items.IRON_SWORD, got.get(4).getItem());
    }

    @Test
    void sanitizeClampsValuesAndCutsLongLists() {
        List<String> warnings = new ArrayList<>();
        List<Drop> fixed = Drop.sanitizedList("x", List.of(new Drop(DIAMOND, 250, 0, 9999), new Drop(DIAMOND, 10, 5, 2)), warnings);
        assertEquals(List.of(new Drop(DIAMOND, 100, 1, Drop.MAX_COUNT), new Drop(DIAMOND, 10, 5, 5)), fixed);
        assertEquals(3, warnings.size(), warnings.toString());

        List<Drop> ok = List.of(new Drop(DIAMOND, 10, 1, 2));
        assertSame(ok, Drop.sanitizedList("x", ok, new ArrayList<>()));

        List<Drop> many = new ArrayList<>();
        for (int i = 0; i < Drop.MAX_DROPS + 3; i++) many.add(new Drop(DIAMOND, 1, 1, 1));
        assertEquals(Drop.MAX_DROPS, Drop.sanitizedList("x", many, new ArrayList<>()).size());
    }

    @Test
    void mobsJsonHoldsDropsPerEntityType() {
        MobStatsConfig c = MobStatsConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"overrides\":{\"minecraft:zombie\":{\"health\":40}},"
                        + "\"drops\":{\"gettcore:boss_bear\":[{\"item\":\"preset:fire_scroll\",\"chance\":5}],\"bad id\":[{\"item\":\"minecraft:diamond\"}]}}"))
                .getOrThrow();
        List<String> warnings = new ArrayList<>();
        MobStatsConfig s = c.sanitized(warnings);
        assertEquals(1, warnings.size(), warnings.toString());
        assertEquals(List.of(new Drop(ResourceLocation.parse("preset:fire_scroll"), 5, 1, 1)),
                s.dropsOf(ResourceLocation.parse("gettcore:boss_bear")));
        assertTrue(s.dropsOf(ResourceLocation.parse("minecraft:zombie")).isEmpty());
        assertEquals(40, s.get(ResourceLocation.parse("minecraft:zombie")).orElseThrow().health().orElseThrow());
        // round trip, and old files without "drops" still read
        assertEquals(s, MobStatsConfig.CODEC.parse(JsonOps.INSTANCE,
                MobStatsConfig.CODEC.encodeStart(JsonOps.INSTANCE, s).getOrThrow()).getOrThrow());
        assertTrue(MobStatsConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"overrides\":{}}")).getOrThrow()
                .drops().isEmpty());
    }

    @Test
    void huntingVariantsCarryTheirOwnDrops() {
        HuntingConfig.Entry e = HuntingConfig.Entry.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"name\":\"큰 좀비\",\"entity\":\"minecraft:zombie\",\"drops\":[{\"item\":\"minecraft:diamond\",\"chance\":50,\"min\":1,\"max\":3}]}"))
                .getOrThrow();
        assertEquals(List.of(new Drop(DIAMOND, 50, 1, 3)), e.drops());
        assertEquals(e, HuntingConfig.Entry.CODEC.parse(JsonOps.INSTANCE,
                HuntingConfig.Entry.CODEC.encodeStart(JsonOps.INSTANCE, e).getOrThrow()).getOrThrow());
        // a variant written before drops existed has none
        assertTrue(HuntingConfig.Entry.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"name\":\"a\",\"entity\":\"minecraft:zombie\"}")).getOrThrow().drops().isEmpty());
    }
}
