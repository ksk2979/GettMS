package org.gettserver.gettcore.shop;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractWidget;
import org.gettserver.gettcore.network.ShopEditPayload;
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
    private static final int ARROW_W = (TabStrip.SLOTS - TabStrip.OVERFLOW_SLOTS) * TAB_W / 2;
    private static final int LIST_X = 8;
    private static final int LIST_Y = 32;
    private static final int LIST_W = 160;
    private static final int ROW_H = 24;
    private static final int VISIBLE_ROWS = 6;
    private static final int BTN_W = 44;
    private static final int BTN_H = 16;
    private static final int SB_X = LIST_X + LIST_W + 3;
    private static final int SB_W = 6;
    private static final int SB_H = VISIBLE_ROWS * ROW_H - 2;

    private record Row(ShopEntry entry, int owned) {}

    private int selectedTab;
    /** 탭 줄에서 맨 왼쪽에 보이는 탭 번호 (탭이 7개를 넘을 때만 의미 있음). */
    private int tabOffset;
    private int scroll;
    private boolean draggingBar;
    private QuantityPopup popup;
    private boolean editing;
    private Button editToggle;
    private final List<AbstractWidget> editWidgets = new ArrayList<>();
    private String editSignature;

    /** EditBox that commits when it loses focus. */
    private static final class PriceBox extends EditBox {
        private final Runnable commit;

        PriceBox(net.minecraft.client.gui.Font font, int x, int y, int w, int h, Runnable commit) {
            super(font, x, y, w, h, Component.empty());
            this.commit = commit;
            setMaxLength(12);
            setFilter(str -> str.chars().allMatch(c -> c >= '0' && c <= '9'));
        }

        @Override
        public void setFocused(boolean focused) {
            boolean was = isFocused();
            super.setFocused(focused);
            if (was && !focused) commit.run();
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (isFocused() && (key == 257 || key == 335)) {
                commit.run();
                return true;
            }
            return super.keyPressed(key, scan, mods);
        }
    }

    public ShopScreen(ShopMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 360;
        this.imageHeight = 200;
    }

    @Override
    protected void init() {
        super.init();
        editWidgets.clear();
        editSignature = null;
        editToggle = null;
        if (menu.canEdit()) {
            editToggle = addRenderableWidget(Button.builder(editLabel(), b -> {
                editing = !editing;
                b.setMessage(editLabel());
                editSignature = null;
                syncEditWidgets();
            }).bounds(leftPos + imageWidth - 48, topPos + 4, 44, 16).build());
        }
        if (menu.mode() == ShopMode.SELL) {
            addRenderableWidget(Button.builder(Component.translatable("gettcore.gui.sell_all"),
                    b -> PacketDistributor.sendToServer(new TradePayload(menu.shopId(),
                            ResourceLocation.withDefaultNamespace("air"), 1, ShopMode.SELL, true))).bounds(leftPos + LIST_X, topPos + 176, LIST_W, 18).build());
        }
    }

    private Component editLabel() {
        return Component.translatable(editing ? "gettcore.gui.edit_on" : "gettcore.gui.edit");
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (menu.canEdit()) syncEditWidgets();
    }

    private void clearEditWidgets() {
        for (AbstractWidget w : editWidgets) removeWidget(w);
        editWidgets.clear();
    }

    /** Rebuilds the per-row edit widgets whenever the visible rows (or their prices) change. */
    private void syncEditWidgets() {
        ShopDefinition def = definition();
        if (!editing || def == null) {
            if (!editWidgets.isEmpty()) clearEditWidgets();
            editSignature = null;
            return;
        }
        List<Row> rows = rows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - VISIBLE_ROWS)));
        StringBuilder sig = new StringBuilder().append(selectedTab).append('|').append(scroll).append('|').append(width).append('|').append(height);
        for (int i = 0; i < VISIBLE_ROWS && scroll + i < rows.size(); i++) {
            ShopEntry e = rows.get(scroll + i).entry();
            sig.append(';').append(e.item()).append(',').append(e.buyPrice()).append(',').append(e.sellPrice())
                    .append(',').append(e.canBuy()).append(',').append(e.canSell());
        }
        if (sig.toString().equals(editSignature)) return;
        editSignature = sig.toString();
        clearEditWidgets();
        int ex = leftPos + LIST_X + LIST_W + 6;
        for (int i = 0; i < VISIBLE_ROWS && scroll + i < rows.size(); i++) {
            ShopEntry e = rows.get(scroll + i).entry();
            int y = topPos + LIST_Y + i * ROW_H + 3;
            PriceBox[] boxes = new PriceBox[2];
            boxes[0] = new PriceBox(font, ex, y, 62, 16, () -> commitPrice(e, boxes[0], boxes[1]));
            boxes[1] = new PriceBox(font, ex + 66, y, 62, 16, () -> commitPrice(e, boxes[0], boxes[1]));
            boxes[0].setValue(Long.toString(e.buyPrice()));
            boxes[1].setValue(Long.toString(e.sellPrice()));
            Button del = Button.builder(Component.translatable("gettcore.gui.delete"),
                    b -> PacketDistributor.sendToServer(new ShopEditPayload(menu.shopId(),
                            ShopEditPayload.Action.REMOVE, e.item(), e.tab(), 0, 0, false, false)))
                    .bounds(ex + 132, y, 44, 16).build();
            for (AbstractWidget w : new AbstractWidget[] {boxes[0], boxes[1], del}) {
                editWidgets.add(addRenderableWidget(w));
            }
        }
        ShopDefinition d = def;
        if (!d.tabs().isEmpty()) {
            String tabId = d.tabs().get(Math.min(selectedTab, d.tabs().size() - 1)).id();
            editWidgets.add(addRenderableWidget(Button.builder(Component.translatable("gettcore.gui.add_held"),
                    b -> addHeld(tabId)).bounds(ex, topPos + 176, 176, 18).build()));
        }
    }

    private void commitPrice(ShopEntry e, EditBox buy, EditBox sell) {
        long nb;
        long ns;
        try {
            nb = buy.getValue().isEmpty() ? 0 : Long.parseLong(buy.getValue());
            ns = sell.getValue().isEmpty() ? 0 : Long.parseLong(sell.getValue());
        } catch (NumberFormatException ex) {
            return;
        }
        if (nb == e.buyPrice() && ns == e.sellPrice()) return;
        PacketDistributor.sendToServer(new ShopEditPayload(menu.shopId(), ShopEditPayload.Action.SET_PRICE,
                e.item(), e.tab(), nb, ns, e.canBuy(), e.canSell()));
    }

    private void addHeld(String tabId) {
        ItemStack held = minecraft.player.getInventory().getSelected();
        if (held.isEmpty()) return;
        PacketDistributor.sendToServer(new ShopEditPayload(menu.shopId(), ShopEditPayload.Action.ADD,
                BuiltInRegistries.ITEM.getKey(held.getItem()), tabId, 100, 50, true, true));
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
            if (editing) {
                out.add(new Row(e, 0));
            } else if (menu.mode() == ShopMode.BUY) {
                if (e.canBuy()) out.add(new Row(e, 0));
            } else if (e.canSell()) {
                // 보유 수량이 0이어도 목록에 남긴다(버튼만 비활성화). 전체 판매 목록을 한눈에 볼 수 있게.
                out.add(new Row(e, TradeCalculator.countSellable(minecraft.player.getInventory().items, e.item())));
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
            int total = def.tabs().size();
            tabOffset = TabStrip.clampOffset(tabOffset, total);
            for (int i = 0; i < total; i++) {
                int x = tabX(i, total);
                if (x < 0) continue;
                ShopTab tab = def.tabs().get(i);
                int y = topPos + TAB_Y;
                g.fill(x, y, x + TAB_W - 2, y + TAB_H, i == selectedTab ? 0xFFFFF6DC : 0xFFD9C9A0);
                g.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(tab.icon())), x + 3, y + 3);
            }
            if (canScrollTabs(total, -1)) renderTabArrow(g, arrowX(-1), "◁");
            if (canScrollTabs(total, 1)) renderTabArrow(g, arrowX(1), "▷");
        }
        List<Row> rows = rows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - VISIBLE_ROWS)));
        for (int i = 0; i < VISIBLE_ROWS && scroll + i < rows.size(); i++) {
            Row row = rows.get(scroll + i);
            int x = leftPos + LIST_X;
            int y = topPos + LIST_Y + i * ROW_H;
            g.fill(x, y, x + LIST_W, y + ROW_H - 2, 0xFFE8DAB4);
            g.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(row.entry().item())), x + 4, y + 3);
            if (editing) continue;
            int bx = x + LIST_W - BTN_W - 4;
            int by = y + 3;
            boolean enabled = rowEnabled(row);
            g.fill(bx, by, bx + BTN_W, by + BTN_H, enabled ? 0xFF8B6B3A : 0xFF6A6A6A);
            g.fill(bx + 1, by + 1, bx + BTN_W - 1, by + BTN_H - 1, enabled ? 0xFFB98F4E : 0xFF9A9A9A);
            Component label = Component.translatable(
                    menu.mode() == ShopMode.BUY ? "gettcore.gui.buy" : "gettcore.gui.sell");
            g.drawCenteredString(font, label, bx + BTN_W / 2, by + 4, enabled ? 0xFFFFFFFF : 0xFFDDDDDD);
        }
        renderScrollbar(g, rows.size());
    }

    /** 행의 아이콘·이름 위(구매/판매 버튼 왼쪽)에 마우스를 올리면 바닐라 아이템 툴팁(효과·방어력 등)을 보여준다. */
    private void renderRowTooltip(GuiGraphics g, int mouseX, int mouseY) {
        int x = leftPos + LIST_X;
        int right = editing ? x + LIST_W : x + LIST_W - BTN_W - 4;
        if (mouseX < x || mouseX >= right) return;
        int i = (mouseY - (topPos + LIST_Y)) / ROW_H;
        if (mouseY < topPos + LIST_Y || i >= VISIBLE_ROWS) return;
        if ((mouseY - (topPos + LIST_Y)) % ROW_H >= ROW_H - 2) return;
        List<Row> rows = rows();
        if (scroll + i >= rows.size()) return;
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(rows.get(scroll + i).entry().item()));
        g.renderTooltip(font, stack, mouseX, mouseY);
    }

    /** 보이는 탭이면 화면 x, 아니면 -1. 넘칠 때는 왼쪽 화살표 자리만큼 밀린다. */
    private int tabX(int i, int total) {
        int visible = TabStrip.visibleCount(total);
        if (i < tabOffset || i >= tabOffset + visible) return -1;
        int start = leftPos + LIST_X + (TabStrip.overflows(total) ? ARROW_W : 0);
        return start + (i - tabOffset) * TAB_W;
    }

    /** dir -1 = ◁ (줄 왼쪽 끝), 1 = ▷ (줄 오른쪽 끝). */
    private int arrowX(int dir) {
        return dir < 0 ? leftPos + LIST_X : leftPos + LIST_X + TabStrip.SLOTS * TAB_W - ARROW_W;
    }

    private boolean canScrollTabs(int total, int dir) {
        if (!TabStrip.overflows(total)) return false;
        return dir < 0 ? tabOffset > 0 : tabOffset + TabStrip.visibleCount(total) < total;
    }

    private void renderTabArrow(GuiGraphics g, int x, String arrow) {
        int y = topPos + TAB_Y;
        g.fill(x, y, x + ARROW_W - 2, y + TAB_H, 0xFFD9C9A0);
        g.drawCenteredString(font, arrow, x + (ARROW_W - 2) / 2, y + (TAB_H - 8) / 2, 0xFF3B2F1C);
    }

    private boolean overTabRow(double mouseX, double mouseY) {
        return mouseX >= leftPos + LIST_X && mouseX < leftPos + LIST_X + TabStrip.SLOTS * TAB_W
                && mouseY >= topPos + TAB_Y && mouseY < topPos + TAB_Y + TAB_H;
    }

    /** 목록 오른쪽의 드래그 가능한 스크롤바. 행이 화면보다 적으면 트랙만 흐리게 그린다. */
    private void renderScrollbar(GuiGraphics g, int rowCount) {
        int x = leftPos + SB_X;
        int y = topPos + LIST_Y;
        g.fill(x, y, x + SB_W, y + SB_H, 0xFF8B7A5A);
        if (rowCount <= VISIBLE_ROWS) {
            g.fill(x + 1, y + 1, x + SB_W - 1, y + SB_H - 1, 0xFFC9BA94);
            return;
        }
        int thumbH = thumbHeight(rowCount);
        int thumbY = y + (SB_H - thumbH) * scroll / (rowCount - VISIBLE_ROWS);
        g.fill(x + 1, y + 1, x + SB_W - 1, y + SB_H - 1, 0xFFC9BA94);
        g.fill(x + 1, thumbY, x + SB_W - 1, thumbY + thumbH, draggingBar ? 0xFF6B4B1A : 0xFF8B6B3A);
    }

    private static int thumbHeight(int rowCount) {
        return Math.max(12, SB_H * VISIBLE_ROWS / rowCount);
    }

    private void scrollToMouse(double mouseY) {
        int rowCount = rows().size();
        if (rowCount <= VISIBLE_ROWS) {
            scroll = 0;
            return;
        }
        int thumbH = thumbHeight(rowCount);
        double frac = (mouseY - (topPos + LIST_Y) - thumbH / 2.0) / (SB_H - thumbH);
        scroll = (int) Math.round(Math.max(0, Math.min(1, frac)) * (rowCount - VISIBLE_ROWS));
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingBar) {
            scrollToMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingBar = false;
        return super.mouseReleased(mouseX, mouseY, button);
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
            int total = def.tabs().size();
            for (int i = 0; i < total; i++) {
                int x = tabX(i, total);
                int y = topPos + TAB_Y;
                if (x >= 0 && mouseX >= x && mouseX < x + TAB_W - 2 && mouseY >= y && mouseY < y + TAB_H) {
                    g.renderTooltip(font, Component.literal(def.tabs().get(i).name()), mouseX, mouseY);
                }
            }
            renderRowTooltip(g, mouseX, mouseY);
        }
        if (popup != null) popup.render(g, mouseX, mouseY, partialTick);
    }

    /** 판매 모드에서 보유 수량이 0이면 버튼을 비활성화한다. */
    private boolean rowEnabled(Row row) {
        return menu.mode() == ShopMode.BUY || row.owned() > 0;
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
        // Like AnvilScreen: a focused price box swallows every key except ESC, so E / hotbar digits do not leak through.
        if (getFocused() instanceof PriceBox box && box.isFocused() && key != 256) {
            return box.keyPressed(key, scan, mods) || box.canConsumeInput();
        }
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
            int total = def.tabs().size();
            int y = topPos + TAB_Y;
            for (int dir : new int[] {-1, 1}) {
                int ax = arrowX(dir);
                if (canScrollTabs(total, dir) && mouseX >= ax && mouseX < ax + ARROW_W - 2
                        && mouseY >= y && mouseY < y + TAB_H) {
                    tabOffset = TabStrip.clampOffset(tabOffset + dir, total);
                    return true;
                }
            }
            for (int i = 0; i < total; i++) {
                int x = tabX(i, total);
                if (x >= 0 && mouseX >= x && mouseX < x + TAB_W - 2 && mouseY >= y && mouseY < y + TAB_H) {
                    selectedTab = i;
                    tabOffset = TabStrip.ensureVisible(tabOffset, i, total);
                    scroll = 0;
                    return true;
                }
            }
        }
        if (button == 0 && mouseX >= leftPos + SB_X && mouseX < leftPos + SB_X + SB_W
                && mouseY >= topPos + LIST_Y && mouseY < topPos + LIST_Y + SB_H) {
            draggingBar = true;
            scrollToMouse(mouseY);
            return true;
        }
        if (button == 0 && !editing) {
            List<Row> rows = rows();
            for (int i = 0; i < VISIBLE_ROWS && scroll + i < rows.size(); i++) {
                int bx = leftPos + LIST_X + LIST_W - BTN_W - 4;
                int by = topPos + LIST_Y + i * ROW_H + 3;
                if (mouseX >= bx && mouseX < bx + BTN_W && mouseY >= by && mouseY < by + BTN_H) {
                    Row row = rows.get(scroll + i);
                    if (rowEnabled(row)) openPopup(row.entry());
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (popup != null) return true;
        ShopDefinition def = definition();
        if (def != null && overTabRow(mouseX, mouseY)) {
            int total = def.tabs().size();
            if (scrollY != 0) tabOffset = TabStrip.clampOffset(tabOffset - (int) Math.signum(scrollY), total);
            return true;
        }
        if (mouseX >= leftPos + LIST_X && mouseX < leftPos + LIST_X + LIST_W
                && mouseY >= topPos + LIST_Y && mouseY < topPos + LIST_Y + VISIBLE_ROWS * ROW_H) {
            scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
