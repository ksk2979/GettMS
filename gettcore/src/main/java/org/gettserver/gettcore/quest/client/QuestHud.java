package org.gettserver.gettcore.quest.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import net.neoforged.neoforge.client.extensions.common.IClientMobEffectExtensions;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.network.QuestSyncPayload;
import org.gettserver.gettcore.network.QuestSyncPayload.ObjState;

/**
 * Tracker HUD at the top right: "[장] 제목" and the objectives (or where to go next). Registered as a GUI layer;
 * hidden with F1, while a screen other than chat is open, and when switched off with K. Also resolves objective
 * target names for the quest screens. Client only.
 */
public final class QuestHud {
    public static final ResourceLocation LAYER_ID = GettCore.id("quest_tracker");
    private static final int MAX_WIDTH = 180;
    private static final int PAD = 4;
    private static final int MARGIN = 4;
    private static final int LINE_GAP = 1;
    private static final int BG = 0x80000000;

    private QuestHud() {}

    public static void render(GuiGraphics g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        if (mc.screen != null && !(mc.screen instanceof ChatScreen)) return;
        if (!ClientQuestState.hudVisible()) return;
        List<Component> lines = QuestText.hudLines(ClientQuestState.get(), QuestHud::label);
        if (lines.isEmpty()) return;

        Font font = mc.font;
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (Component line : lines) wrapped.addAll(font.split(line, MAX_WIDTH - 2 * PAD));
        int textW = 0;
        for (FormattedCharSequence s : wrapped) textW = Math.max(textW, font.width(s));
        int w = textW + 2 * PAD;
        int h = wrapped.size() * (font.lineHeight + LINE_GAP) - LINE_GAP + 2 * PAD;
        int x = g.guiWidth() - w - MARGIN;
        int y = MARGIN + effectsOffset(mc);
        g.fill(x, y, x + w, y + h, BG);
        int ly = y + PAD;
        for (FormattedCharSequence s : wrapped) {
            g.drawString(font, s, x + PAD, ly, 0xFFFFFFFF, true);
            ly += font.lineHeight + LINE_GAP;
        }
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
