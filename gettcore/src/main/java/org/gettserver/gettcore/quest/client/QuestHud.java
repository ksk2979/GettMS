package org.gettserver.gettcore.quest.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.extensions.common.IClientMobEffectExtensions;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.network.QuestSyncPayload;
import org.gettserver.gettcore.network.QuestSyncPayload.ObjState;
import org.gettserver.gettcore.quest.QuestStatus.Phase;

/**
 * Tracker HUD (top right by default, movable with Ctrl+-). One box: the main line's "[장] 제목" and objectives pinned on
 * top, then (when any shows) a separator and each started, not hidden, not finished sub line as "[NPC] 제목" plus its
 * objectives. The box is at most {@code hud.maxLines} rows; only the sub area scrolls, with the mouse wheel while the
 * chat screen is open and the mouse is over the box. Registered as a GUI layer; hidden with F1, while a screen other
 * than chat is open, and when switched off with K. Also resolves objective target names for the quest screens.
 * Client only.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class QuestHud {
    public static final ResourceLocation LAYER_ID = GettCore.id("quest_tracker");
    private static final int MAX_WIDTH = 180;
    private static final int PAD = 4;
    private static final int LINE_GAP = 1;
    private static final int BG = 0x80000000;
    private static final int SEPARATOR = 0x60FFFFFF;
    private static final int BAR_TRACK = 0x40FFFFFF;
    private static final int BAR = 0xC0FFFFFF;
    private static final int BAR_W = 2;

    /** First sub-area row shown. */
    private static int scroll;
    /** Screen rectangle of the box drawn last frame, or null when nothing was drawn (scroll hit test). */
    private static Box lastBox;
    private static int lastSubRows;
    private static int lastAvailable;

    private QuestHud() {}

    /** Screen rectangle of a drawn box (GUI coordinates). */
    public record Box(int x, int y, int width, int height) {
        public boolean contains(double mx, double my) {
            return mx >= x && mx < x + width && my >= y && my < y + height;
        }
    }

    public static void render(GuiGraphics g, DeltaTracker deltaTracker) {
        lastBox = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        if (mc.screen != null && !(mc.screen instanceof ChatScreen)) return;
        if (!ClientQuestState.hudVisible()) return;
        List<Component> main = mainLines();
        List<Component> subs = subLines();
        if (main.isEmpty() && subs.isEmpty()) return;

        Layout box = layout(mc.font, main, subs, ClientQuestState.maxLines(), scroll, false);
        scroll = box.scroll();
        lastSubRows = box.sub().size();
        lastAvailable = box.subRows();
        HudPosition pos = ClientQuestState.hudPosition();
        int x = pos.left(g.guiWidth(), box.width());
        int y = pos.top(g.guiHeight(), box.height());
        // 기본 위치(오른쪽 위)일 때만 효과 아이콘 아래로 비켜 그린다. 사용자가 옮긴 뒤엔 그 자리 그대로.
        if (pos.isDefault()) y = Math.max(0, Math.min(y + effectsOffset(mc), g.guiHeight() - box.height()));
        draw(g, box, x, y);
        lastBox = new Box(x, y, box.width(), box.height());
    }

    /** Where the box was drawn last frame, if it was. */
    public static Optional<Box> lastBox() {
        return Optional.ofNullable(lastBox);
    }

    /** Moves the sub area by {@code delta} rows (positive = down), kept in range. */
    public static void scrollBy(int delta) {
        scroll = HudScroll.clampScroll(scroll + delta, lastSubRows, lastAvailable);
    }

    /** Wheel over the box while chat is open scrolls the sub area; elsewhere (or chat closed) vanilla handles it. */
    @SubscribeEvent
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!(event.getScreen() instanceof ChatScreen)) return;
        double dy = event.getScrollDeltaY();
        Box box = lastBox;
        if (dy == 0 || box == null || !box.contains(event.getMouseX(), event.getMouseY())) return;
        scrollBy(-(int) Math.signum(dy));
        event.setCanceled(true);
    }

    /**
     * Wrapped rows of the box: {@code main} (pinned), {@code sub} (all sub rows; {@code subRows} of them shown from
     * {@code scroll}) and the box size. A separator row sits between them when both are non-empty.
     */
    public record Layout(List<FormattedCharSequence> main, List<FormattedCharSequence> sub, int subRows, int scroll,
                         int width, int height) {
        public boolean separator() {
            return !main.isEmpty() && !sub.isEmpty();
        }
    }

    /**
     * Lays the box out for at most {@code maxLines} rows; the main block is never cut. With no sub rows this is the
     * plain main box. {@code fill} pads the sub area to its full height (move screen preview).
     */
    public static Layout layout(Font font, List<Component> main, List<Component> subs, int maxLines, int scroll,
                                boolean fill) {
        List<FormattedCharSequence> mainRows = wrap(font, main);
        List<FormattedCharSequence> subRows = wrap(font, subs);
        int textW = 0;
        for (FormattedCharSequence s : mainRows) textW = Math.max(textW, font.width(s));
        for (FormattedCharSequence s : subRows) textW = Math.max(textW, font.width(s));
        int shown = 0;
        int sc = 0;
        if (!subRows.isEmpty()) {
            // 메인이 줄 수를 다 써도 서브 한 줄은 남겨 스크롤로 볼 수 있게 한다.
            int available = mainRows.isEmpty() ? maxLines : HudScroll.available(maxLines, mainRows.size());
            available = Math.max(1, available);
            sc = HudScroll.clampScroll(scroll, subRows.size(), available);
            shown = fill ? available : Math.min(available, subRows.size());
        }
        int rows = mainRows.size() + (!mainRows.isEmpty() && !subRows.isEmpty() ? 1 : 0) + shown;
        int w = textW + 2 * PAD;
        int h = rows * (font.lineHeight + LINE_GAP) - LINE_GAP + 2 * PAD;
        return new Layout(mainRows, subRows, shown, sc, w, h);
    }

    private static List<FormattedCharSequence> wrap(Font font, List<Component> lines) {
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (Component line : lines) wrapped.addAll(font.split(line, MAX_WIDTH - 2 * PAD));
        return wrapped;
    }

    /** Draws the HUD box with its top-left corner at (left, top). Shared by the layer and the move screen. */
    public static void draw(GuiGraphics g, Layout box, int left, int top) {
        Font font = Minecraft.getInstance().font;
        int row = font.lineHeight + LINE_GAP;
        g.fill(left, top, left + box.width(), top + box.height(), BG);
        int ly = top + PAD;
        for (FormattedCharSequence s : box.main()) {
            g.drawString(font, s, left + PAD, ly, 0xFFFFFFFF, true);
            ly += row;
        }
        if (box.separator()) {
            int sy = ly + (font.lineHeight - 1) / 2;
            g.fill(left + PAD, sy, left + box.width() - PAD, sy + 1, SEPARATOR);
            ly += row;
        }
        int areaTop = ly;
        for (int i = 0; i < box.subRows(); i++) {
            int idx = box.scroll() + i;
            if (idx < box.sub().size()) g.drawString(font, box.sub().get(idx), left + PAD, ly, 0xFFFFFFFF, true);
            ly += row;
        }
        if (box.scroll() > 0 && box.subRows() > 0) {
            // 스크롤 위치가 맨 위가 아니면 상자 오른쪽에 얇은 위치 막대.
            int areaH = box.subRows() * row - LINE_GAP;
            int total = box.sub().size();
            int thumbH = Math.max(4, areaH * box.subRows() / total);
            int maxScroll = Math.max(1, total - box.subRows());
            int thumbY = areaTop + (areaH - thumbH) * box.scroll() / maxScroll;
            int bx = left + box.width() - BAR_W;
            g.fill(bx, areaTop, bx + BAR_W, areaTop + areaH, BAR_TRACK);
            g.fill(bx, thumbY, bx + BAR_W, thumbY + thumbH, BAR);
        }
    }

    /** HUD lines of the main line (the pinned block). */
    private static List<Component> mainLines() {
        return ClientQuestState.get().main().map(l -> QuestText.hudLines(l, QuestHud::label)).orElse(List.of());
    }

    /** "[NPC] 제목" + progress lines of every shown sub line (started, not hidden, not finished), in sync order. */
    private static List<Component> subLines() {
        List<Component> out = new ArrayList<>();
        for (QuestSyncPayload.LineView l : ClientQuestState.get().lines()) {
            if (l.giver().isEmpty() || l.hidden() || l.phase() == Phase.LINE_DONE) continue;
            String head = l.title().isBlank() ? l.lineName() : l.title();
            out.add(QuestText.headerLine(l.giver(), head));
            out.addAll(QuestText.progressLines(l, QuestHud::label));
        }
        return out;
    }

    /** The box the move screen shows: real lines, or samples where there are none, padded to {@code maxLines}. */
    public static Layout previewLayout(Font font, int maxLines) {
        List<Component> main = mainLines();
        if (main.isEmpty()) {
            main = List.of(
                    Component.translatable("gettcore.quest.hud.sample_title").withStyle(ChatFormatting.YELLOW),
                    Component.translatable("gettcore.quest.hud.sample_objective").withStyle(ChatFormatting.WHITE));
        }
        List<Component> subs = subLines();
        if (subs.isEmpty()) {
            subs = List.of(
                    Component.translatable("gettcore.quest.hud.sample_sub_title").withStyle(ChatFormatting.YELLOW),
                    Component.translatable("gettcore.quest.hud.sample_sub_objective").withStyle(ChatFormatting.WHITE));
        }
        return layout(font, main, subs, maxLines, 0, true);
    }

    /** Space taken by the vanilla effect icons at the top right: one row for beneficial, two with harmful ones. */
    private static int effectsOffset(Minecraft mc) {
        boolean beneficial = false;
        boolean harmful = false;
        for (MobEffectInstance e : mc.player.getActiveEffects()) {
            if (!e.showIcon() || !IClientMobEffectExtensions.of(e).isVisibleInGui(e)) continue;
            if (e.getEffect().value().isBeneficial()) beneficial = true;
            else harmful = true;
        }
        int offset = harmful ? 52 : beneficial ? 26 : 0;
        if (offset > 0 && mc.isDemo()) offset += 15;
        return offset;
    }

    /** Objective label with client-resolved names (configured label first). */
    public static Component label(ObjState o) {
        if (!o.label().isBlank()) return Component.literal(o.label());
        return QuestText.objectiveLabel(o, targetName(o));
    }

    private static Component targetName(ObjState o) {
        String t = o.target();
        return switch (o.type()) {
            case "collect" -> t.startsWith("#") ? tagName("item", t.substring(1)) : itemName(t);
            case "kill" -> t.startsWith("#") ? tagName("entity_type", t.substring(1)) : entityName(t);
            case "advancement" -> advancementTitle(t);
            default -> Component.literal(t);
        };
    }

    private static Component itemName(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return Component.literal(id);
        return BuiltInRegistries.ITEM.getOptional(rl).<Component>map(i -> i.getDescription())
                .orElse(Component.literal(id));
    }

    private static Component entityName(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return Component.literal(id);
        return BuiltInRegistries.ENTITY_TYPE.getOptional(rl).<Component>map(e -> e.getDescription())
                .orElse(Component.literal(id));
    }

    /** "tag.item.minecraft.logs" style translation when the resource pack has one, else "#id". */
    private static Component tagName(String kind, String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl != null) {
            String key = "tag." + kind + "." + rl.getNamespace() + "." + rl.getPath().replace('/', '.');
            if (I18n.exists(key)) return Component.translatable(key);
        }
        return Component.literal("#" + id);
    }

    /** Advancement title known to the client (hidden, unrevealed ones are not sent), else the id. */
    private static Component advancementTitle(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        if (rl != null && conn != null) {
            var holder = conn.getAdvancements().get(rl);
            if (holder != null) {
                Optional<Component> title = holder.value().display().map(d -> d.getTitle());
                if (title.isPresent()) return title.get();
            }
        }
        return Component.literal(id);
    }
}
