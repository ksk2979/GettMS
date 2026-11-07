package org.gettserver.gettcore.editor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;

/** 문자열을 UTF-8 + Deflate로 압축해 전송 가능한 조각으로 나눈다. */
public final class Chunks {
    public static final int MAX_CHUNK_BYTES = 30_000;
    public static final int MAX_CHUNKS = 128;
    public static final int MAX_INFLATED_BYTES = 8 * 1024 * 1024;

    private Chunks() {}

    public static List<byte[]> split(String text) {
        byte[] input = text.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            deflater.setInput(input);
            deflater.finish();
            byte[] buf = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                out.write(buf, 0, n);
            }
        } finally {
            deflater.end();
        }
        byte[] packed = out.toByteArray();
        List<byte[]> chunks = new ArrayList<>();
        for (int off = 0; off < packed.length; off += MAX_CHUNK_BYTES) {
            chunks.add(Arrays.copyOfRange(packed, off, Math.min(packed.length, off + MAX_CHUNK_BYTES)));
        }
        if (chunks.size() > MAX_CHUNKS) {
            throw new IllegalArgumentException("too many chunks: " + chunks.size());
        }
        return chunks;
    }
}
