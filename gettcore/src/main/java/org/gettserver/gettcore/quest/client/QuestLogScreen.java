package org.gettserver.gettcore.quest.client;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.network.QuestActionPayload;
import org.gettserver.gettcore.network.QuestSyncPayload;
import org.gettserver.gettcore.quest.QuestStatus.Phase;
import org.gettserver.gettcore.quest.client.QuestText.LogEntry;
import org.gettserver.gettcore.quest.client.QuestText.Status;

/**
 * Quest list (J): the main line grouped by chapter on the left (✔ done, ▶ current, gray locked), the selected quest
 * on the right. Only the current quest shows its progress and can open the full screen (VIEW request); done and locked
 * quests show their title only. Reads the latest sync live. Client only.
 */
public class QuestLogScreen extends Screen {
    private static final int ROW_H = 12;
    private static final int LIST_W = 140;
    private static final int GREEN = 0xFF55FF55;
    private static final int YELLOW = 0xFFFFFF55;
    private static final int GRAY = 0xFF808080;

    private QuestSyncPayload seen;
    private List<LogEntry> entries = List.of();
    private String selectedId = "";
    private int listScroll;
    private int px;
    private int py;
    private int pw;
    private int ph;
    private Button detailsButton;
    private Button hudButton;

    public QuestLogScreen() {
        super(Component.translatable("gettcore.quest.log.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        pw = Math.min(380, width - 20);
        ph = Math.min(240, height - 20);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        int by = py + ph - 25;
        hudButton = addRenderableWidget(Button.builder(hudLabel(), b -> {
            QuestKeys.toggleHud(minecraft);
            b.setMessage(hudLabel());
        }).bounds(px + 6, by, 120, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gettcore.quest.screen.close"), b -> onClose())
                .bounds(px + pw - 66, by, 60, 20).build());
        detailsButton = addRenderableWidget(Button.builder(Component.translatable("gettcore.quest.log.details"),
                b -> requestDetails(b)).bounds(px + pw - 66 - 84, by, 80, 20).build());
        refresh(true);
    }

    private static Component hudLabel() {
        return Component.translatable(ClientQuestState.hudVisible() ? "gettcore.quest.log.hud_on"
                : "gettcore.quest.log.hud_off");
    }

    /** Rebuilds the rows when a new sync arrived; keeps the selection if that quest still exists. */
    private void refresh(boolean force) {
        QuestSyncPayload s = ClientQuestState.get();
        if (!force && s == seen) return;
        seen = s;
        entries = QuestText.logEntries(s);
        boolean found = entries.stream().anyMatch(e -> !e.header() && e.id().equals(selectedId));
        if (!found) selectedId = s.questId();
        detailsButton.active = isCurrent(selectedId);
        hudButton.setMessage(hudLabel());
    }

    private boolean isCurrent(String id) {
        return !id.isEmpty() && seen.phase() != Phase.LINE_DONE && id.equals(seen.questId());
    }

    private void requestDetails(Button b) {
        if (!isCurrent(selectedId)) return;
        b.active = false;
        PacketDistributor.sendToServer(new QuestActionPayload(selectedId, QuestActionPayload.Action.VIEW));
    }

    @Override
    public void tick() {
        refresh(false);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (QuestKeys.QUEST_LOG.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private int listTop() {
        return py + 20;
    }

    private int listBottom() {
        return py + ph - 30;
    }

    private int maxScroll() {
        return Math.max(0, entries.size() * ROW_H - (listBottom() - listTop()));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int lx = px + 6;
        if (button == 0 && mouseX >= lx && mouseX < lx + LIST_W && mouseY >= listTop() && mouseY < listBottom()) {
            int i = (int) ((mouseY - listTop() + listScroll) / ROW_H);
            if (i >= 0 && i < entries.size() && !entries.get(i).header()) {
                selectedId = entries.get(i).id();
                detailsButton.active = isCurrent(selectedId);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < px + 6 + LIST_W) {
            listScroll = Math.max(0, Math.min(maxScroll(), listScroll - (int) (scrollY * ROW_H * 2)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        QuestScreen.box(g, px, py, px + pw, py + ph);
        g.fill(px + 6 + LIST_W + 2, listTop(), px + 6 + LIST_W + 3, listBottom(), 0x608080A0);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Component title = seen.lineName().isBlank() ? getTitle() : Component.literal(seen.lineName());
        g.drawString(font, title, px + 8, py + 6, YELLOW, false);
        if (seen.lineId().isEmpty()) {
            g.drawString(font, Component.translatable("gettcore.quest.log.empty"), px + 8, listTop() + 4, GRAY,
                    false);
            return;
        }
        renderList(g, mouseX, mouseY);
        renderDetails(g);
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        int lx = px + 6;
        int top = listTop();
        int bottom = listBottom();
        g.enableScissor(lx, top, lx + LIST_W, bottom);
        int y = top - listScroll;
        for (LogEntry e : entries) {
            if (y + ROW_H >= top && y <= bottom) {
                if (e.header()) {
                    g.drawString(font, trim(e.chapter(), LIST_W - 4), lx + 2, y + 2, 0xFFAAAAFF, false);
                } else {
                    boolean hover = mouseX >= lx && mouseX < lx + LIST_W && mouseY >= y && mouseY < y + ROW_H
                            && mouseY >= top && mouseY < bottom;
                    if (e.id().equals(selectedId)) g.fill(lx, y, lx + LIST_W, y + ROW_H, 0x50FFFFFF);
                    else if (hover) g.fill(lx, y, lx + LIST_W, y + ROW_H, 0x28FFFFFF);
                    String mark = switch (e.status()) {
                        case DONE -> "✔ ";
                        case CURRENT -> "▶ ";
                        case LOCKED -> "· ";
                    };
                    g.drawString(font, trim(mark + e.title(), LIST_W - 10), lx + 8, y + 2, color(e.status()), false);
                }
            }
            y += ROW_H;
        }
        g.disableScissor();
    }

    private void renderDetails(GuiGraphics g) {
        int x = px + 6 + LIST_W + 8;
        int w = px + pw - 8 - x;
        int y = listTop();
        int bottom = listBottom();
        if (w < 40) return;
        LogEntry sel = entries.stream().filter(e -> !e.header() && e.id().equals(selectedId)).findFirst()
                .orElse(null);
        if (sel == null) {
            // 라인 완료: 다음 퀘스트 준비 중 문구.
            var line = QuestText.phaseLine(seen.phase(), seen.npc(), seen.endText());
            if (line.isPresent()) drawWrapped(g, line.get(), x, y, w, bottom, 0xFFFFFFFF);
            return;
        }
        y = drawWrapped(g, QuestText.headerLine(sel.chapter(), sel.title()), x, y, w, bottom, 0xFFFFFFFF) + 4;
        switch (sel.status()) {
            case DONE -> drawWrapped(g, Component.translatable("gettcore.quest.log.done")
                    .withStyle(ChatFormatting.GREEN), x, y, w, bottom, 0xFFFFFFFF);
            case LOCKED -> drawWrapped(g, Component.translatable("gettcore.quest.log.locked")
                    .withStyle(ChatFormatting.GRAY), x, y, w, bottom, 0xFFFFFFFF);
            case CURRENT -> {
                for (Component line : QuestText.progressLines(seen, QuestHud::label)) {
                    y = drawWrapped(g, line, x, y, w, bottom, 0xFFFFFFFF) + 2;
                }
            }
        }
    }

    /** Draws wrapped text and returns the y below it (stops at {@code bottom}). */
    private int drawWrapped(GuiGraphics g, Component c, int x, int y, int w, int bottom, int color) {
        for (FormattedCharSequence s : font.split(c, w)) {
            if (y + font.lineHeight > bottom) break;
            g.drawString(font, s, x, y, color, false);
            y += font.lineHeight + 1;
        }
        return y;
    }

    private String trim(String text, int maxW) {
        if (font.width(text) <= maxW) return text;
        return font.plainSubstrByWidth(text, maxW - font.width("…")) + "…";
    }

    private static int color(Status s) {
        return switch (s) {
            case DONE -> GREEN;
            case CURRENT -> YELLOW;
            case LOCKED -> GRAY;
        };
    }
}
