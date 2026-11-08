package org.gettserver.gettcore.pet.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.client.ClientPetConfig;
import org.gettserver.gettcore.network.PetDexActionPayload;
import org.gettserver.gettcore.pet.CapturedMobItem;
import org.gettserver.gettcore.pet.PetKey;
import org.gettserver.gettcore.pet.PetSpecies;
import org.jetbrains.annotations.Nullable;

/** The pet dex: a paged grid of owned pets; left click to summon / recall, right click to release. Client only. */
public class PetDexScreen extends Screen {
    private static final int COLS = 5;
    private static final int ROWS = 4;
    private static final int CELL = 48;
    private static final int GAP = 4;
    private static final int PAD = 8;
    /** Title (top + 8) and the controls hint (top + 20) sit inside the panel, above the grid. */
    private static final int HEADER = 34;
    private static final int FOOTER = 26;
    /** Smallest distance kept between the panel and the top / bottom screen edge. */
    private static final int MARGIN = 4;
    private static final int GRID_W = COLS * CELL + (COLS - 1) * GAP;
    private static final int PANEL_W = GRID_W + PAD * 2;
    private static final int BG = 0xE0101018;
    private static final int BORDER = 0xFF8080A0;
    private static final int CELL_BG = 0x80000000;
    private static final int BOSS_BORDER = 0xFFF2C94C;
    private static final int ACTIVE_BORDER = 0xFF55FF55;
    private static final double BOSS_SPEED_MULT = 1.3;

    private final List<@Nullable LivingEntity> previews = new ArrayList<>();
    private List<PetKey> previewKeys = List.of();
    private int page;
    private Button prev;
    private Button next;

