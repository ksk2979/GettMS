package org.gettserver.gettcore.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import org.gettserver.gettcore.upgrade.UpgradeConfig.Step;
import org.gettserver.gettcore.upgrade.UpgradeConfig.TierCost;
import org.junit.jupiter.api.Test;

/** 배낭 등급 비용: 아이템 id 는 그대로, 등급(0~4)마다 비용이 하나. */
class BackpackTierTest {
    private static UpgradeConfig parse(String json) {
        return UpgradeConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    @Test
    void aFileWithoutTheSectionUsesTheAgreedCosts() {
        UpgradeConfig c = parse("{\"steps\":[]}");
        assertEquals(List.of(new TierCost("minecraft:iron_ingot", 20, 0), new TierCost("minecraft:gold_ingot", 20, 0),
                new TierCost("minecraft:diamond", 10, 0), new TierCost("minecraft:netherite_ingot", 4, 0)),
                c.backpackTiers());
        assertEquals(c, UpgradeConfig.fromJsonString(UpgradeConfig.toJsonString(c)));
        assertTrue(c.errors().isEmpty());
    }

    @Test
    void eachTierHasItsOwnCostAndTheLastTierHasNone() {
        UpgradeConfig c = UpgradeConfig.DEFAULT;
        Step first = c.backpackStep("travelersbackpack:standard", 0).orElseThrow();
        assertEquals(new Step("travelersbackpack:standard", "travelersbackpack:standard", "minecraft:iron_ingot", 20, 0), first);
        assertTrue(UpgradeConfig.isBackpackStep(first));
        assertEquals("minecraft:netherite_ingot", c.backpackStep("travelersbackpack:bat", 3).orElseThrow().material());
        assertEquals(4, c.backpackStep("travelersbackpack:bat", 3).orElseThrow().count());
        assertTrue(c.backpackStep("travelersbackpack:bat", 4).isEmpty(), "netherite is the last tier");
        assertTrue(c.backpackStep("travelersbackpack:bat", -1).isEmpty());
        assertFalse(UpgradeConfig.isBackpackStep(new Step("a:wood", "a:stone", "minecraft:iron_ingot", 3, 0)));
    }

    @Test
    void anEmptyListSwitchesItOffAndEditedCostsSurvive() {
        assertTrue(parse("{\"backpackTiers\":[]}").backpackStep("travelersbackpack:standard", 0).isEmpty());
        UpgradeConfig c = parse("{\"backpackTiers\":[{\"material\":\"minecraft:emerald\",\"count\":7,\"gold\":500}]}");
        assertEquals(new TierCost("minecraft:emerald", 7, 500), c.backpackTiers().get(0));
        assertTrue(c.backpackStep("travelersbackpack:standard", 1).isEmpty(), "only one tier was given");
        assertTrue(c.itemIds().contains("minecraft:emerald"));
    }

    @Test
    void badEntriesAreReportedAndCleanedUp() {
        UpgradeConfig c = new UpgradeConfig(List.of(), List.of(new TierCost("minecraft:iron_ingot", 0, -5),
                new TierCost("not an id", 1, 0), new TierCost("minecraft:diamond", 1, 0)));
        assertEquals(3, c.errors().size());
        List<String> warnings = new ArrayList<>();
        UpgradeConfig clean = c.sanitized(warnings);
        assertEquals(List.of(new TierCost("minecraft:iron_ingot", 1, 0)), clean.backpackTiers());
        assertEquals(3, warnings.size());

        List<TierCost> five = new ArrayList<>(UpgradeConfig.BACKPACK_DEFAULT);
        five.add(new TierCost("minecraft:emerald", 1, 0));
        assertEquals(UpgradeConfig.BACKPACK_DEFAULT, new UpgradeConfig(List.of(), five).sanitized(new ArrayList<>()).backpackTiers());
    }
}
