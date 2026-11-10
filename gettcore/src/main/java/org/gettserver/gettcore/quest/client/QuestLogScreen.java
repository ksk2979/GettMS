package org.gettserver.gettcore.quest.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.network.QuestActionPayload;
import org.gettserver.gettcore.network.QuestHidePayload;
import org.gettserver.gettcore.network.QuestSyncPayload;
import org.gettserver.gettcore.network.QuestSyncPayload.LineView;
import org.gettserver.gettcore.quest.QuestStatus.Phase;
import org.gettserver.gettcore.quest.client.QuestText.LogEntry;
import org.gettserver.gettcore.quest.client.QuestText.Status;

/**
 * Quest list (J), three columns: the lines the player follows (main first, then the started sub lines as
 * "giver · line n/total" with a HUD show/hide toggle ◉/○ on the right), the selected line's quests grouped by chapter
 * (✔ done, ▶ current, gray locked), and the selected quest. Only the current quest shows its progress and can open
 * the full screen (VIEW request with the line id); a finished line shows its end text. Each list scrolls with the
 * wheel. Reads the latest sync live. Client only.
 */
public class QuestLogScreen extends Screen {
    private static final int ROW_H = 12;
    private static final int LINE_W = 124;
    private static final int LIST_W = 132;
    private static final int EYE_W = 12;
    private static final int GREEN = 0xFF55FF55;
    private static final int YELLOW = 0xFFFFFF55;
    private static final int GRAY = 0xFF808080;
    private static final int SEPARATOR = 0x608080A0;

    private QuestSyncPayload seenSync;
    private List<LineView> lines = List.of();
    /** Hide toggles sent but not yet confirmed by a sync (line id → hidden). */
    private final Map<String, Boolean> pendingHide = new HashMap<>();
    private String selectedLine = "";
    private LineView seen;
    private List<LogEntry> entries = List.of();
    private String selectedId = "";
    private int lineScroll;
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
        pw = Math.min(460, width - 20);
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

    /** Re-reads the lines when a new sync arrived; keeps the selected line and quest when they still exist. */
    private void refresh(boolean force) {
        QuestSyncPayload sync = ClientQuestState.get();
        if (!force && sync == seenSync) return;
        seenSync = sync;
        lines = sync.lines();
        // 서버가 반영한 토글(또는 사라진 줄)은 대기 목록에서 뺀다.
        pendingHide.entrySet().removeIf(e -> lines.stream().noneMatch(l -> l.lineId().equals(e.getKey())
                && l.hidden() != e.getValue()));
        lineScroll = Math.min(lineScroll, maxLineScroll());
        LineView line = lines.stream().filter(l -> l.lineId().equals(selectedLine)).findFirst()
                .orElse(lines.isEmpty() ? null : lines.get(0));
        selectLine(line, false);
        hudButton.setMessage(hudLabel());
    }

    /** Shows {@code line}'s quests; a different line resets the quest selection and its scroll. */
    private void selectLine(LineView line, boolean userPick) {
        if (line == null) {
            selectedLine = "";
            seen = null;
            entries = List.of();
            selectedId = "";
            detailsButton.active = false;
            return;
        }
        boolean changed = !line.lineId().equals(selectedLine);
        selectedLine = line.lineId();
        seen = line;
        entries = QuestText.logEntries(line);
        if (changed || userPick) listScroll = 0;
        boolean found = !changed && entries.stream().anyMatch(e -> !e.header() && e.id().equals(selectedId));
        if (!found) selectedId = line.questId();
        listScroll = Math.min(listScroll, maxListScroll());
        detailsButton.active = isCurrent(selectedId);
    }

    private boolean isCurrent(String id) {
        return seen != null && !id.isEmpty() && seen.phase() != Phase.LINE_DONE && id.equals(seen.questId());
    }

    private void requestDetails(Button b) {
        if (!isCurrent(selectedId)) return;
        b.active = false;
        PacketDistributor.sendToServer(new QuestActionPayload(seen.lineId(), selectedId,
                QuestActionPayload.Action.VIEW, -1));
    }

    private boolean hidden(LineView l) {
        return pendingHide.getOrDefault(l.lineId(), l.hidden());
    }

