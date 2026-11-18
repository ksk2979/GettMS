package org.gettserver.gettcore.party;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.gettserver.gettcore.party.PartyBook.Member;
import org.gettserver.gettcore.party.PartyBook.Result;
import org.junit.jupiter.api.Test;

/** 파티: 만들기·초대 조건·참가·탈퇴(파티장 승계, 마지막 사람이 나가면 해체)·추방, 초대 대기와 시간 초과. */
class PartyBookTest {
    static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3), D = new UUID(0, 4), E = new UUID(0, 5);

    static Member m(UUID id) {
        return new Member(id, "p" + id.getLeastSignificantBits());
    }

    @Test
    void namesAreTrimmedLimitedAndUniqueIgnoringCase() {
        assertEquals("봄동", PartyBook.cleanName("  봄동 "));
        assertNull(PartyBook.cleanName("   "));
        assertNull(PartyBook.cleanName("1234567890123"));
        assertEquals("123456789012", PartyBook.cleanName("123456789012"));
        assertNull(PartyBook.cleanName("a[b]"));
        assertNull(PartyBook.cleanName("a" + (char) 0xA7 + "c"));
        PartyBook book = new PartyBook();
        assertEquals(Result.BAD_NAME, book.create(m(A), ""));
        assertEquals(Result.OK, book.create(m(A), "Team"));
        assertEquals(Result.NAME_TAKEN, book.create(m(B), "team"));
        assertEquals(Result.ALREADY_IN_PARTY, book.create(m(A), "Other"));
    }

    @Test
    void onlyTheLeaderInvitesPlayersWithoutAPartyUntilFull() {
        PartyBook book = new PartyBook();
        assertEquals(Result.NOT_IN_PARTY, book.checkInvite(A, B));
        book.create(m(A), "one");
        assertEquals(Result.SELF, book.checkInvite(A, A));
        assertEquals(Result.OK, book.checkInvite(A, B));
        assertEquals(Result.OK, book.join("ONE", m(B)));
        assertEquals(Result.NOT_LEADER, book.checkInvite(B, C));
        assertEquals(Result.TARGET_IN_PARTY, book.checkInvite(A, B));
        book.join("one", m(C));
        book.join("one", m(D));
        assertEquals(Result.FULL, book.checkInvite(A, E));
        assertEquals(Result.FULL, book.join("one", m(E)));
        assertEquals(Result.NO_SUCH_PARTY, book.join("none", m(E)));
        assertTrue(book.sameParty(A, D));
        assertFalse(book.sameParty(A, E));
        assertFalse(book.sameParty(E, E));
    }

    @Test
    void leaderLeavingPassesLeadershipAndTheLastOneDisbands() {
        PartyBook book = new PartyBook();
        book.create(m(A), "one");
        book.join("one", m(B));
        book.join("one", m(C));
        assertEquals(Result.OK, book.leave(A));
        assertNull(book.of(A));
        assertTrue(book.of(B).isLeader(B));
        assertEquals(Result.NOT_LEADER, book.kick(C, B));
        assertEquals(Result.SELF, book.kick(B, B));
        assertEquals(Result.NOT_MEMBER, book.kick(B, A));
        assertEquals(Result.OK, book.kick(B, C));
        assertEquals(1, book.of(B).size());
        assertEquals(Result.OK, book.leave(B));
        assertNull(book.named("one"));
        assertEquals(Result.NOT_IN_PARTY, book.leave(B));
        assertEquals(Result.OK, book.create(m(C), "one"));
    }

    @Test
    void restoreSkipsDuplicatesAndRenameUpdatesTheStoredName() {
        PartyBook book = new PartyBook();
        book.restore("one", List.of(m(A), m(B)));
        book.restore("two", List.of(m(B), m(C)));
        book.restore("one", List.of(m(D)));
        book.restore("bad[", List.of(m(E)));
        book.restore("empty", List.of(m(A)));
        assertEquals(2, book.parties().size());
        assertEquals("one", book.of(B).name());
        assertEquals(List.of(m(C)), book.of(C).members());
        assertNull(book.of(D));
        assertTrue(book.rename(A, "new"));
        assertFalse(book.rename(A, "new"));
        assertEquals("new", book.of(A).members().get(0).name());
    }

    @Test
    void oneInvitePerTargetAndItExpires() {
        PartyInvites inv = new PartyInvites();
        assertTrue(inv.offer(new PartyInvites.Invite(B, "one", A, 400)));
        assertFalse(inv.offer(new PartyInvites.Invite(B, "two", C, 500)));
        assertTrue(inv.offer(new PartyInvites.Invite(D, "One", A, 450)));
        assertTrue(inv.expire(399).isEmpty());
        assertEquals(1, inv.expire(400).size());
        assertNull(inv.take(B));
        assertEquals(1, inv.dropParty("one").size());
        assertNull(inv.take(D));
    }
}
