package org.gettserver.gettcore.quest.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.economy.ClientConfig;
import org.gettserver.gettcore.economy.Gold;
import org.gettserver.gettcore.network.QuestActionPayload;
import org.gettserver.gettcore.network.QuestDetailPayload;
import org.gettserver.gettcore.network.QuestDetailPayload.Mode;
import org.gettserver.gettcore.network.QuestSyncPayload.ObjState;

/**
 * One quest: title, description, objectives and rewards. OFFER shows 수락, REPORT 보상 받기 (each sends one
 * {@link QuestActionPayload} and closes), VIEW only 닫기. Opened from a {@link QuestDetailPayload}. "Pick one" reward
 * choices are shown under the rewards: view only in OFFER/VIEW; in REPORT one must be clicked (highlighted) before
 * 보상 받기 is enabled, and its index is sent. Client only.
 */
public class QuestScreen extends Screen {
    static final int BOX_BG = 0xE0101018;
    static final int BOX_BORDER = 0xFF8080A0;
    private static final int MAX_W = 300;
    private static final int HEADER_H = 20;
    private static final int FOOTER_H = 30;
    private static final int ICON = 18;
    private static final int LINE_H = 10;
    /** Pitch of one choice slot (18 px box + 2 px gap). */
    private static final int SLOT = 20;

    private sealed interface Row permits TextRow, GoldRow, ItemsRow, ChoicesRow, Gap {}

    private record TextRow(FormattedCharSequence text, int color) implements Row {}

    private record GoldRow(String text) implements Row {}

    private record ItemsRow(List<ItemStack> stacks) implements Row {}

    private record ChoicesRow(List<ItemStack> stacks) implements Row {}

    private record Gap(int height) implements Row {}

    /** Top-left of one visible choice slot, rebuilt every frame. */
    private record Hit(int index, int x, int y) {}

    private final QuestDetailPayload data;
    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private final List<Hit> choiceHits = new ArrayList<>();
    private boolean sent;
    /** Picked choice index (REPORT only), -1 = none yet. */
    private int chosen = -1;
    private Button primary;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int bodyH;
    private int contentH;
    private int scroll;

    public QuestScreen(QuestDetailPayload data, Screen parent) {
        super(Component.literal(data.title()));
        this.data = data;
        this.parent = parent;
    }

    /** Packet entry: opens the screen, returning to the quest list afterwards when it was asked from there. */
    public static void open(QuestDetailPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        Screen parent = mc.screen instanceof QuestLogScreen log ? log : null;
        mc.setScreen(new QuestScreen(payload, parent));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        panelW = Math.min(MAX_W, width - 20);
        int textW = panelW - 16;
        buildRows(textW);
        contentH = 0;
        for (Row r : rows) contentH += height(r, textW);
        bodyH = Math.max(LINE_H, Math.min(contentH, height - 20 - HEADER_H - FOOTER_H));
        panelH = HEADER_H + bodyH + FOOTER_H;
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        scroll = Math.min(scroll, Math.max(0, contentH - bodyH));

        int btnW = 80;
        int btnY = panelY + panelH - 25;
        Button close = Button.builder(Component.translatable("gettcore.quest.screen.close"), b -> onClose())
                .bounds(0, btnY, btnW, 20).build();
        if (data.mode() == Mode.VIEW) {
            close.setX(panelX + (panelW - btnW) / 2);
        } else {
            QuestActionPayload.Action action = data.mode() == Mode.OFFER
                    ? QuestActionPayload.Action.ACCEPT : QuestActionPayload.Action.REPORT;
            String key = data.mode() == Mode.OFFER ? "gettcore.quest.screen.accept" : "gettcore.quest.screen.report";
            primary = Button.builder(Component.translatable(key), b -> act(b, action))
                    .bounds(panelX + panelW / 2 - btnW - 4, btnY, btnW, 20).build();
            updatePrimary();
            addRenderableWidget(primary);
            close.setX(panelX + panelW / 2 + 4);
        }
        addRenderableWidget(close);
    }

    /** A report of a quest with reward choices: one must be picked first. */
    private boolean mustChoose() {
        return data.mode() == Mode.REPORT && !data.choices().isEmpty();
    }

    private void updatePrimary() {
        if (primary == null) return;
        primary.active = !sent && (!mustChoose() || chosen >= 0);
        primary.setTooltip(mustChoose() && chosen < 0
                ? Tooltip.create(Component.translatable("gettcore.quest.screen.choose_hint")) : null);
    }

