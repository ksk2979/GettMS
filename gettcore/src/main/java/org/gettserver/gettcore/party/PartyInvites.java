package org.gettserver.gettcore.party;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Invitations waiting for an answer: one per invited player, gone when answered or when its time runs out (which
 * counts as a refusal). Pure; time is a tick counter supplied by the caller.
 */
public final class PartyInvites {
    /** How long an invitation waits (20 s). */
    public static final int DURATION_TICKS = 400;

    public record Invite(UUID target, String party, UUID inviter, long expiresAt) {}

    private final Map<UUID, Invite> byTarget = new HashMap<>();

    /** False when the target already has an invitation waiting. */
    public boolean offer(Invite invite) {
        return byTarget.putIfAbsent(invite.target(), invite) == null;
    }

    /** The waiting invitation of the target, removed; null when there is none. */
    public Invite take(UUID target) {
        return byTarget.remove(target);
    }

    /** Invitations whose time is up at {@code now}, removed. */
    public List<Invite> expire(long now) {
        List<Invite> out = new ArrayList<>();
        for (Iterator<Invite> it = byTarget.values().iterator(); it.hasNext();) {
            Invite i = it.next();
            if (now >= i.expiresAt()) {
                out.add(i);
                it.remove();
            }
        }
        return out;
    }

    /** Invitations to a party that no longer exists, removed. */
    public List<Invite> dropParty(String party) {
        List<Invite> out = new ArrayList<>();
        String key = party.toLowerCase(Locale.ROOT);
        for (Iterator<Invite> it = byTarget.values().iterator(); it.hasNext();) {
            Invite i = it.next();
            if (i.party().toLowerCase(Locale.ROOT).equals(key)) {
                out.add(i);
                it.remove();
            }
        }
        return out;
    }

    public void clear() {
        byTarget.clear();
    }
}
