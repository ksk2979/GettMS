package org.gettserver.gettcore.shop;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.data.EconomyConfig;
import org.gettserver.gettcore.data.PriceFluctuationConfig;
import org.gettserver.gettcore.data.ShopDefinition;
import org.gettserver.gettcore.data.ShopEntry;
import org.junit.jupiter.api.Test;

/** 시세 변동: 주기마다 기준가의 70~130% 안에서 10 단위로, 같은 주기에는 같은 값. */
class PriceFluctuationTest {
    static final PriceFluctuationConfig CFG = PriceFluctuationConfig.DEFAULT;

    static ShopEntry entry(String item, long buy, long sell, boolean canBuy) {
        return new ShopEntry(ResourceLocation.parse(item), "t", buy, sell, canBuy, true, buy, sell);
    }

    @Test
    void defaultsAreThirtyMinutesSeventyToOneThirty() {
        assertTrue(CFG.enabled());
        assertEquals(30, CFG.intervalMinutes());
        assertEquals(70.0, CFG.minPercent());
        assertEquals(130.0, CFG.maxPercent());
        assertEquals(10, CFG.roundTo());
    }

    @Test
    void periodFlipsOnTheClock() {
        long halfHour = 30 * 60_000L;
        assertEquals(0, PriceFluctuation.period(0, CFG));
        assertEquals(0, PriceFluctuation.period(halfHour - 1, CFG));
        assertEquals(1, PriceFluctuation.period(halfHour, CFG));
        assertEquals(halfHour, PriceFluctuation.millisLeft(0, CFG));
        assertEquals(1, PriceFluctuation.millisLeft(halfHour - 1, CFG));
    }

    @Test
    void pricesStayInRangeInTensAndCoverTheWholeRange() {
        ShopEntry cabbage = entry("farmersdelight:cabbage", 0, 100, false);
        Set<Long> seen = new HashSet<>();
        for (long period = 0; period < 2000; period++) {
            long p = PriceFluctuation.sellPrice(cabbage, 42L, "farmer", period, CFG);
            assertTrue(p >= 70 && p <= 130, "out of range: " + p);
            assertEquals(0, p % 10, "not a multiple of 10: " + p);
            seen.add(p);
        }
        assertEquals(Set.of(70L, 80L, 90L, 100L, 110L, 120L, 130L), seen);
    }

    @Test
    void sameInputsGiveTheSamePriceAndItemsMoveSeparately() {
        ShopEntry a = entry("minecraft:cod", 0, 1000, false);
        ShopEntry b = entry("minecraft:salmon", 0, 1000, false);
        assertEquals(PriceFluctuation.sellPrice(a, 7L, "fisher", 123, CFG), PriceFluctuation.sellPrice(a, 7L, "fisher", 123, CFG));
        int differs = 0;
        int changes = 0;
        for (long period = 0; period < 200; period++) {
            if (PriceFluctuation.sellPrice(a, 7L, "fisher", period, CFG) != PriceFluctuation.sellPrice(b, 7L, "fisher", period, CFG)) differs++;
            if (PriceFluctuation.sellPrice(a, 7L, "fisher", period, CFG) != PriceFluctuation.sellPrice(a, 7L, "fisher", period + 1, CFG)) changes++;
        }
        assertTrue(differs > 150, "two items should rarely share a price: " + differs);
        assertTrue(changes > 150, "a price should change nearly every period: " + changes);
    }

    @Test
    void neverBelowTenAndTinyOrZeroPricesStayFixed() {
        for (long period = 0; period < 300; period++) {
            assertEquals(10, PriceFluctuation.sellPrice(entry("a:b", 0, 10, false), 1L, "s", period, CFG), "10 has no room to move");
            long p = PriceFluctuation.sellPrice(entry("a:c", 0, 14, false), 1L, "s", period, CFG);
            assertTrue(p == 10, "14 → only 10 fits in 9.8..18.2: " + p);
            assertEquals(5, PriceFluctuation.sellPrice(entry("a:d", 0, 5, false), 1L, "s", period, CFG));
            assertEquals(0, PriceFluctuation.sellPrice(entry("a:e", 0, 0, false), 1L, "s", period, CFG));
        }
    }

    @Test
    void sellPriceNeverExceedsTheBuyPriceOfABuyableItem() {
        ShopEntry seed = entry("minecraft:wheat_seeds", 105, 100, true);
        boolean capped = false;
        for (long period = 0; period < 500; period++) {
            long p = PriceFluctuation.sellPrice(seed, 3L, "farmer", period, CFG);
            assertTrue(p <= 105, "arbitrage: sell " + p + " > buy 105");
            assertEquals(0, p % 10);
            if (p == 100) capped = true;
        }
        assertTrue(capped);
        // Not buyable here: the buy price does not cap anything.
        ShopEntry sellOnly = entry("minecraft:wheat", 105, 100, false);
        boolean above = false;
        for (long period = 0; period < 500; period++) above |= PriceFluctuation.sellPrice(sellOnly, 3L, "farmer", period, CFG) > 105;
        assertTrue(above);
    }

    @Test
    void configIsSanitizedAndOnlyMarkedShopsFluctuate() {
        PriceFluctuationConfig odd = new PriceFluctuationConfig(true, 0, 150, 50, 0, true).sanitized();
        assertEquals(1, odd.intervalMinutes());
        assertEquals(50.0, odd.minPercent());
        assertEquals(150.0, odd.maxPercent());
        assertEquals(1, odd.roundTo());

        EconomyConfig eco = EconomyConfig.DEFAULT;
        ShopDefinition fixed = new ShopDefinition("armor", "방어구", List.of(), List.of());
        ShopDefinition market = new ShopDefinition("farmer", "농부", List.of(), List.of(), true);
        assertFalse(eco.fluctuates(fixed));
        assertTrue(eco.fluctuates(market));
        // The switch survives the entry helpers and the file format; old files without it mean "fixed".
        assertTrue(market.withEntry(entry("a:b", 0, 10, false)).withoutEntry(ResourceLocation.parse("a:b")).fluctuate());
        var json = ShopDefinition.CODEC.encodeStart(JsonOps.INSTANCE, market).getOrThrow();
        assertTrue(ShopDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().fluctuate());
        assertFalse(ShopDefinition.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"id\":\"x\",\"name\":\"X\"}")).getOrThrow().fluctuate());
        assertTrue(EconomyConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}")).getOrThrow()
                .priceFluctuation().enabled());
    }
}
