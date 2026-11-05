package org.gettserver.gettcore.shop;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.network.TradePayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.gettserver.gettcore.data.ShopDefinition;
import org.gettserver.gettcore.data.ShopEntry;
import org.gettserver.gettcore.data.ShopTab;
import org.gettserver.gettcore.economy.ClientBalance;
import org.gettserver.gettcore.economy.EconomyHud;

public class ShopScreen extends AbstractContainerScreen<ShopMenu> {
    private static final int PANEL = 0xFFF4E8CC;
    private static final int BORDER = 0xFF4A3B24;
    private static final int TAB_Y = 6;
    private static final int TAB_W = 24;
    private static final int TAB_H = 22;
    private static final int LIST_X = 8;
    private static final int LIST_Y = 32;
    private static final int LIST_W = 160;
    private static final int ROW_H = 24;
    private static final int VISIBLE_ROWS = 6;
    private static final int BTN_W = 44;
    private static final int BTN_H = 16;

    private record Row(ShopEntry entry, int owned) {}

    private int selectedTab;
    private int scroll;
    private QuantityPopup popup;

    public ShopScreen(ShopMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 360;
        this.imageHeight = 200;
    }

    @Override
    protected void init() {
        super.init();
        if (menu.mode() == ShopMode.SELL) {
            addRenderableWidget(Button.builder(Component.translatable("gettcore.gui.sell_all"),
                    b -> PacketDistributor.sendToServer(new TradePayload(menu.shopId(),
                            ResourceLocation.withDefaultNamespace("air"), 1, ShopMode.SELL, true))).bounds(leftPos + LIST_X, topPos + 176, LIST_W, 18).build());
        }
    }

    private ShopDefinition definition() {
        return ClientShopCache.get(menu.shopId());
    }

