package org.gettserver.gettcore.craft.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.craft.ClientCraftConfig;
import org.gettserver.gettcore.craft.CraftConfig;
import org.gettserver.gettcore.craft.CraftConfig.Ingredient;
import org.gettserver.gettcore.craft.CraftConfig.Recipe;
import org.gettserver.gettcore.craft.CraftInventory;
import org.gettserver.gettcore.craft.CraftTabRow;
import org.gettserver.gettcore.economy.ClientBalance;
import org.gettserver.gettcore.economy.ClientConfig;
import org.gettserver.gettcore.economy.Gold;
import org.gettserver.gettcore.network.CraftRequestPayload;
import org.gettserver.gettcore.preset.ItemPresets;
import org.lwjgl.glfw.GLFW;

/**
 * The crafting screen of the craft bench. Tabs = recipe categories (◁/▷ and the wheel move the row when they do not
 * all fit); the grid on the right lists what can be made (green frame = everything is in the inventory), hovering
 * shows what it takes; clicking picks it, and the panel on the left shows the ingredients as icons (green frame =
 * enough, red = missing; hovering shows the item's tooltip with needed/held counts), the gold and the 제작 button.
 * The server makes the item at once and the screen stays open. Client only.
 */
public final class CraftScreen extends Screen {
    private static final int W = 340;
    private static final int H = 210;
    private static final int BG = 0xF00E1622;
    private static final int BORDER = 0xFF3A4A68;
    private static final int BAND = 0xFF1B2C48;
    private static final int PANEL = 0xFF141E2E;
    private static final int SLOT = 0xFF0B121C;
    private static final int CELL = 18;
    private static final int COLS = 10;
    private static final int ROWS = 7;
    private static final int DETAIL_W = 134;
    private static final int GREEN = 0xFF55E070;
    private static final int RED = 0xFFFF6060;
    private static final int TAB_GAP = 2;
    private static final int TAB_H = 18;
    private static final int ARROW_W = 14;
    /** Ingredient icons: {@link #ING_COLS} boxes of {@link #ING_BOX} px, {@link #ING_PITCH} apart. */
    private static final int ING_COLS = 6;
    private static final int ING_BOX = 20;
    private static final int ING_PITCH = 21;

    private List<String> categories = List.of();
    private String category = "";
    private List<Recipe> shown = List.of();
    private Recipe selected;
    private int scrollRow;
    private int tabOffset;
    /** Move the tab row so the selected tab is on it (after picking a tab; ◁/▷ leave the row where they put it). */
    private boolean revealTab = true;
    private boolean tabsOverflow;
    private Button craftButton;
    private CraftConfig seenConfig;
    private final Map<String, CraftInventory.Plan> plans = new HashMap<>();

    public CraftScreen() {
        super(Component.translatable("gettcore.craft.title"));
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new CraftScreen());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int left() {
        return (width - W) / 2;
    }

    private int top() {
        return Math.max(4, (height - H) / 2);
    }

    private int tabTop() {
        return top() + 27;
    }

    private int gridLeft() {
        return left() + 8 + DETAIL_W + 8;
    }

    private int gridTop() {
        return top() + (categories.size() > 1 ? 50 : 30);
    }

    private static Component categoryName(String c) {
        return c.isEmpty() ? Component.translatable("gettcore.craft.category.none") : Component.literal(c);
    }

    @Override
    protected void init() {
        CraftConfig config = ClientCraftConfig.get();
        seenConfig = config;
        categories = config.categories();
        if (!categories.contains(category)) {
            category = categories.isEmpty() ? "" : categories.get(0);
            revealTab = true;
        }
        shown = config.recipes().stream().filter(r -> r.category().equals(category)).toList();
        if (selected != null) selected = config.recipe(selected.id()).filter(shown::contains).orElse(null);
        if (selected == null && !shown.isEmpty()) selected = shown.get(0);
        scrollRow = Math.max(0, Math.min(scrollRow, maxScroll()));

        tabsOverflow = false;
        if (categories.size() > 1) initTabs();
        craftButton = addRenderableWidget(Button.builder(Component.translatable("gettcore.craft.make"), b -> {
            if (selected != null) PacketDistributor.sendToServer(new CraftRequestPayload(selected.id()));
        }).bounds(left() + 8 + 6, top() + H - 34, DETAIL_W - 12, 20).build());
        refreshPlans();
    }

