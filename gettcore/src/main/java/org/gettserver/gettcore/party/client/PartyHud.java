package org.gettserver.gettcore.party.client;

import java.util.List;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.network.PartyActionPayload;
import org.gettserver.gettcore.network.PartySyncPayload;
import org.gettserver.gettcore.quest.client.HudPosition;
import org.lwjgl.glfw.GLFW;

/**
 * Party HUD. (1) The member list with health bars: party name on top, one row per member; folded = the name only.
 * Where it sits and whether it is folded are set from the party screen. (2) The invitation box: slides in at the
 * bottom right with the inviter, the party name, a bar that runs down with the time left, and 수락 / 거절. It is
 * drawn over every screen too, and the two buttons are clickable whenever the mouse is free (chat open, any menu);
 * the chat message of the invitation has the same two buttons. Client only.
 */
public final class PartyHud {
    public static final ResourceLocation LAYER_ID = GettCore.id("party_hud");

    public static final int WIDTH = 104;
    private static final int HEADER = 13;
    private static final int ROW = 19;
    private static final int HEADER_BG = 0xD01B2C48;
    private static final int BODY_BG = 0x80101824;
    private static final int GOLD = 0xFFE8B84A;

    private static final int INV_W = 150;
    private static final int INV_H = 64;
    private static final int INV_MARGIN = 6;
    private static final int INV_BOTTOM = 22;
    private static final int SLIDE_MS = 250;

    private PartyHud() {}

    public static int height(int members, boolean folded) {
        return folded ? HEADER : HEADER + members * ROW + 2;
    }

    public static void render(GuiGraphics g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        if (mc.screen == null) drawInvite(g, g.guiWidth(), g.guiHeight(), -1, -1, true);
        if (mc.screen != null && !(mc.screen instanceof ChatScreen)) return;
        PartySyncPayload party = ClientParty.party();
        if (!party.inParty()) return;
        boolean folded = ClientParty.folded();
        HudPosition pos = ClientParty.position();
        int h = height(party.members().size(), folded);
        draw(g, party, pos.left(g.guiWidth(), WIDTH), pos.top(g.guiHeight(), h), folded);
    }

    public static void draw(GuiGraphics g, PartySyncPayload party, int left, int top, boolean folded) {
        Font font = Minecraft.getInstance().font;
        List<PartySyncPayload.Entry> members = party.members();
        g.fill(left, top, left + WIDTH, top + HEADER, HEADER_BG);
        String title = font.plainSubstrByWidth(party.name(), WIDTH - 22);
        g.drawCenteredString(font, title, left + WIDTH / 2, top + 3, GOLD);
        g.drawString(font, folded ? "+" : "-", left + WIDTH - 9, top + 3, 0xFFB0B8C8, false);
        if (folded) return;
        g.fill(left, top + HEADER, left + WIDTH, top + height(members.size(), false), BODY_BG);
        for (int i = 0; i < members.size(); i++) {
            PartySyncPayload.Entry e = members.get(i);
            int y = top + HEADER + 2 + i * ROW;
            drawFace(g, e.id(), left + 3, y + 2, 12);
            String name = font.plainSubstrByWidth(e.name(), WIDTH - 22);
            g.drawString(font, name, left + 19, y, e.online() ? (i == 0 ? GOLD : 0xFFFFFFFF) : 0xFF808080, true);
            int barX = left + 19;
            int barW = WIDTH - 23;
            int barY = y + 11;
            g.fill(barX - 1, barY - 1, barX + barW + 1, barY + 5, 0xFF000000);
            g.fill(barX, barY, barX + barW, barY + 4, e.online() ? 0xFF4A1010 : 0xFF303030);
            if (e.online() && e.maxHealth() > 0) {
                float f = Math.max(0f, Math.min(1f, e.health() / e.maxHealth()));
                int w = Math.round(barW * f);
                if (w > 0) g.fill(barX, barY, barX + w, barY + 4, 0xFFD02626);
            }
        }
    }

    public static void drawFace(GuiGraphics g, UUID id, int x, int y, int size) {
        Minecraft mc = Minecraft.getInstance();
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(id);
        PlayerSkin skin = info != null ? info.getSkin() : DefaultPlayerSkin.get(id);
        PlayerFaceRenderer.draw(g, skin, x, y, size);
    }

