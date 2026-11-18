package org.gettserver.gettcore.nick;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.gettserver.gettcore.nick.NicknameBook.Result;
import org.junit.jupiter.api.Test;

/** 서버 닉네임: 규칙(2~12자, 글자·숫자·밑줄), 대소문자 무시 중복 금지, 계정 이름과 겹침 금지, 바꾸면 옛 이름이 풀림. */
class NicknameBookTest {
    static final UUID A = new UUID(0, 1), B = new UUID(0, 2);

    @Test
    void rules() {
        assertEquals("천사", NicknameBook.clean(" 천사 "));
        assertEquals("Angel_97", NicknameBook.clean("Angel_97"));
        assertNull(NicknameBook.clean("a"));
        assertNull(NicknameBook.clean("1234567890123"));
        assertNull(NicknameBook.clean("a b"));
        assertNull(NicknameBook.clean("[운영자]"));
        assertNull(NicknameBook.clean("a" + (char) 0xA7 + "c"));
        assertNull(NicknameBook.clean(null));
    }

    @Test
    void uniqueIgnoringCaseAndNotSomeoneElsesAccountName() {
        NicknameBook book = new NicknameBook();
        assertEquals(Result.OK, book.set(A, "Angel", k -> false));
        assertEquals(Result.SAME, book.set(A, "Angel", k -> false));
        assertEquals(Result.TAKEN, book.set(B, "angel", k -> false));
        assertEquals(Result.TAKEN, book.set(B, "Steve", k -> k.equals("steve")));
        assertEquals(Result.BAD_NAME, book.set(B, "x", k -> false));
        assertEquals(A, book.owner("ANGEL"));
        assertNull(book.of(B));
    }

    @Test
    void changingFreesTheOldNameAndCaseOnlyChangesWork() {
        NicknameBook book = new NicknameBook();
        book.set(A, "Angel", null);
        assertEquals(Result.OK, book.set(A, "ANGEL", null));
        assertEquals("ANGEL", book.of(A));
        assertEquals(Result.OK, book.set(A, "천사", null));
        assertEquals(Result.OK, book.set(B, "angel", null));
        assertTrue(book.clear(A));
        assertFalse(book.clear(A));
        assertNull(book.owner("천사"));
    }

    @Test
    void restoreSkipsBadAndDuplicateEntries() {
        NicknameBook book = new NicknameBook();
        book.restore(A, "one");
        book.restore(B, "ONE");
        book.restore(B, "x");
        book.restore(A, "two");
        assertEquals(1, book.all().size());
        assertEquals("one", book.of(A));
    }
}
