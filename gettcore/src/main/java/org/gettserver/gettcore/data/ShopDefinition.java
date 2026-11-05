package org.gettserver.gettcore.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public record ShopDefinition(String id, String name, List<ShopTab> tabs, List<ShopEntry> entries) {
    public static final Codec<ShopDefinition> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(ShopDefinition::id),
            Codec.STRING.fieldOf("name").forGetter(ShopDefinition::name),
            ShopTab.CODEC.listOf().optionalFieldOf("tabs", List.of()).forGetter(ShopDefinition::tabs),
            ShopEntry.CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(ShopDefinition::entries)
    ).apply(i, ShopDefinition::new));

    /** Adds the entry, replacing any existing entry for the same item. */
    public ShopDefinition withEntry(ShopEntry entry) {
        List<ShopEntry> list = new ArrayList<>(entries);
        boolean replaced = false;
        for (int n = 0; n < list.size(); n++) {
            if (list.get(n).item().equals(entry.item())) {
                list.set(n, entry);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(entry);
        return new ShopDefinition(id, name, tabs, List.copyOf(list));
    }

    public ShopDefinition withoutEntry(ResourceLocation item) {
        return new ShopDefinition(id, name, tabs, entries.stream().filter(e -> !e.item().equals(item)).toList());
    }

    public ShopDefinition withCurrentPrices(ResourceLocation item, long currentBuy, long currentSell) {
        return new ShopDefinition(id, name, tabs, entries.stream()
                .map(e -> e.item().equals(item) ? e.withCurrentPrices(currentBuy, currentSell) : e).toList());
    }

    public ShopDefinition withPrices(ResourceLocation item, long buy, long sell) {
        return new ShopDefinition(id, name, tabs,
                entries.stream().map(e -> e.item().equals(item) ? e.withPrices(buy, sell) : e).toList());
    }
}
