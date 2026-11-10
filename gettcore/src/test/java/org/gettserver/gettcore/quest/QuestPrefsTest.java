package org.gettserver.gettcore.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

class QuestPrefsTest {
    @Test
    void noteStartedAppendsOnce() {
        QuestPrefs p = new QuestPrefs();
        p.noteStarted("a");
        p.noteStarted("b");
        p.noteStarted("a");
        assertEquals(List.of("a", "b"), p.order());
    }

    @Test
    void orderedPutsUnknownStartedAfterKnown() {
        QuestPrefs p = new QuestPrefs();
        p.noteStarted("b");
        p.noteStarted("d");
        assertEquals(List.of("b", "d", "a", "c"), p.ordered(Set.of("a", "c", "d", "b")));
    }

    @Test
    void unknownLinesIgnored() {
        QuestPrefs p = new QuestPrefs();
        p.noteStarted("gone");
        p.noteStarted("x");
        assertEquals(List.of("x"), p.ordered(List.of("x")));
    }

    @Test
    void codecRoundTrip() {
        QuestPrefs p = new QuestPrefs();
        p.noteStarted("a");
        p.noteStarted("b");
        p.hidden().add("b");
        Tag t = QuestPrefs.CODEC.encodeStart(NbtOps.INSTANCE, p).getOrThrow();
        QuestPrefs back = QuestPrefs.CODEC.parse(NbtOps.INSTANCE, t).getOrThrow();
        assertEquals(List.of("a", "b"), back.order());
        assertEquals(Set.of("b"), back.hidden());
    }

    @Test
    void emptyTagDecodesEmpty() {
        QuestPrefs back = QuestPrefs.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow();
        assertTrue(back.order().isEmpty());
        assertTrue(back.hidden().isEmpty());
    }
}