    /** The tab row: every tab when they fit, else ◁, the tabs from {@link #tabOffset} that fit, ▷. */
    private void initTabs() {
        int[] widths = new int[categories.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = Math.min(90, font.width(categoryName(categories.get(i))) + 12);
        }
        int rowX = left() + 8;
        int rowW = W - 16;
        tabsOverflow = CraftTabRow.overflows(widths, rowW, TAB_GAP);
        int avail = tabsOverflow ? rowW - 2 * (ARROW_W + TAB_GAP) : rowW;
        tabOffset = revealTab
                ? CraftTabRow.ensureVisible(tabOffset, categories.indexOf(category), widths, avail, TAB_GAP)
                : CraftTabRow.clampOffset(tabOffset, widths, avail, TAB_GAP);
        revealTab = false;
        int visible = CraftTabRow.visible(widths, tabOffset, avail, TAB_GAP);
        int x = rowX + (tabsOverflow ? ARROW_W + TAB_GAP : 0);
        for (int i = tabOffset; i < tabOffset + visible; i++) {
            String c = categories.get(i);
            int w = Math.min(widths[i], avail);
            Button tab = addRenderableWidget(Button.builder(categoryName(c), b -> {
                category = c;
                selected = null;
                scrollRow = 0;
                revealTab = true;
                rebuildWidgets();
            }).bounds(x, tabTop(), w, TAB_H).build());
            tab.active = !c.equals(category);
            x += w + TAB_GAP;
        }
        if (tabsOverflow) {
            Button back = addRenderableWidget(Button.builder(Component.literal("◁"), b -> shiftTabs(-1))
                    .bounds(rowX, tabTop(), ARROW_W, TAB_H).build());
            back.active = tabOffset > 0;
            Button forward = addRenderableWidget(Button.builder(Component.literal("▷"), b -> shiftTabs(1))
                    .bounds(rowX + rowW - ARROW_W, tabTop(), ARROW_W, TAB_H).build());
            forward.active = tabOffset + visible < categories.size();
        }
    }

    /** init() clamps the offset, so stepping past either end changes nothing. */
    private void shiftTabs(int dir) {
        tabOffset += dir;
        rebuildWidgets();
    }

    private int maxScroll() {
        return Math.max(0, (shown.size() + COLS - 1) / COLS - ROWS);
    }

    private void refreshPlans() {
        plans.clear();
        if (minecraft == null || minecraft.player == null) return;
        for (Recipe r : shown) plans.put(r.id(), CraftInventory.plan(r, minecraft.player.getInventory().items));
        craftButton.active = selected != null && canMake(selected);
    }

    private boolean canMake(Recipe r) {
        CraftInventory.Plan plan = plans.get(r.id());
        return plan != null && plan.craftable() && ClientBalance.get() >= r.gold() && !resultOf(r).isEmpty();
    }

    @Override
    public void tick() {
        // The recipes were reloaded on the server while the screen was open.
        if (seenConfig != ClientCraftConfig.get()) rebuildWidgets();
        else refreshPlans();
    }

    private static ItemStack resultOf(Recipe r) {
        ResourceLocation id = ResourceLocation.tryParse(r.result());
        return id == null ? ItemStack.EMPTY : ItemPresets.stack(id, Math.max(1, Math.min(r.count(), 99)));
    }

    private static ItemStack stackOf(Ingredient g) {
        ResourceLocation id = ResourceLocation.tryParse(g.item());
        return id == null ? ItemStack.EMPTY : ItemPresets.stack(id, 1);
    }

    private static String ingredientName(Ingredient g) {
        ResourceLocation id = ResourceLocation.tryParse(g.item());
        String name = id == null ? g.item() : ItemPresets.name(id);
        return g.minReinforce() > 0 ? name + " +" + g.minReinforce() : name;
    }

    private int have(Recipe r, int ingredient) {
        CraftInventory.Plan plan = plans.get(r.id());
        return plan == null ? 0 : plan.have()[ingredient];
    }

    private Component goldLine(Recipe r) {
        boolean ok = ClientBalance.get() >= r.gold();
        return Component.literal(Gold.format(r.gold()) + " " + ClientConfig.currencyName())
                .withStyle(s -> s.withColor(ok ? GREEN : RED));
    }

