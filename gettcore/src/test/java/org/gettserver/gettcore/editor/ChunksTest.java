package org.gettserver.gettcore.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ChunksTest {
    @Test
    void roundTripLargeText() {
        Random r = new Random(42);
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 200_000) sb.append((char) ('가' + r.nextInt(2000)));
        String text = sb.toString();
        List<byte[]> chunks = Chunks.split(text);
        assertTrue(chunks.size() > 1);
        for (byte[] c : chunks) assertTrue(c.length <= 30_000);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) order.add(i);
        Collections.shuffle(order, new Random(1));
        ChunkAssembler asm = new ChunkAssembler();
        Optional<String> result = Optional.empty();
        for (int k = 0; k < order.size(); k++) {
            int i = order.get(k);
            result = asm.accept(7, i, chunks.size(), chunks.get(i));
            if (k < order.size() - 1) assertTrue(result.isEmpty());
        }
        assertEquals(text, result.orElseThrow());
    }

    @Test
    void newSessionDiscardsOld() {
        List<byte[]> a = Chunks.split("A".repeat(10));
        List<byte[]> b = Chunks.split("hello");
        ChunkAssembler asm = new ChunkAssembler();
        assertEquals(1, a.size());
        // start a 2-part session 1, never finished
        assertTrue(asm.accept(1, 0, 2, a.get(0)).isEmpty());
        assertEquals("hello", asm.accept(2, 0, 1, b.get(0)).orElseThrow());
        // old session's second part alone cannot complete anything now
        assertTrue(asm.accept(1, 1, 2, a.get(0)).isEmpty());
    }

    @Test
    void rejectsBadIndexAndTotal() {
        ChunkAssembler asm = new ChunkAssembler();
        byte[] d = Chunks.split("x").get(0);
        assertThrows(IllegalArgumentException.class, () -> asm.accept(1, 0, Chunks.MAX_CHUNKS + 1, d));
        assertThrows(IllegalArgumentException.class, () -> asm.accept(1, 0, 0, d));
        assertThrows(IllegalArgumentException.class, () -> asm.accept(1, -1, 2, d));
        assertThrows(IllegalArgumentException.class, () -> asm.accept(1, 2, 2, d));
        asm.accept(5, 0, 3, d);
        assertThrows(IllegalArgumentException.class, () -> asm.accept(5, 1, 4, d));
    }

    @Test
    void emptyStringIsOneChunk() {
        List<byte[]> chunks = Chunks.split("");
        assertEquals(1, chunks.size());
        assertEquals("", new ChunkAssembler().accept(1, 0, 1, chunks.get(0)).orElseThrow());
    }

    @Test
    void rejectsOversizedInflate() {
        List<byte[]> chunks = Chunks.split("a".repeat(8 * 1024 * 1024 + 1));
        ChunkAssembler asm = new ChunkAssembler();
        assertThrows(IllegalArgumentException.class, () -> {
            for (int i = 0; i < chunks.size(); i++) asm.accept(1, i, chunks.size(), chunks.get(i));
        });
    }

    @Test
    void acceptsExactlyMaxInflate() {
        String text = "a".repeat(8 * 1024 * 1024);
        List<byte[]> chunks = Chunks.split(text);
        ChunkAssembler asm = new ChunkAssembler();
        Optional<String> r = Optional.empty();
        for (int i = 0; i < chunks.size(); i++) r = asm.accept(1, i, chunks.size(), chunks.get(i));
        assertEquals(text.length(), r.orElseThrow().length());
    }
}
