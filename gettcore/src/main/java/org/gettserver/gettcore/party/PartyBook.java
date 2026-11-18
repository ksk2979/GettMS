package org.gettserver.gettcore.party;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Every party and who is in it. A player is in at most one party; the first member is the leader, so when the leader
 * leaves the next member takes over, and a party with no members is gone. Only the leader invites and kicks.
 * Party names are unique ignoring case. Pure: no Minecraft classes.
 */
public final class PartyBook {
    public static final int MAX_MEMBERS = 4;
    public static final int NAME_MAX = 12;

    public enum Result {
        OK, ALREADY_IN_PARTY, BAD_NAME, NAME_TAKEN, NOT_IN_PARTY, NOT_LEADER, FULL, TARGET_IN_PARTY, SELF, NOT_MEMBER,
        NO_SUCH_PARTY
    }

    public record Member(UUID id, String name) {}

    public static final class Party {
        private final String name;
        private final List<Member> members = new ArrayList<>();

        private Party(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        /** In join order; the first one is the leader. */
        public List<Member> members() {
            return Collections.unmodifiableList(members);
        }

        public UUID leader() {
            return members.get(0).id();
        }

        public boolean isLeader(UUID id) {
            return !members.isEmpty() && members.get(0).id().equals(id);
        }

        public boolean contains(UUID id) {
            return indexOf(id) >= 0;
        }

        public int size() {
            return members.size();
        }

        private int indexOf(UUID id) {
            for (int i = 0; i < members.size(); i++) if (members.get(i).id().equals(id)) return i;
            return -1;
        }
    }

    private final Map<String, Party> byName = new LinkedHashMap<>();
    private final Map<UUID, Party> byMember = new HashMap<>();

    /** Trimmed name, or null when it is empty, too long or has characters that break the chat tag. */
    public static String cleanName(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.codePointCount(0, s.length()) > NAME_MAX) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F || c == 0xA7 || c == '[' || c == ']') return null;
        }
        return s;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public Result create(Member leader, String rawName) {
        if (byMember.containsKey(leader.id())) return Result.ALREADY_IN_PARTY;
        String name = cleanName(rawName);
        if (name == null) return Result.BAD_NAME;
        if (byName.containsKey(key(name))) return Result.NAME_TAKEN;
        Party p = new Party(name);
        p.members.add(leader);
        byName.put(key(name), p);
        byMember.put(leader.id(), p);
        return Result.OK;
    }

    /** The party of a player, or null. */
    public Party of(UUID player) {
        return byMember.get(player);
    }

    public Party named(String name) {
        return name == null ? null : byName.get(key(name));
    }

    public Collection<Party> parties() {
        return Collections.unmodifiableCollection(byName.values());
    }

    public boolean sameParty(UUID a, UUID b) {
        Party p = byMember.get(a);
        return p != null && p == byMember.get(b);
    }

    /** Whether {@code inviter} may invite {@code target} right now. */
    public Result checkInvite(UUID inviter, UUID target) {
        Party p = byMember.get(inviter);
        if (p == null) return Result.NOT_IN_PARTY;
        if (!p.isLeader(inviter)) return Result.NOT_LEADER;
        if (inviter.equals(target)) return Result.SELF;
        if (byMember.containsKey(target)) return Result.TARGET_IN_PARTY;
        if (p.size() >= MAX_MEMBERS) return Result.FULL;
        return Result.OK;
    }

    public Result join(String partyName, Member member) {
        if (byMember.containsKey(member.id())) return Result.ALREADY_IN_PARTY;
        Party p = named(partyName);
        if (p == null) return Result.NO_SUCH_PARTY;
        if (p.size() >= MAX_MEMBERS) return Result.FULL;
        p.members.add(member);
        byMember.put(member.id(), p);
        return Result.OK;
    }

    /** Removes the player from their party; the party disappears with its last member. */
    public Result leave(UUID player) {
        Party p = byMember.remove(player);
        if (p == null) return Result.NOT_IN_PARTY;
        p.members.remove(p.indexOf(player));
        if (p.members.isEmpty()) byName.remove(key(p.name));
        return Result.OK;
    }

    public Result kick(UUID leader, UUID target) {
        Party p = byMember.get(leader);
        if (p == null) return Result.NOT_IN_PARTY;
        if (!p.isLeader(leader)) return Result.NOT_LEADER;
        if (leader.equals(target)) return Result.SELF;
        if (!p.contains(target)) return Result.NOT_MEMBER;
        return leave(target);
    }

    /** Updates the stored name of a member (account names can change). True when it changed. */
    public boolean rename(UUID player, String name) {
        Party p = byMember.get(player);
        if (p == null) return false;
        int i = p.indexOf(player);
        if (p.members.get(i).name().equals(name)) return false;
        p.members.set(i, new Member(player, name));
        return true;
    }

    /** Loading: adds a saved party, skipping bad names, members already placed and anything over the size limit. */
    public void restore(String rawName, List<Member> members) {
        String name = cleanName(rawName);
        if (name == null || byName.containsKey(key(name))) return;
        Party p = new Party(name);
        for (Member m : members) {
            if (m == null || m.id() == null || byMember.containsKey(m.id()) || p.size() >= MAX_MEMBERS) continue;
            p.members.add(new Member(m.id(), m.name() == null ? "" : m.name()));
            byMember.put(m.id(), p);
        }
        if (!p.members.isEmpty()) byName.put(key(name), p);
    }
}
