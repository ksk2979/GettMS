package org.gettserver.gettcore.editor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** 보낸 쪽 하나당 하나. 조각을 모아 전부 도착하면 풀어서 문자열로 돌려준다. */
public final class ChunkAssembler {
    private int sessionId;
    private boolean active;
    private byte[][] parts;
    private int received;

    public Optional<String> accept(int sessionId, int index, int total, byte[] data) {
        if (total < 1 || total > Chunks.MAX_CHUNKS) {
            throw new IllegalArgumentException("bad total: " + total);
        }
        if (index < 0 || index >= total) {
            throw new IllegalArgumentException("bad index: " + index);
        }
        if (!active || sessionId != this.sessionId) {
            this.sessionId = sessionId;
            this.active = true;
            this.parts = new byte[total][];
            this.received = 0;
        } else if (parts.length != total) {
            throw new IllegalArgumentException("total mismatch: " + total);
        }
        if (parts[index] == null) {
            parts[index] = data;
            received++;
        }
        if (received < total) {
            return Optional.empty();
        }
        byte[][] done = parts;
        active = false;
        parts = null;
        return Optional.of(inflate(done));
    }

    private static String inflate(byte[][] done) {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        for (byte[] p : done) packed.write(p, 0, p.length);
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(packed.toByteArray());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && !inflater.finished() && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new IllegalArgumentException("truncated or invalid data");
                }
                if (out.size() + n > Chunks.MAX_INFLATED_BYTES) {
                    throw new IllegalArgumentException("inflated data too large");
                }
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("invalid compressed data", e);
        } finally {
            inflater.end();
        }
    }
}
