package org.gettserver.gettcore.editor;

import static org.gettserver.gettcore.editor.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import org.gettserver.gettcore.data.ShopDefinition;
import org.junit.jupiter.api.Test;

class EditorMergeTest {
    @Test
    void keepCurrentPricesWhenBaseUnchanged() {
        ShopDefinition existing = shop("s", entry("minecraft:a", 10, 5).withCurrentPrices(14, 7));
        ShopDefinition incoming = shop("s", entry("minecraft:a", 10, 5));
        var e = EditorMerge.keepCurrentPrices(incoming, existing).entries().get(0);
        assertEquals(14, e.currentBuyPrice());
        assertEquals(7, e.currentSellPrice());
    }

    @Test
    void resetCurrentWhenBaseChanged() {
        ShopDefinition existing = shop("s", entry("minecraft:a", 10, 5).withCurrentPrices(14, 7));
        ShopDefinition incoming = shop("s", entry("minecraft:a", 20, 5), entry("minecraft:new", 3, 1));
        var es = EditorMerge.keepCurrentPrices(incoming, existing).entries();
        assertEquals(20, es.get(0).currentBuyPrice());
        assertEquals(5, es.get(0).currentSellPrice());
        assertEquals(3, es.get(1).currentBuyPrice());
        assertEquals(incoming, EditorMerge.keepCurrentPrices(incoming, null));
    }
}
