package org.gettserver.gettcore.party.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.network.PartyActionPayload;

/**
 * Players on the server who can be invited (everyone online except this player and the party members), a page at a
 * time, each with a 초대 button. The server decides whether the invitation goes out (already in a party, another
 * invitation waiting, ...) and answers in chat. Client only.
 */
public final class PartyInviteScreen extends Screen {
    private static final int W = 220;
    private static final int H = 214;
    private static final int ROW_H = 22;
    private static final int PER_PAGE = 6;

    private record Row(UUID id, String name) {}

    private final Screen parent;
    private final Set<UUID> sent = new HashSet<>();
    private List<Row> rows = List.of();
    private int page;
    private int seenVersion = -1;
    private int seenCount = -1;

    public PartyInviteScreen(Screen parent) {
        super(Component.translatable("gettcore.party.invite_list.title"));
        this.parent = parent;
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

    private int onlineCount() {
        return minecraft == null || minecraft.getConnection() == null ? 0
                : minecraft.getConnection().getOnlinePlayers().size();
    }

    private List<Row> candidates() {
        List<Row> out = new ArrayList<>();
        if (minecraft == null || minecraft.getConnection() == null || minecraft.player == null) return out;
        UUID me = minecraft.player.getUUID();
        for (PlayerInfo info : minecraft.getConnection().getOnlinePlayers()) {
            UUID id = info.getProfile().getId();
            if (id.equals(me) || ClientParty.isMember(id)) continue;
            out.add(new Row(id, info.getProfile().getName()));
        }
        out.sort(Comparator.comparing(r -> r.name().toLowerCase(java.util.Locale.ROOT)));
        return out;
    }

    @Override
    protected void init() {
        seenVersion = ClientParty.version();
        seenCount = onlineCount();
        rows = candidates();
        int pages = Math.max(1, (rows.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        int x = left();
        int y = top();
        boolean canInvite = ClientParty.isLeader();
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= rows.size()) break;
            Row row = rows.get(idx);
            boolean done = sent.contains(row.id());
            Button b = addRenderableWidget(Button.builder(
                    Component.translatable(done ? "gettcore.party.invite_list.sent" : "gettcore.party.invite_list.invite"),
                    btn -> {
                        PacketDistributor.sendToServer(
                                PartyActionPayload.of(PartyActionPayload.Action.INVITE, row.id()));
                        sent.add(row.id());
                        btn.active = false;
                        btn.setMessage(Component.translatable("gettcore.party.invite_list.sent"));
                    }).bounds(x + W - 10 - 46, y + 30 + i * ROW_H + 2, 46, 16).build());
            b.active = canInvite && !done;
        }
        int by = y + H - 28;
        Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            page--;
            rebuildWidgets();
        }).bounds(x + 8, by, 20, 20).build());
        prev.active = page > 0;
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            page++;
            rebuildWidgets();
        }).bounds(x + 32, by, 20, 20).build());
        next.active = page < pages - 1;
        addRenderableWidget(Button.builder(Component.translatable("gettcore.party.back"), b -> onClose())
                .bounds(x + W - 8 - 70, by, 70, 20).build());
    }

    @Override
    public void tick() {
        // Someone joined or left the server, or the party changed: refresh the list.
        if (seenVersion != ClientParty.version() || seenCount != onlineCount()) rebuildWidgets();
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        PartyScreen.panel(g, font, title, x, y, W, H);
        if (rows.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("gettcore.party.invite_list.empty"), width / 2, y + H / 2 - 10,
                    0xFFC8D0DC);
            return;
        }
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= rows.size()) break;
            Row row = rows.get(idx);
            int ry = y + 30 + i * ROW_H;
            g.fill(x + 8, ry, x + W - 8, ry + ROW_H - 2, PartyScreen.ROW_BG);
            PartyHud.drawFace(g, row.id(), x + 12, ry + 2, 16);
            g.drawString(font, font.plainSubstrByWidth(row.name(), W - 100), x + 34, ry + 6, 0xFFFFFFFF, false);
        }
        int pages = Math.max(1, (rows.size() + PER_PAGE - 1) / PER_PAGE);
        g.drawString(font, (page + 1) + " / " + pages, x + 60, y + H - 22, 0xFF8090A8, false);
    }
}
