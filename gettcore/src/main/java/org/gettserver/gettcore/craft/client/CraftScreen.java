package org.gettserver.gettcore.craft.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import org.gettserver.gettcore.economy.ClientBalance;
import org.gettserver.gettcore.economy.ClientConfig;
import org.gettserver.gettcore.economy.Gold;
import org.gettserver.gettcore.network.CraftRequestPayload;
import org.gettserver.gettcore.preset.ItemPresets;
import org.lwjgl.glfw.GLFW;

/**
 * The crafting screen of the craft bench. Tabs = recipe categories; the grid on the right lists what can be made
 * (green frame = everything is in the inventory), hovering shows what it takes; clicking picks it, and the panel on
 * the left shows the ingredients (green = enough, red = missing), the gold and the 제작 button. The server makes the
 * item at once and the screen stays open. Client only.
 */
public final class CraftScreen extends Screen {
    private static final int W = 340;
    private static final int H = 210;
    private static final int BG = 0xF00E1622;
    private static final int BORDER = 0xFF3A4A68;
    private static final int BAND = 0xFF1B2C48;
    private static final int PANEL = 0xFF141E2E;
    private static final int CELL = 18;
    private static final int COLS = 10;
    private static final int ROWS = 7;
    private static final int DETAIL_W = 134;
    private static final int GREEN = 0xFF55E070;
    private static final int RED = 0xFFFF6060;

    private List<String> categories = List.of();
    private String category = "";
    private List<Recipe> shown = List.of();
    private Recipe selected;
    private int scrollRow;
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
        if (!categories.contains(category)) category = categories.isEmpty() ? "" : categories.get(0);
        shown = config.recipes().stream().filter(r -> r.category().equals(category)).toList();
        if (selected != null) selected = config.recipe(selected.id()).filter(shown::contains).orElse(null);
        if (selected == null && !shown.isEmpty()) selected = shown.get(0);
        scrollRow = Math.max(0, Math.min(scrollRow, maxScroll()));

        if (categories.size() > 1) {
            int x = left() + 8;
            for (String c : categories) {
                Component label = categoryName(c);
                int w = Math.min(90, font.width(label) + 12);
                Button tab = addRenderableWidget(Button.builder(label, b -> {
                    category = c;
                    selected = null;
                    scrollRow = 0;
                    rebuildWidgets();
                }).bounds(x, top() + 27, w, 18).build());
                tab.active = !c.equals(category);
                x += w + 2;
                if (x > left() + W - 60) break;
            }
        }
        craftButton = addRenderableWidget(Button.builder(Component.translatable("gettcore.craft.make"), b -> {
            if (selected != null) PacketDistributor.sendToServer(new CraftRequestPayload(selected.id()));
        }).bounds(left() + 8 + 6, top() + H - 34, DETAIL_W - 12, 20).build());
        refreshPlans();
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

    private static String ingredientName(Ingredient g) {
        ResourceLocation id = ResourceLocation.tryParse(g.item());
        String name = id == null ? g.item() : ItemPresets.name(id);
        return g.minReinforce() > 0 ? name + " +" + g.minReinforce() : name;
    }

    /** "필요 재료" lines of a recipe, coloured by what the inventory holds. */
    private List<Component> requirementLines(Recipe r) {
        List<Component> out = new ArrayList<>();
        CraftInventory.Plan plan = plans.get(r.id());
        out.add(Component.translatable("gettcore.craft.needs").withStyle(ChatFormatting.GRAY));
        for (int i = 0; i < r.ingredients().size(); i++) {
            Ingredient g = r.ingredients().get(i);
            int have = plan == null ? 0 : plan.have()[i];
            MutableComponent line = Component.literal("- " + ingredientName(g) + " x" + g.count()
                    + " (" + Math.min(have, 9999) + ")");
            out.add(line.withStyle(s -> s.withColor(have >= g.count() ? GREEN : RED)));
        }
        if (r.gold() > 0) {
            boolean ok = ClientBalance.get() >= r.gold();
            out.add(Component.literal("- " + Gold.format(r.gold()) + " " + ClientConfig.currencyName())
                    .withStyle(s -> s.withColor(ok ? GREEN : RED)));
        }
        return out;
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
            int ly = dy + 56;
            int bottom = y + H - 38;
            for (Component line : requirementLines(selected)) {
                if (ly + 9 > bottom) break;
                g.drawString(font, font.plainSubstrByWidth(line.getString(), DETAIL_W - 10), dx + 5, ly,
                        line.getStyle().getColor() == null ? 0xFFA0A8B8 : 0xFF000000 | line.getStyle().getColor().getValue(),
                        false);
                ly += 10;
            }
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

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
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
        if (maxScroll() > 0 && scrollY != 0) {
            scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
