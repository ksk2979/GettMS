package org.gettserver.gettcore.party.client;

import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.network.PartyActionPayload;
import org.gettserver.gettcore.network.PartySyncPayload;
import org.gettserver.gettcore.party.PartyBook;

/**
 * The party window (opened from the button in the game menu). Without a party: "파티 생성". In a party: its name,
 * the members with their health, 파티 탈퇴 / 파티 초대 (leader only), a 추방 button per member for the leader, and
 * the two settings of the member health bars (fold, move). Rebuilds itself when a sync arrives. Client only.
 */
public final class PartyScreen extends Screen {
    static final int PANEL_BG = 0xF00E1622;
    static final int PANEL_BORDER = 0xFF3A4A68;
    static final int BAND = 0xFF1B2C48;
    static final int ROW_BG = 0xFF141E2E;
    static final int GOLD = 0xFFE8B84A;

    private static final int W = 250;
    private static final int H = 206;
    private static final int ROW_H = 24;

    private int seenVersion = -1;

    public PartyScreen() {
        super(Component.translatable("gettcore.party.title"));
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

    /** Dark panel with a title band, shared by the party screens. */
    static void panel(GuiGraphics g, Font font, Component title, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL_BG);
        g.renderOutline(x, y, w, h, PANEL_BORDER);
        g.fill(x + 6, y + 6, x + w - 6, y + 22, BAND);
        g.drawCenteredString(font, title, x + w / 2, y + 10, 0xFFFFFFFF);
    }

    @Override
    protected void init() {
        seenVersion = ClientParty.version();
        PartySyncPayload party = ClientParty.party();
        int x = left();
        int y = top();
        if (!party.inParty()) {
            addRenderableWidget(Button.builder(Component.translatable("gettcore.party.create"),
                            b -> minecraft.setScreen(new PartyNameScreen(this)))
                    .bounds(x + (W - 120) / 2, y + H - 34, 120, 20).build());
            return;
        }
        boolean leader = ClientParty.isLeader();
        UUID me = minecraft.player == null ? null : minecraft.player.getUUID();
        List<PartySyncPayload.Entry> members = party.members();
        if (leader) {
            for (int i = 0; i < members.size(); i++) {
                PartySyncPayload.Entry e = members.get(i);
                if (e.id().equals(me)) continue;
                addRenderableWidget(Button.builder(Component.translatable("gettcore.party.kick"),
                                b -> PacketDistributor.sendToServer(
                                        PartyActionPayload.of(PartyActionPayload.Action.KICK, e.id())))
                        .bounds(x + W - 10 - 36, y + 44 + i * ROW_H + 3, 36, 16).build());
            }
        }
        int bw = (W - 24) / 2;
        int by = y + H - 52;
        addRenderableWidget(Button.builder(Component.translatable("gettcore.party.leave"), b -> {
            PacketDistributor.sendToServer(PartyActionPayload.of(PartyActionPayload.Action.LEAVE));
        }).bounds(x + 8, by, bw, 20).build());
        Button invite = addRenderableWidget(Button.builder(Component.translatable("gettcore.party.invite"),
                        b -> minecraft.setScreen(new PartyInviteScreen(this)))
                .bounds(x + 16 + bw, by, bw, 20).build());
        invite.active = leader && members.size() < PartyBook.MAX_MEMBERS;
        addRenderableWidget(Button.builder(foldLabel(), b -> {
            ClientParty.saveFolded(!ClientParty.folded());
            b.setMessage(foldLabel());
        }).bounds(x + 8, by + 24, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gettcore.party.hud.move"),
                        b -> minecraft.setScreen(new PartyHudMoveScreen(this)))
                .bounds(x + 16 + bw, by + 24, bw, 20).build());
    }

    private static Component foldLabel() {
        return Component.translatable(ClientParty.folded() ? "gettcore.party.hud.unfold" : "gettcore.party.hud.fold");
    }

    @Override
    public void tick() {
        if (seenVersion != ClientParty.version()) rebuildWidgets();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        panel(g, font, title, x, y, W, H);
        PartySyncPayload party = ClientParty.party();
        if (!party.inParty()) {
            g.drawCenteredString(font, Component.translatable("gettcore.party.none"), width / 2, y + H / 2 - 16, 0xFFC8D0DC);
            return;
        }
        List<PartySyncPayload.Entry> members = party.members();
        g.drawString(font, party.name(), x + 12, y + 30, GOLD, false);
        String count = members.size() + "/" + PartyBook.MAX_MEMBERS;
        g.drawString(font, count, x + W - 12 - font.width(count), y + 30, 0xFFFFFFFF, false);
        for (int i = 0; i < PartyBook.MAX_MEMBERS; i++) {
            int ry = y + 44 + i * ROW_H;
            g.fill(x + 8, ry, x + W - 8, ry + ROW_H - 2, ROW_BG);
            if (i >= members.size()) continue;
            PartySyncPayload.Entry e = members.get(i);
            PartyHud.drawFace(g, e.id(), x + 12, ry + 3, 16);
            g.drawString(font, font.plainSubstrByWidth(e.name(), 96), x + 34, ry + 3,
                    e.online() ? 0xFFFFFFFF : 0xFF808080, false);
            Component sub = i == 0 ? Component.translatable("gettcore.party.leader")
                    : Component.translatable(e.online() ? "gettcore.party.member" : "gettcore.party.offline_mark");
            g.drawString(font, sub, x + 34, ry + 12, i == 0 ? GOLD : 0xFF8090A8, false);
            if (e.online()) {
                String hp = Math.round(e.health()) + " / " + Math.round(e.maxHealth());
                g.drawString(font, hp, x + W - 54 - font.width(hp), ry + 7, 0xFFE06060, false);
            }
        }
    }
}