    /** Sends the answer once (a double click would otherwise get a false "expired" error), then closes. */
    private void act(Button button, QuestActionPayload.Action action) {
        if (sent) return;
        boolean report = action == QuestActionPayload.Action.REPORT;
        if (report && mustChoose() && chosen < 0) return;
        sent = true;
        button.active = false;
        int choice = report && mustChoose() ? chosen : -1;
        PacketDistributor.sendToServer(new QuestActionPayload(data.lineId(), data.questId(), action, choice));
        onClose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int top = panelY + HEADER_H;
        if (button == 0 && mustChoose() && !sent && mouseY >= top && mouseY < top + bodyH) {
            for (Hit h : choiceHits) {
                if (mouseX >= h.x() && mouseX < h.x() + ICON && mouseY >= h.y() && mouseY < h.y() + ICON) {
                    chosen = h.index();
                    updatePrimary();
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private void buildRows(int textW) {
        rows.clear();
        String text = data.mode() == Mode.REPORT && !data.completeText().isBlank()
                ? data.completeText() : data.description();
        for (String para : text.split("\\R", -1)) {
            if (para.isBlank()) {
                rows.add(new Gap(LINE_H / 2));
                continue;
            }
            for (FormattedCharSequence s : font.split(Component.literal(para), textW)) {
                rows.add(new TextRow(s, 0xFFE0E0E0));
            }
        }
        if (!data.objectives().isEmpty()) {
            rows.add(new Gap(6));
            heading("gettcore.quest.screen.objectives", textW);
            for (ObjState o : data.objectives()) {
                for (FormattedCharSequence s : font.split(QuestText.objectiveLine(o, QuestHud.label(o)), textW)) {
                    rows.add(new TextRow(s, 0xFFFFFFFF));
                }
            }
        }
        rows.add(new Gap(6));
        heading("gettcore.quest.screen.rewards", textW);
        boolean any = false;
        if (data.gold() > 0) {
            rows.add(new GoldRow(Gold.format(data.gold()) + " " + ClientConfig.currencyName()));
            any = true;
        }
        List<ItemStack> stacks = data.items().stream().filter(s -> !s.isEmpty()).toList();
        if (!stacks.isEmpty()) {
            rows.add(new ItemsRow(stacks));
            any = true;
        }
        if (data.xp() > 0) {
            text(Component.translatable("gettcore.quest.screen.xp", data.xp()).withStyle(ChatFormatting.GREEN),
                    textW);
            any = true;
        }
        if (!any && data.choices().isEmpty()) {
            text(Component.translatable("gettcore.quest.screen.no_rewards").withStyle(ChatFormatting.GRAY), textW);
        }
        if (!data.choices().isEmpty()) {
            rows.add(new Gap(4));
            text(Component.translatable("gettcore.quest.screen.choose").withStyle(ChatFormatting.GOLD), textW);
            rows.add(new ChoicesRow(data.choices()));
        }
    }

    private void heading(String key, int textW) {
        text(Component.translatable(key).withStyle(ChatFormatting.AQUA), textW);
    }

    private void text(Component c, int textW) {
        for (FormattedCharSequence s : font.split(c, textW)) rows.add(new TextRow(s, 0xFFFFFFFF));
    }

    private static int perLine(int textW) {
        return Math.max(1, textW / ICON);
    }

    private static int slotsPerLine(int textW) {
        return Math.max(1, (textW + 2) / SLOT);
    }

    private static int height(Row r, int textW) {
        return switch (r) {
            case TextRow t -> LINE_H;
            case GoldRow gr -> LINE_H + 1;
            case ItemsRow ir -> ((ir.stacks().size() + perLine(textW) - 1) / perLine(textW)) * ICON;
            case ChoicesRow cr -> ((cr.stacks().size() + slotsPerLine(textW) - 1) / slotsPerLine(textW)) * SLOT;
            case Gap gap -> gap.height();
        };
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, contentH - bodyH);
        if (max == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        scroll = Math.max(0, Math.min(max, scroll - (int) (scrollY * LINE_H * 2)));
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        box(g, panelX, panelY, panelX + panelW, panelY + panelH);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        List<FormattedCharSequence> titleLines = font.split(
                Component.literal(data.title()).withStyle(ChatFormatting.YELLOW), panelW - 16);
        if (!titleLines.isEmpty()) {
            FormattedCharSequence t = titleLines.get(0);
            g.drawString(font, t, panelX + (panelW - font.width(t)) / 2, panelY + 6, 0xFFFFFFFF, false);
        }
        g.fill(panelX + 6, panelY + HEADER_H - 3, panelX + panelW - 6, panelY + HEADER_H - 2, 0x608080A0);

        int x = panelX + 8;
        int top = panelY + HEADER_H;
        int textW = panelW - 16;
        ItemStack hovered = ItemStack.EMPTY;
        ItemStack hoveredChoice = ItemStack.EMPTY;
        choiceHits.clear();
        g.enableScissor(panelX, top, panelX + panelW, top + bodyH);
        int y = top - scroll;
        for (Row r : rows) {
            int h = height(r, textW);
            if (y + h >= top && y <= top + bodyH) {
                switch (r) {
                    case TextRow t -> g.drawString(font, t.text(), x, y, t.color(), false);
                    case GoldRow gr -> {
                        g.blit(GettCore.id("textures/gui/coin.png"), x, y, 0, 0, 9, 9, 9, 9);
                        g.drawString(font, gr.text(), x + 12, y + 1, 0xFFFFD700, false);
                    }
                    case ItemsRow ir -> {
                        int per = perLine(textW);
                        for (int i = 0; i < ir.stacks().size(); i++) {
                            ItemStack s = ir.stacks().get(i);
                            int ix = x + (i % per) * ICON;
                            int iy = y + (i / per) * ICON;
                            g.renderItem(s, ix, iy);
                            g.renderItemDecorations(font, s, ix, iy);
                            if (mouseX >= ix && mouseX < ix + 16 && mouseY >= iy && mouseY < iy + 16
                                    && mouseY >= top && mouseY < top + bodyH) {
                                hovered = s;
                            }
                        }
                    }
                    case ChoicesRow cr -> {
                        int per = slotsPerLine(textW);
                        for (int i = 0; i < cr.stacks().size(); i++) {
                            ItemStack s = cr.stacks().get(i);
                            int sx = x + (i % per) * SLOT;
                            int sy = y + (i / per) * SLOT;
                            boolean over = mouseX >= sx && mouseX < sx + ICON && mouseY >= sy && mouseY < sy + ICON
                                    && mouseY >= top && mouseY < top + bodyH;
                            drawSlot(g, sx, sy, i == chosen, over && mustChoose() && !sent);
                            if (!s.isEmpty()) {
                                g.renderItem(s, sx + 1, sy + 1);
                                g.renderItemDecorations(font, s, sx + 1, sy + 1);
                            }
                            if (sy + ICON > top && sy < top + bodyH) choiceHits.add(new Hit(i, sx, sy));
                            if (over) hoveredChoice = s;
                        }
                    }
                    case Gap gap -> { }
                }
            }
            y += h;
        }
        g.disableScissor();
        if (contentH > bodyH) {
            // 스크롤 막대: 본문 오른쪽 가장자리.
            int barH = Math.max(8, bodyH * bodyH / contentH);
            int barY = top + (bodyH - barH) * scroll / (contentH - bodyH);
            g.fill(panelX + panelW - 4, barY, panelX + panelW - 2, barY + barH, 0xA0A0A0C0);
        }
        if (!hovered.isEmpty()) g.renderTooltip(font, hovered, mouseX, mouseY);
        if (!hoveredChoice.isEmpty()) {
            // 툴팁: "아이템 이름 ×개수" + 기본 설명 줄.
            List<Component> tip = new ArrayList<>(getTooltipFromItem(minecraft, hoveredChoice));
            if (!tip.isEmpty()) {
                tip.set(0, tip.get(0).copy().append(Component.literal(" ×" + hoveredChoice.getCount())
                        .withStyle(ChatFormatting.GRAY)));
            }
            g.renderTooltip(font, tip, Optional.empty(), mouseX, mouseY);
        }
    }

    /** Choice slot: dark box with a gray border; 2 px yellow border when picked, light fill on hover. */
    private static void drawSlot(GuiGraphics g, int x, int y, boolean picked, boolean hover) {
        g.fill(x, y, x + ICON, y + ICON, hover ? 0x60FFFFFF : 0x60000000);
        int c = picked ? 0xFFFFFF55 : 0xFF606070;
        int t = picked ? 2 : 1;
        g.fill(x, y, x + ICON, y + t, c);
        g.fill(x, y + ICON - t, x + ICON, y + ICON, c);
        g.fill(x, y, x + t, y + ICON, c);
        g.fill(x + ICON - t, y, x + ICON, y + ICON, c);
    }

    static void box(GuiGraphics g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, BOX_BG);
        g.fill(x1, y1, x2, y1 + 1, BOX_BORDER);
        g.fill(x1, y2 - 1, x2, y2, BOX_BORDER);
        g.fill(x1, y1, x1 + 1, y2, BOX_BORDER);
        g.fill(x2 - 1, y1, x2, y2, BOX_BORDER);
    }
}