    public PetDexScreen() {
        super(Component.translatable("gettcore.gui.pet_dex.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Rows per page: {@value #ROWS}, or fewer when the screen is too short to show them all (minimum 1). The fit
     * reserves the footer, so the panel only overflows (downwards) when not even one row fits.
     */
    private int rowsPerPage() {
        return PetDexPaging.rowsThatFit(height - 2 * MARGIN - HEADER - FOOTER - PAD, CELL, GAP, ROWS);
    }

    private int perPage() {
        return COLS * rowsPerPage();
    }

    private boolean paged() {
        return ClientPetDex.owned().size() > perPage();
    }

    /** Rows shown on the current page (1 when empty: the "no pets" message takes one row). */
    private int rows() {
        int count = PetDexPaging.countOnPage(ClientPetDex.owned().size(), page, perPage());
        return PetDexPaging.rowsFor(count, COLS, rowsPerPage());
    }

    private int gridH() {
        int rows = rows();
        return rows * CELL + (rows - 1) * GAP;
    }

    private int panelH() {
        return HEADER + gridH() + (paged() ? FOOTER : 0) + PAD;
    }

    private int left() {
        return (width - PANEL_W) / 2;
    }

    /** Vertically centred, never closer than {@value #MARGIN} px to the top edge (never above y = 0). */
    private int top() {
        return Math.max(MARGIN, (height - panelH()) / 2);
    }

    private int footerY() {
        return top() + HEADER + gridH() + 2;
    }

    @Override
    protected void init() {
        prev = addRenderableWidget(Button.builder(Component.literal("‹"), b -> page--)
                .bounds(width / 2 - 60, footerY(), 20, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal("›"), b -> page++)
                .bounds(width / 2 + 40, footerY(), 20, 20).build());
        rebuildPreviews();
    }

    private void rebuildPreviews() {
        discardPreviews();
        previewKeys = ClientPetDex.owned();
        for (PetKey key : previewKeys) {
            previews.add(EntityPreview.create(ResourceLocation.parse(key.speciesId()), key.variant()));
        }
    }

    private void discardPreviews() {
        for (LivingEntity e : previews) {
            if (e != null) e.discard();
        }
        previews.clear();
    }

    @Override
    public void removed() {
        discardPreviews();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        List<PetKey> owned = ClientPetDex.owned();
        if (!owned.equals(previewKeys)) rebuildPreviews();
        int perPage = perPage();
        int pages = PetDexPaging.pageCount(owned.size(), perPage);
        page = Math.max(0, Math.min(page, pages - 1));
        boolean paged = paged();
        prev.visible = paged;
        next.visible = paged;
        prev.active = page > 0;
        next.active = page < pages - 1;
        // The panel height follows the rows on the current page, so the buttons move with it.
        prev.setY(footerY());
        next.setY(footerY());
        super.render(g, mouseX, mouseY, partialTick);

        int hovered = -1;
        for (int c = 0; c < perPage; c++) {
            int idx = page * perPage + c;
            if (idx >= owned.size()) break;
            drawCell(g, c, idx, owned, mouseX, mouseY);
            if (cellHit(c, mouseX, mouseY)) hovered = idx;
        }
        if (hovered >= 0) g.renderComponentTooltip(font, tooltip(hovered), mouseX, mouseY);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x1 = left(), y1 = top(), x2 = x1 + PANEL_W, y2 = y1 + panelH();
        g.fill(x1, y1, x2, y2, BG);
        outline(g, x1, y1, x2, y2, BORDER);
        g.drawCenteredString(font, title, width / 2, y1 + 8, 0xFFFFFF55);
        g.drawCenteredString(font, Component.translatable("gettcore.gui.pet_dex.hint"), width / 2, y1 + 20,
                0xFFAAAAAA);
        List<PetKey> owned = ClientPetDex.owned();
        if (owned.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("gettcore.gui.pet_dex.empty"),
                    width / 2, y1 + HEADER + gridH() / 2 - 4, 0xFFAAAAAA);
        } else if (paged()) {
            g.drawCenteredString(font, Component.translatable("gettcore.gui.pet_dex.page", page + 1,
                    PetDexPaging.pageCount(owned.size(), perPage())), width / 2, footerY() + 6, 0xFFFFFFFF);
        }
    }

    static void outline(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y1 + 1, color);
        g.fill(x1, y2 - 1, x2, y2, color);
        g.fill(x1, y1, x1 + 1, y2, color);
        g.fill(x2 - 1, y1, x2, y2, color);
    }

    private int cellX(int c) {
        return left() + PAD + (c % COLS) * (CELL + GAP);
    }

    private int cellY(int c) {
        return top() + HEADER + (c / COLS) * (CELL + GAP);
    }

    private boolean cellHit(int c, double mx, double my) {
        int x = cellX(c), y = cellY(c);
        return mx >= x && mx < x + CELL && my >= y && my < y + CELL;
    }

    private void drawCell(GuiGraphics g, int c, int idx, List<PetKey> owned, int mouseX, int mouseY) {
        PetKey key = owned.get(idx);
        boolean boss = isBoss(idx);
        boolean active = ClientPetDex.active().filter(key::equals).isPresent();
        int x = cellX(c), y = cellY(c);
        g.fill(x, y, x + CELL, y + CELL, CELL_BG);
        outline(g, x, y, x + CELL, y + CELL, cellHit(c, mouseX, mouseY) ? 0xFFFFFFFF : BORDER);
        if (boss) outline(g, x + 1, y + 1, x + CELL - 1, y + CELL - 1, BOSS_BORDER);
        if (active) outline(g, x + 2, y + 2, x + CELL - 2, y + CELL - 2, ACTIVE_BORDER);

        LivingEntity e = idx < previews.size() ? previews.get(idx) : null;
        float scale = 0;
        if (e != null) {
            float mult = boss ? species(key).map(s -> (float) s.sizeScale()).orElse(1.0F) : 1.0F;
            float h = Math.max(e.getBbHeight(), 0.3F), w = Math.max(e.getBbWidth(), 0.3F);
            float fit = Math.min((CELL - 8) / h, (CELL - 8) / w);
            scale = Math.min(fit * 0.6F * mult, fit * 0.95F);
        }
        EntityPreview.render(g, x + 3, y + 3, x + CELL - 3, y + CELL - 3, scale, e, 180.0F);
        if (active) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 200);
            g.drawCenteredString(font, Component.translatable("gettcore.gui.pet_dex.active"),
                    x + CELL / 2, y + CELL - 10, ACTIVE_BORDER);
            g.pose().popPose();
        }
    }

    private static boolean isBoss(int idx) {
        List<Boolean> flags = ClientPetDex.boss();
        return idx < flags.size() && flags.get(idx);
    }

    private static Optional<PetSpecies> species(PetKey key) {
        return ClientPetConfig.species(ResourceLocation.parse(key.speciesId()));
    }

    private List<Component> tooltip(int idx) {
        PetKey key = ClientPetDex.owned().get(idx);
        boolean boss = isBoss(idx);
        List<Component> lines = new ArrayList<>();
        lines.add(CapturedMobItem.displayName(key));
        Optional<PetSpecies> sp = species(key);
        if (sp.isEmpty()) {
            lines.add(Component.translatable("gettcore.gui.pet_dex.unknown"));
            return lines;
        }
        PetSpecies s = sp.get();
        lines.add(Component.translatable("gettcore.kind." + s.kind().name().toLowerCase(Locale.ROOT)));
        double attack = boss ? s.attack() * s.attackMult() : s.attack();
        double speed = boss ? s.speed() * BOSS_SPEED_MULT : s.speed();
        lines.add(Component.translatable("gettcore.gui.pet_dex.attack", fmt(attack)));
        lines.add(Component.translatable("gettcore.gui.pet_dex.speed", fmt(speed)));
        lines.add(Component.translatable("gettcore.gui.pet_dex.attack_speed", fmt(s.attackSpeed())));
        if (boss) lines.add(Component.translatable("gettcore.gui.pet_dex.boss"));
        return lines;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0 && button != 1) return false; // left: summon / recall, right: release
        List<PetKey> owned = ClientPetDex.owned();
        int perPage = perPage();
        for (int c = 0; c < perPage; c++) {
            int idx = page * perPage + c;
            if (idx >= owned.size()) break;
            if (cellHit(c, mx, my)) {
                if (button == 0) {
                    boolean recall = ClientPetDex.active().filter(owned.get(idx)::equals).isPresent();
                    PacketDistributor.sendToServer(new PetDexActionPayload(idx, PetDexActionPayload.Action.SUMMON));
                    // A summon closes the dex so the player sees the pet; a recall keeps it open.
                    if (!recall && minecraft != null) minecraft.setScreen(null);
                } else if (minecraft != null) {
                    minecraft.setScreen(new ConfirmReleaseScreen(this, idx, owned.get(idx)));
                }
                return true;
            }
        }
        return false;
    }
}
