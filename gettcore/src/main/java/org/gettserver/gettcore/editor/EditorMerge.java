package org.gettserver.gettcore.editor;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.data.ShopDefinition;
import org.gettserver.gettcore.data.ShopEntry;

public final class EditorMerge {
    private EditorMerge() {}

    /**
     * Entries whose base prices are unchanged keep the server's current (market) prices; entries with changed or
     * new base prices start at the base price.
     */
    public static ShopDefinition keepCurrentPrices(ShopDefinition incoming, ShopDefinition existing) {
        Map<ResourceLocation, ShopEntry> old = new HashMap<>();
        if (existing != null) for (ShopEntry e : existing.entries()) old.putIfAbsent(e.item(), e);
        return new ShopDefinition(incoming.id(), incoming.name(), incoming.tabs(), incoming.entries().stream().map(e -> {
            ShopEntry prev = old.get(e.item());
            if (prev != null && prev.buyPrice() == e.buyPrice() && prev.sellPrice() == e.sellPrice()) {
                return e.withCurrentPrices(prev.currentBuyPrice(), prev.currentSellPrice());
            }
            return e.withCurrentPrices(e.buyPrice(), e.sellPrice());
        }).toList());
    }
}
