package org.gettserver.gettcore.preset;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.gettserver.gettcore.data.RegistryChecks;
import org.gettserver.gettcore.shop.TradeCalculator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 아이템 프리셋: 구성 요소까지 저장한 아이템을 preset:<이름> 으로 쓴다 (상점·보상·드롭). */
class ItemPresetsTest {
    @TempDir Path dir;
    static final RegistryAccess REG = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    static final ResourceLocation SCROLL = ItemPresets.idOf("fire_scroll");

    static ItemStack named(String name) {
        ItemStack s = new ItemStack(Items.PAPER);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return s;
    }

    @AfterEach
    void clear() {
        ItemPresets.setAll(java.util.Map.of());
    }

    @Test
    void namesMustBePlainResourcePaths() {
        assertTrue(ItemPresets.validName("fireball_3"));
        assertFalse(ItemPresets.validName("화염구"));
        assertFalse(ItemPresets.validName("a/b"));
        assertFalse(ItemPresets.validName("Fire"));
        assertFalse(ItemPresets.validName(""));
        assertTrue(ItemPresets.isPreset(SCROLL));
        assertFalse(ItemPresets.isPreset(ResourceLocation.withDefaultNamespace("paper")));
    }

    @Test
    void savedPresetSurvivesTheFileAndKeepsItsComponents() throws Exception {
        ItemStack held = named("화염구 스크롤");
        held.setCount(7);
        ItemPresets.put(dir, REG, "fire_scroll", held);
        assertTrue(Files.readString(dir.resolve(ItemPresets.FILE), StandardCharsets.UTF_8).contains("fire_scroll"));

        ItemPresets.setAll(java.util.Map.of());
        assertFalse(ItemPresets.exists(SCROLL));
        assertTrue(ItemPresets.load(dir, REG).isEmpty());
        assertTrue(ItemPresets.exists(SCROLL));
        ItemStack got = ItemPresets.stack(SCROLL, 3);
        assertEquals(3, got.getCount());
        assertTrue(ItemStack.isSameItemSameComponents(got, named("화염구 스크롤")));
        assertEquals("화염구 스크롤", ItemPresets.name(SCROLL));

        assertTrue(ItemPresets.remove(dir, REG, "fire_scroll"));
        assertFalse(ItemPresets.remove(dir, REG, "fire_scroll"));
        assertTrue(ItemPresets.stack(SCROLL, 1).isEmpty());
        assertTrue(ItemPresets.load(dir, REG).isEmpty());
        assertFalse(ItemPresets.exists(SCROLL));
    }

    @Test
    void plainIdsStillGiveAPlainStackAndUnknownIdsNothing() {
        ItemStack carrot = ItemPresets.stack(ResourceLocation.withDefaultNamespace("carrot"), 5);
        assertEquals(Items.CARROT, carrot.getItem());
        assertEquals(5, carrot.getCount());
        assertTrue(ItemPresets.stack(ResourceLocation.parse("nomod:nothing"), 1).isEmpty());
        assertTrue(ItemPresets.stack(ItemPresets.idOf("nope"), 1).isEmpty());
    }

    @Test
    void badEntriesAreSkippedWithAnError() throws Exception {
        Files.writeString(dir.resolve(ItemPresets.FILE),
                "{\"presets\":{\"ok\":{\"id\":\"minecraft:paper\",\"count\":1},\"Bad Name\":{\"id\":\"minecraft:paper\",\"count\":1},"
                        + "\"gone\":{\"id\":\"nomod:nothing\",\"count\":1}}}");
        List<String> errors = ItemPresets.load(dir, REG);
        assertEquals(List.of("ok"), ItemPresets.names());
        assertEquals(2, errors.size(), errors.toString());
    }

    @Test
    void shopTreatsAPresetAsItsOwnItem() throws Exception {
        ItemPresets.put(dir, REG, "fire_scroll", named("화염구 스크롤"));
        // selling: only stacks with exactly the preset's components count
        assertTrue(TradeCalculator.sellable(named("화염구 스크롤"), SCROLL));
        assertFalse(TradeCalculator.sellable(named("얼음 스크롤"), SCROLL));
        assertFalse(TradeCalculator.sellable(new ItemStack(Items.PAPER), SCROLL));
        // a plain paper entry does not swallow the scrolls either
        assertFalse(TradeCalculator.sellable(named("화염구 스크롤"), ResourceLocation.withDefaultNamespace("paper")));
        List<ItemStack> inv = new ArrayList<>(List.of(named("화염구 스크롤"), new ItemStack(Items.PAPER, 10), ItemStack.EMPTY));
        inv.get(0).setCount(60);
        assertEquals(60, TradeCalculator.countSellable(inv, SCROLL));

        // buying: stacks onto the same preset, then into empty slots — never onto plain paper
        ItemStack proto = ItemPresets.stack(SCROLL, 1);
        assertEquals(4 + 64, TradeCalculator.capacityFor(inv, proto, 64));
        assertEquals(10, TradeCalculator.distribute(inv, proto, 64, 10));
        assertEquals(64, inv.get(0).getCount());
        assertEquals(10, inv.get(1).getCount());
        assertEquals(6, inv.get(2).getCount());
        assertTrue(ItemStack.isSameItemSameComponents(inv.get(2), proto));
    }

    @Test
    void registryChecksCountExistingPresetsAsItems() throws Exception {
        RegistryChecks live = RegistryChecks.live();
        assertFalse(live.itemExists().test(SCROLL));
        ItemPresets.put(dir, REG, "fire_scroll", named("x"));
        assertTrue(live.itemExists().test(SCROLL));
        assertTrue(live.itemExists().test(ResourceLocation.withDefaultNamespace("carrot")));
    }
}