    private List<Row> rows() {
        ShopDefinition def = definition();
        List<Row> out = new ArrayList<>();
        if (def == null) return out;
        String tabId = def.tabs().isEmpty() ? null : def.tabs().get(Math.min(selectedTab, def.tabs().size() - 1)).id();
        for (ShopEntry e : def.entries()) {
            if (tabId != null && !tabId.equals(e.tab())) continue;
            if (menu.mode() == ShopMode.BUY) {
                if (e.canBuy()) out.add(new Row(e, 0));
            } else if (e.canSell()) {
                int owned = TradeCalculator.countSellable(minecraft.player.getInventory().items, e.item());
                if (owned > 0) out.add(new Row(e, owned));
            }
        }
        return out;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, BORDER);
        g.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, PANEL);
        for (var slot : menu.slots) {
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            g.fill(x - 1, y - 1, x + 17, y + 17, 0xFF8B7A5A);
            g.fill(x, y, x + 16, y + 16, 0xFFC9BA94);
        }
        ShopDefinition def = definition();
        if (def != null) {
            for (int i = 0; i < def.tabs().size(); i++) {
                ShopTab tab = def.tabs().get(i);
                int x = leftPos + LIST_X + i * TAB_W;
                int y = topPos + TAB_Y;
                g.fill(x, y, x + TAB_W - 2, y + TAB_H, i == selectedTab ? 0xFFFFF6DC : 0xFFD9C9A0);
                g.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(tab.icon())), x + 3, y + 3);
            }
        }
        List<Row> rows = rows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - VISIBLE_ROWS)));
        for (int i = 0; i < VISIBLE_ROWS && scroll + i < rows.size(); i++) {
            Row row = rows.get(scroll + i);
            int x = leftPos + LIST_X;
            int y = topPos + LIST_Y + i * ROW_H;
            g.fill(x, y, x + LIST_W, y + ROW_H - 2, 0xFFE8DAB4);
            g.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(row.entry().item())), x + 4, y + 3);
            int bx = x + LIST_W - BTN_W - 4;
            int by = y + 3;
            g.fill(bx, by, bx + BTN_W, by + BTN_H, 0xFF8B6B3A);
            g.fill(bx + 1, by + 1, bx + BTN_W - 1, by + BTN_H - 1, 0xFFB98F4E);
            Component label = Component.translatable(
                    menu.mode() == ShopMode.BUY ? "gettcore.gui.buy" : "gettcore.gui.sell");
            g.drawCenteredString(font, label, bx + BTN_W / 2, by + 4, 0xFFFFFFFF);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Coordinates are relative to leftPos/topPos.
        ShopDefinition def = definition();
        String name = def != null ? def.name() : menu.shopId();
        Component title = Component.translatable(
                menu.mode() == ShopMode.BUY ? "gettcore.gui.shop_title_buy" : "gettcore.gui.shop_title_sell", name);
        g.drawString(font, title, 180, 8, 0xFF3B2F1C, false);
        String bal = EconomyHud.formatGold(ClientBalance.get());
        g.drawString(font, bal, imageWidth - 8 - font.width(bal), 22, 0xFF3B2F1C, false);
        List<Row> rows = rows();
        for (int i = 0; i < VISIBLE_ROWS && scroll + i < rows.size(); i++) {
            Row row = rows.get(scroll + i);
            int y = LIST_Y + i * ROW_H;
            String itemName = BuiltInRegistries.ITEM.get(row.entry().item()).getDescription().getString();
            g.drawString(font, font.plainSubstrByWidth(itemName, 90), LIST_X + 24, y + 3, 0xFF3B2F1C, false);
            long price = menu.mode() == ShopMode.BUY ? row.entry().currentBuyPrice() : row.entry().currentSellPrice();
            String sub = EconomyHud.formatGold(price) + (menu.mode() == ShopMode.SELL ? " x" + row.owned() : "");
            g.drawString(font, sub, LIST_X + 24, y + 13, 0xFF6B5A3A, false);
            if (ClientShopCache.dynamicsEnabled(menu.shopId())) {
                ShopEntry en = row.entry();
                boolean buyMode = menu.mode() == ShopMode.BUY;
                var d = PriceDynamics.delta(buyMode ? en.currentBuyPrice() : en.currentSellPrice(),
                        buyMode ? en.buyPrice() : en.sellPrice());
                if (d.isPresent()) {
                    int v = d.get();
                    String mark = (v > 0 ? "\u25B2" : "\u25BC") + String.format("%,d", Math.abs(v));
                    g.drawString(font, mark, LIST_X + 24 + font.width(sub) + 4, y + 13,
                            v > 0 ? 0xFF4CAF50 : 0xFFE53935, false);
                }
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (popup == null) renderTooltip(g, mouseX, mouseY);
        ShopDefinition def = definition();
        if (def != null && popup == null) {
            for (int i = 0; i < def.tabs().size(); i++) {
                int x = leftPos + LIST_X + i * TAB_W;
                int y = topPos + TAB_Y;
                if (mouseX >= x && mouseX < x + TAB_W - 2 && mouseY >= y && mouseY < y + TAB_H) {
                    g.renderTooltip(font, Component.literal(def.tabs().get(i).name()), mouseX, mouseY);
                }
            }
        }
        if (popup != null) popup.render(g, mouseX, mouseY, partialTick);
    }

    private void openPopup(ShopEntry e) {
        Item item = BuiltInRegistries.ITEM.get(e.item());
        var inv = minecraft.player.getInventory().items;
        boolean buy = menu.mode() == ShopMode.BUY;
        long price = buy ? e.currentBuyPrice() : e.currentSellPrice();
        int max = buy
                ? TradeCalculator.maxBuyable(ClientBalance.get(), price,
                        TradeCalculator.capacityFor(inv, item, item.getDefaultMaxStackSize()), 64 * 36)
                : Math.min(TradeCalculator.countSellable(inv, e.item()), 64 * 36);
        popup = new QuantityPopup(font, width, height, item, price, max,
                qty -> PacketDistributor.sendToServer(new TradePayload(menu.shopId(), e.item(), qty, menu.mode(), false)),
                () -> popup = null);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (popup != null) return popup.keyPressed(key, scan, mods);
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (popup != null) return popup.charTyped(c, mods);
        return super.charTyped(c, mods);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (popup != null) return popup.mouseClicked(mouseX, mouseY, button);
        ShopDefinition def = definition();
        if (def != null && button == 0) {
            for (int i = 0; i < def.tabs().size(); i++) {
                int x = leftPos + LIST_X + i * TAB_W;
                int y = topPos + TAB_Y;
                if (mouseX >= x && mouseX < x + TAB_W - 2 && mouseY >= y && mouseY < y + TAB_H) {
                    selectedTab = i;
                    scroll = 0;
                    return true;
                }
            }
        }
        if (button == 0) {
            List<Row> rows = rows();
            for (int i = 0; i < VISIBLE_ROWS && scroll + i < rows.size(); i++) {
                int bx = leftPos + LIST_X + LIST_W - BTN_W - 4;
                int by = topPos + LIST_Y + i * ROW_H + 3;
                if (mouseX >= bx && mouseX < bx + BTN_W && mouseY >= by && mouseY < by + BTN_H) {
                    openPopup(rows.get(scroll + i).entry());
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (popup != null) return true;
        if (mouseX >= leftPos + LIST_X && mouseX < leftPos + LIST_X + LIST_W
                && mouseY >= topPos + LIST_Y && mouseY < topPos + LIST_Y + VISIBLE_ROWS * ROW_H) {
            scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