    /** "필요 재료" lines of a recipe, coloured by what the inventory holds (the grid's tooltip). */
    private List<Component> requirementLines(Recipe r) {
        List<Component> out = new ArrayList<>();
        out.add(Component.translatable("gettcore.craft.needs").withStyle(ChatFormatting.GRAY));
        for (int i = 0; i < r.ingredients().size(); i++) {
            Ingredient g = r.ingredients().get(i);
            int have = have(r, i);
            MutableComponent line = Component.literal("- " + ingredientName(g) + " x" + g.count()
                    + " (" + Math.min(have, 9999) + ")");
            out.add(line.withStyle(s -> s.withColor(have >= g.count() ? GREEN : RED)));
        }
        if (r.gold() > 0) out.add(Component.literal("- ").append(goldLine(r)).withStyle(goldLine(r).getStyle()));
        return out;
    }

    /** The count drawn on an ingredient icon: "", "16", "2.3k". */
    static String countText(int count) {
        if (count <= 1) return "";
        if (count < 1000) return Integer.toString(count);
        return String.format(Locale.ROOT, "%.1fk", count / 1000.0);
    }

    private int ingredientsLeft() {
        return left() + 8 + (DETAIL_W - ING_COLS * ING_PITCH + (ING_PITCH - ING_BOX)) / 2;
    }

    private int ingredientsTop() {
        return gridTop() + 68;
    }

    /** Index of the selected recipe's ingredient whose icon is under the mouse, or -1. */
    private int ingredientAt(double mx, double my) {
        if (selected == null) return -1;
        int rx = (int) Math.floor(mx) - ingredientsLeft();
        int ry = (int) Math.floor(my) - ingredientsTop();
        if (rx < 0 || ry < 0 || rx >= ING_COLS * ING_PITCH) return -1;
        if (rx % ING_PITCH >= ING_BOX || ry % ING_PITCH >= ING_BOX) return -1;
        int idx = ry / ING_PITCH * ING_COLS + rx / ING_PITCH;
        return idx < selected.ingredients().size() ? idx : -1;
    }

    private int cellAt(double mx, double my) {
        int gx = gridLeft();
        int gy = gridTop();
        if (mx < gx || my < gy || mx >= gx + COLS * CELL || my >= gy + ROWS * CELL) return -1;
        int idx = (scrollRow + (int) (my - gy) / CELL) * COLS + (int) (mx - gx) / CELL;
        return idx < shown.size() ? idx : -1;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        g.fill(x, y, x + W, y + H, BG);
        g.renderOutline(x, y, W, H, BORDER);
        g.fill(x + 6, y + 6, x + W - 6, y + 22, BAND);
        g.drawCenteredString(font, title, x + W / 2, y + 10, 0xFFFFFFFF);

        // Detail panel.
        int dx = x + 8;
        int dy = gridTop();
        g.fill(dx, dy, dx + DETAIL_W, y + H - 8, PANEL);
        if (shown.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("gettcore.craft.empty"), gridLeft() + COLS * CELL / 2,
                    gridTop() + 40, 0xFFC8D0DC);
        } else if (selected != null) {
            ItemStack result = resultOf(selected);
            g.pose().pushPose();
            g.pose().translate(dx + DETAIL_W / 2f - 16, dy + 6, 0);
            g.pose().scale(2f, 2f, 1f);
            if (!result.isEmpty()) g.renderItem(result, 0, 0);
            g.pose().popPose();
            String name = result.isEmpty() ? selected.result() : result.getHoverName().getString();
            if (selected.count() > 1) name += " x" + selected.count();
            g.drawCenteredString(font, font.plainSubstrByWidth(name, DETAIL_W - 8), dx + DETAIL_W / 2, dy + 42, 0xFFFFFFFF);
            g.drawString(font, Component.translatable("gettcore.craft.needs"), dx + 5, dy + 56, 0xFFA0A8B8, false);
            if (selected.gold() > 0) {
                Component gold = goldLine(selected);
                g.drawString(font, gold, dx + DETAIL_W - 5 - font.width(gold), dy + 56,
                        0xFF000000 | gold.getStyle().getColor().getValue(), false);
            }
            renderIngredients(g, selected, y + H - 38);
        }