    /** Sub lines that are still running get the HUD toggle (the main line and finished lines do not). */
    private static boolean hasToggle(LineView l) {
        return !l.giver().isEmpty() && l.phase() != Phase.LINE_DONE;
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

    private int top() {
        return py + 20;
    }

    private int bottom() {
        return py + ph - 30;
    }

    private int lineX() {
        return px + 6;
    }

    private int listX() {
        return lineX() + LINE_W + 6;
    }

    private int detailX() {
        return listX() + LIST_W + 8;
    }

    private int maxLineScroll() {
        return Math.max(0, lines.size() * ROW_H - (bottom() - top()));
    }

    private int maxListScroll() {
        return Math.max(0, entries.size() * ROW_H - (bottom() - top()));
    }

    private boolean inColumn(double mx, double my, int x, int w) {
        return mx >= x && mx < x + w && my >= top() && my < bottom();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && inColumn(mouseX, mouseY, lineX(), LINE_W)) {
            int i = (int) ((mouseY - top() + lineScroll) / ROW_H);
            if (i >= 0 && i < lines.size()) {
                LineView l = lines.get(i);
                if (hasToggle(l) && mouseX >= lineX() + LINE_W - EYE_W) {
                    boolean hide = !hidden(l);
                    pendingHide.put(l.lineId(), hide);
                    PacketDistributor.sendToServer(new QuestHidePayload(l.lineId(), hide));
                } else {
                    selectLine(l, true);
                }
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                return true;
            }
        }
        if (button == 0 && inColumn(mouseX, mouseY, listX(), LIST_W)) {
            int i = (int) ((mouseY - top() + listScroll) / ROW_H);
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
        int step = (int) (scrollY * ROW_H * 2);
        if (mouseX < lineX() + LINE_W) {
            lineScroll = Math.max(0, Math.min(maxLineScroll(), lineScroll - step));
            return true;
        }
        if (mouseX < listX() + LIST_W) {
            listScroll = Math.max(0, Math.min(maxListScroll(), listScroll - step));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        QuestScreen.box(g, px, py, px + pw, py + ph);
        g.fill(lineX() + LINE_W + 2, top(), lineX() + LINE_W + 3, bottom(), SEPARATOR);
        g.fill(listX() + LIST_W + 2, top(), listX() + LIST_W + 3, bottom(), SEPARATOR);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Component title = seen == null || seen.lineName().isBlank() ? getTitle() : Component.literal(seen.lineName());
        g.drawString(font, title, px + 8, py + 6, YELLOW, false);
        if (lines.isEmpty()) {
            g.drawString(font, Component.translatable("gettcore.quest.log.empty"), px + 8, top() + 4, GRAY, false);
            return;
        }
        Component tip = renderLines(g, mouseX, mouseY);
        renderList(g, mouseX, mouseY);
        renderDetails(g);
        if (tip != null) g.renderTooltip(font, tip, mouseX, mouseY);
    }

    /** Line column; returns the toggle tooltip when the mouse is over a toggle. */
    private Component renderLines(GuiGraphics g, int mouseX, int mouseY) {
        int lx = lineX();
        int top = top();
        int bottom = bottom();
        Component tip = null;
        g.enableScissor(lx, top, lx + LINE_W, bottom);
        int y = top - lineScroll;
        for (LineView l : lines) {
            if (y + ROW_H >= top && y <= bottom) {
                boolean rowHover = mouseX >= lx && mouseX < lx + LINE_W && mouseY >= y && mouseY < y + ROW_H
                        && mouseY >= top && mouseY < bottom;
                boolean toggle = hasToggle(l);
                boolean eyeHover = rowHover && toggle && mouseX >= lx + LINE_W - EYE_W;
                if (l.lineId().equals(selectedLine)) g.fill(lx, y, lx + LINE_W, y + ROW_H, 0x50FFFFFF);
                else if (rowHover && !eyeHover) g.fill(lx, y, lx + LINE_W, y + ROW_H, 0x28FFFFFF);
                int textW = LINE_W - 4 - (toggle ? EYE_W : 0);
                g.drawString(font, trim(lineLabel(l), textW), lx + 2, y + 2, lineColor(l), false);
                if (toggle) {
                    boolean hid = hidden(l);
                    if (eyeHover) g.fill(lx + LINE_W - EYE_W, y, lx + LINE_W, y + ROW_H, 0x40FFFFFF);
                    String eye = hid ? "○" : "◉";
                    g.drawString(font, eye, lx + LINE_W - EYE_W + (EYE_W - font.width(eye)) / 2, y + 2,
                            hid ? GRAY : 0xFFFFFFFF, false);
                    if (eyeHover) {
                        tip = Component.translatable(hid ? "gettcore.quest.log.hud_hidden"
                                        : "gettcore.quest.log.hud_shown")
                                .append(Component.literal(" — ").withStyle(ChatFormatting.GRAY))
                                .append(Component.translatable("gettcore.quest.log.hud_toggle_tip")
                                        .withStyle(ChatFormatting.GRAY));
                    }
                }
            }
            y += ROW_H;
        }
        g.disableScissor();
        return tip;
    }

    /** "메인 퀘스트" (or the main line's name) / "giver · line n/total". */
    private static String lineLabel(LineView l) {
        if (l.giver().isEmpty()) {
            return l.lineName().isBlank() ? Component.translatable("gettcore.quest.log.main").getString()
                    : l.lineName();
        }
        int done = (int) l.quests().stream().filter(q -> l.completedIds().contains(q.id())).count();
        return l.giver() + " · " + l.lineName() + " " + done + "/" + l.quests().size();
    }

    private static int lineColor(LineView l) {
        if (l.phase() == Phase.LINE_DONE) return GREEN;
        return l.giver().isEmpty() ? YELLOW : 0xFFAAFF55;
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        int lx = listX();
        int top = top();
        int bottom = bottom();
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
        if (seen == null) return;
        int x = detailX();
        int w = px + pw - 8 - x;
        int y = top();
        int bottom = bottom();
        if (w < 40) return;
        if (seen.phase() == Phase.LINE_DONE && !seen.giver().isEmpty()) {
            // 끝난 서브 줄: endText 를 먼저 보여 준다.
            var end = QuestText.phaseLine(seen.phase(), seen.npc(), seen.endText());
            if (end.isPresent()) {
                y = drawWrapped(g, end.get().copy().withStyle(ChatFormatting.GREEN), x, y, w, bottom, 0xFFFFFFFF)
                        + 6;
            }
        }
        LogEntry sel = entries.stream().filter(e -> !e.header() && e.id().equals(selectedId)).findFirst()
                .orElse(null);
        if (sel == null) {
            // 메인 라인 완료: 다음 퀘스트 준비 중 문구 (서브 줄은 위에서 이미 표시).
            if (seen.giver().isEmpty()) {
                var line = QuestText.phaseLine(seen.phase(), seen.npc(), seen.endText());
                if (line.isPresent()) drawWrapped(g, line.get(), x, y, w, bottom, 0xFFFFFFFF);
            }
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
