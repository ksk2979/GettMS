package org.gettserver.gettcore.party.client;

import java.util.Locale;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.gettserver.gettcore.network.PartyInvitePayload;
import org.gettserver.gettcore.network.PartySyncPayload;
import org.gettserver.gettcore.quest.client.ClientQuestState;
import org.gettserver.gettcore.quest.client.HudPosition;

/**
 * What this client knows about its party: the last {@link PartySyncPayload}, the invitation on screen, and the
 * settings of the member health bars (position, folded) kept in gettcore-client.properties. Client only.
 */
public final class ClientParty {
    public static final HudPosition DEFAULT_POSITION = new HudPosition(HudPosition.Anchor.TOP_LEFT, 4, 70);
    private static final String KEY_POSITION = "partyHud.position";
    private static final String KEY_FOLDED = "partyHud.folded";

    /** A waiting invitation; times are {@link Util#getMillis()}. */
    public record Invite(String party, String inviter, UUID inviterId, long shownAt, long endsAt) {
        /** 1 = just arrived, 0 = time is up. */
        public float remaining(long now) {
            long total = endsAt - shownAt;
            return total <= 0 ? 0 : Math.max(0f, Math.min(1f, (endsAt - now) / (float) total));
        }
    }

    private static PartySyncPayload party = PartySyncPayload.none();
    /** Goes up with every sync, so open screens know to rebuild. */
    private static int version;
    private static Invite invite;
    private static HudPosition position;
    private static Boolean folded;

    private ClientParty() {}

    public static void set(PartySyncPayload payload) {
        party = payload;
        version++;
    }

    public static PartySyncPayload party() {
        return party;
    }

    public static int version() {
        return version;
    }

    public static boolean isLeader() {
        Minecraft mc = Minecraft.getInstance();
        return party.inParty() && mc.player != null && party.members().get(0).id().equals(mc.player.getUUID());
    }

    public static boolean isMember(UUID id) {
        for (PartySyncPayload.Entry e : party.members()) if (e.id().equals(id)) return true;
        return false;
    }

    public static void onInvite(PartyInvitePayload payload) {
        if (payload.ticks() <= 0) {
            invite = null;
            return;
        }
        long now = Util.getMillis();
        invite = new Invite(payload.party(), payload.inviter(), payload.inviterId(), now, now + payload.ticks() * 50L);
    }

    /** The invitation to show, or null (also once its time is up). */
    public static Invite invite() {
        if (invite != null && Util.getMillis() >= invite.endsAt()) invite = null;
        return invite;
    }

    public static void clearInvite() {
        invite = null;
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        party = PartySyncPayload.none();
        invite = null;
        version++;
    }

    public static HudPosition position() {
        if (position == null) position = parse(ClientQuestState.setting(KEY_POSITION));
        return position;
    }

    public static void savePosition(HudPosition pos) {
        position = pos;
        ClientQuestState.saveSetting(KEY_POSITION,
                pos.anchor().name().toLowerCase(Locale.ROOT) + "," + pos.x() + "," + pos.y());
    }

    public static boolean folded() {
        if (folded == null) folded = "true".equalsIgnoreCase(ClientQuestState.setting(KEY_FOLDED));
        return folded;
    }

    public static void saveFolded(boolean value) {
        folded = value;
        ClientQuestState.saveSetting(KEY_FOLDED, Boolean.toString(value));
    }

    private static HudPosition parse(String value) {
        if (value == null) return DEFAULT_POSITION;
        String[] parts = value.split(",");
        if (parts.length != 3) return DEFAULT_POSITION;
        try {
            return new HudPosition(HudPosition.Anchor.valueOf(parts[0].trim().toUpperCase(Locale.ROOT)),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        } catch (IllegalArgumentException e) {
            return DEFAULT_POSITION;
        }
    }
}