        // Recipe grid.
        int gx = gridLeft();
        int gy = gridTop();
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int cx = gx + col * CELL;
                int cy = gy + row * CELL;
                g.fill(cx, cy, cx + CELL - 1, cy + CELL - 1, PANEL);
                int idx = (scrollRow + row) * COLS + col;
                if (idx >= shown.size()) continue;
                Recipe r = shown.get(idx);
                ItemStack stack = resultOf(r);
                if (!stack.isEmpty()) {
                    g.renderItem(stack, cx, cy);
                    g.renderItemDecorations(font, stack, cx, cy);
                }
                if (canMake(r)) g.renderOutline(cx - 1, cy - 1, CELL + 1, CELL + 1, GREEN);
                if (r == selected) g.renderOutline(cx - 1, cy - 1, CELL + 1, CELL + 1, 0xFFFFE060);
            }
        }
        if (maxScroll() > 0) {
            String page = (scrollRow + 1) + " / " + (maxScroll() + 1);
            g.drawString(font, page, gx + COLS * CELL - font.width(page), gy + ROWS * CELL + 3, 0xFF8090A8, false);
        }
    }

    /**
     * One framed icon per ingredient: the needed count on it, "+N" in the corner when it asks for a reinforce level,
     * a green frame when the inventory holds enough and a red one when it does not. A recipe has at most
     * {@link CraftConfig#MAX_INGREDIENTS} ingredients, which is two rows.
     */
    private void renderIngredients(GuiGraphics g, Recipe r, int bottom) {
        int x0 = ingredientsLeft();
        int y0 = ingredientsTop();
        for (int i = 0; i < r.ingredients().size(); i++) {
            Ingredient ing = r.ingredients().get(i);
            int bx = x0 + i % ING_COLS * ING_PITCH;
            int by = y0 + i / ING_COLS * ING_PITCH;
            if (by + ING_BOX > bottom) break;
            g.fill(bx, by, bx + ING_BOX, by + ING_BOX, SLOT);
            g.renderOutline(bx, by, ING_BOX, ING_BOX, have(r, i) >= ing.count() ? GREEN : RED);
            ItemStack stack = stackOf(ing);
            if (stack.isEmpty()) {
                g.drawCenteredString(font, "?", bx + ING_BOX / 2, by + 6, RED);
            } else {
                g.renderItem(stack, bx + 2, by + 2);
                g.renderItemDecorations(font, stack, bx + 2, by + 2, countText(ing.count()));
            }
            if (ing.minReinforce() > 0) {
                g.pose().pushPose();
                g.pose().translate(bx + 2, by + 2, 200);
                g.pose().scale(0.75f, 0.75f, 1f);
                g.drawString(font, "+" + ing.minReinforce(), 0, 0, 0xFFFFD050, true);
                g.pose().popPose();
            }
        }
    }

    /** The ingredient item's own tooltip, with what the recipe needs of it and what the inventory holds. */
    private List<Component> ingredientTooltip(Recipe r, int index) {
        Ingredient ing = r.ingredients().get(index);
        ItemStack stack = stackOf(ing);
        List<Component> lines = new ArrayList<>(stack.isEmpty()
                ? List.of(Component.literal(ing.item()).withStyle(ChatFormatting.RED))
                : getTooltipFromItem(minecraft, stack));
        int have = have(r, index);
        List<Component> ours = new ArrayList<>();
        ours.add(Component.translatable("gettcore.craft.ing.count", ing.count(), Math.min(have, 9999))
                .withStyle(s -> s.withColor(have >= ing.count() ? GREEN : RED)));
        if (ing.minReinforce() > 0) {
            ours.add(Component.translatable("gettcore.craft.ing.reinforce", ing.minReinforce())
                    .withStyle(ChatFormatting.GOLD));
        }
        lines.addAll(1, ours);
        return lines;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int ing = ingredientAt(mouseX, mouseY);
        if (ing >= 0) {
            g.renderComponentTooltip(font, ingredientTooltip(selected, ing), mouseX, mouseY);
            return;
        }
        int idx = cellAt(mouseX, mouseY);
        if (idx < 0) return;
        Recipe r = shown.get(idx);
        ItemStack stack = resultOf(r);
        List<Component> lines = new ArrayList<>();
        lines.add(stack.isEmpty() ? Component.literal(r.result()) : stack.getHoverName());
        lines.addAll(requirementLines(r));
        g.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        int idx = cellAt(mouseX, mouseY);
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && idx >= 0) {
            selected = shown.get(idx);
            craftButton.active = canMake(selected);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tabsOverflow && scrollY != 0 && mouseY >= tabTop() && mouseY < tabTop() + TAB_H
                && mouseX >= left() + 8 && mouseX < left() + W - 8) {
            shiftTabs(-(int) Math.signum(scrollY));
            return true;
        }
        if (maxScroll() > 0 && scrollY != 0) {
            scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