    // ---- invitation box ----

    private static int inviteLeft(int screenW, ClientParty.Invite invite) {
        float t = Math.min(1f, (Util.getMillis() - invite.shownAt()) / (float) SLIDE_MS);
        float ease = 1f - (1f - t) * (1f - t);
        return screenW - INV_W - INV_MARGIN + Math.round((1f - ease) * (INV_W + INV_MARGIN));
    }

    private static int inviteTop(int screenH) {
        return screenH - INV_H - INV_BOTTOM;
    }

    private static boolean over(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void drawInvite(GuiGraphics g, int screenW, int screenH, int mouseX, int mouseY, boolean hint) {
        ClientParty.Invite invite = ClientParty.invite();
        if (invite == null) return;
        Font font = Minecraft.getInstance().font;
        int x = inviteLeft(screenW, invite);
        int y = inviteTop(screenH);
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(x, y, x + INV_W, y + INV_H, 0xF0101824);
        g.renderOutline(x, y, INV_W, INV_H, 0xFF3A4A68);
        g.drawCenteredString(font, Component.translatable("gettcore.party.invite.title"), x + INV_W / 2, y + 4, 0xFFFFFFFF);
        g.fill(x + 8, y + 14, x + INV_W - 8, y + 15, 0xFF3A4A68);
        drawFace(g, invite.inviterId(), x + 8, y + 19, 16);
        g.drawString(font, font.plainSubstrByWidth(
                Component.translatable("gettcore.party.invite.from", invite.inviter()).getString(), INV_W - 36),
                x + 29, y + 18, 0xFFFFFFFF, false);
        g.drawString(font, font.plainSubstrByWidth(invite.party(), INV_W - 36), x + 29, y + 28, GOLD, false);
        int barW = INV_W - 16;
        g.fill(x + 8, y + 40, x + 8 + barW, y + 42, 0xFF2A2A2A);
        g.fill(x + 8, y + 40, x + 8 + Math.round(barW * invite.remaining(Util.getMillis())), y + 42, 0xFFE0B020);
        int bw = (INV_W - 20) / 2;
        int by = y + 46;
        boolean overAccept = over(mouseX, mouseY, x + 8, by, bw, 13);
        boolean overDecline = over(mouseX, mouseY, x + 12 + bw, by, bw, 13);
        g.fill(x + 8, by, x + 8 + bw, by + 13, overAccept ? 0xFF3C6FB8 : 0xFF24406C);
        g.fill(x + 12 + bw, by, x + 12 + 2 * bw, by + 13, overDecline ? 0xFFB84A3C : 0xFF6C2A24);
        g.drawCenteredString(font, Component.translatable("gettcore.party.invite.accept"), x + 8 + bw / 2, by + 3, 0xFFFFFFFF);
        g.drawCenteredString(font, Component.translatable("gettcore.party.invite.decline"), x + 12 + bw + bw / 2, by + 3,
                0xFFFFFFFF);
        if (hint) {
            Component c = Component.translatable("gettcore.party.invite.hint");
            g.drawString(font, c, x + INV_W - font.width(c), y - 10, 0xFFE0E0E0, true);
        }
        g.pose().popPose();
    }

    /** Over any open screen: the box is drawn on top so it can be answered there. */
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (ClientParty.invite() == null) return;
        drawInvite(event.getGuiGraphics(), event.getScreen().width, event.getScreen().height, event.getMouseX(),
                event.getMouseY(), false);
    }

    public static void onScreenClick(ScreenEvent.MouseButtonPressed.Pre event) {
        ClientParty.Invite invite = ClientParty.invite();
        if (invite == null || event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        int x = inviteLeft(event.getScreen().width, invite);
        int by = inviteTop(event.getScreen().height) + 46;
        int bw = (INV_W - 20) / 2;
        PartyActionPayload.Action action;
        if (over(event.getMouseX(), event.getMouseY(), x + 8, by, bw, 13)) action = PartyActionPayload.Action.ACCEPT;
        else if (over(event.getMouseX(), event.getMouseY(), x + 12 + bw, by, bw, 13)) action = PartyActionPayload.Action.DECLINE;
        else return;
        PacketDistributor.sendToServer(PartyActionPayload.of(action));
        ClientParty.clearInvite();
        event.setCanceled(true);
    }
}
