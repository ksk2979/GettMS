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
        Random r = new Random(3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60_000; i++) sb.append((char) ('!' + r.nextInt(90)));
        String text = sb.toString();
        List<byte[]> c = Chunks.split(text);
        assertEquals(2, c.size());
        ChunkAssembler asm = new ChunkAssembler();
        assertTrue(asm.accept(1, 0, 2, c.get(0)).isEmpty());
        assertTrue(asm.accept(2, 0, 2, new byte[] {1}).isEmpty());
        // old session 1 part 0 must be gone: this alone must not complete anything
        assertTrue(asm.accept(1, 1, 2, c.get(1)).isEmpty());
        assertEquals(text, asm.accept(1, 0, 2, c.get(0)).orElseThrow());
    }

    @Test
    void rejectsNullAndOversizedData() {
        ChunkAssembler asm = new ChunkAssembler();
        assertThrows(IllegalArgumentException.class, () -> asm.accept(1, 0, 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> asm.accept(1, 0, 1, new byte[Chunks.MAX_CHUNK_BYTES + 1]));
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
