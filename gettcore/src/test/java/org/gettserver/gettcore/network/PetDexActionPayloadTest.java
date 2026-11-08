package org.gettserver.gettcore.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class PetDexActionPayloadTest {
    @Test
    void roundTripsBothActions() {
        for (PetDexActionPayload.Action action : PetDexActionPayload.Action.values()) {
            PetDexActionPayload p = new PetDexActionPayload(7, action);
            ByteBuf buf = Unpooled.buffer();
            PetDexActionPayload.STREAM_CODEC.encode(buf, p);
            assertEquals(p, PetDexActionPayload.STREAM_CODEC.decode(buf));
            assertEquals(0, buf.readableBytes());
        }
    }

    @Test
    void rejectsUnknownAction() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(1); // index 1
        buf.writeByte(9); // no such action
        assertThrows(RuntimeException.class, () -> PetDexActionPayload.STREAM_CODEC.decode(buf));
    }
}
