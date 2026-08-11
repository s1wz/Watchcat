package dev.watchcat.capture;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class PrologueBuilderTest {
    private static int selfEntityId(byte[] raw, int varIntLength) {
        return ByteBuffer.wrap(raw, varIntLength, Integer.BYTES).getInt();
    }

    @Test
    void rewritesTheSelfEntityIdAfterASingleBytePacketId() {
        byte[] raw = {0x31, 0x00, 0x00, 0x00, 0x01, (byte) 0xAB, (byte) 0xCD};

        byte[] out = PrologueBuilder.withPhantomSelf(raw);

        assertEquals(PrologueBuilder.PHANTOM_SELF_ENTITY_ID, selfEntityId(out, 1));
        assertEquals(0x31, out[0], "packet id must be preserved");
        assertArrayEquals(new byte[]{(byte) 0xAB, (byte) 0xCD},
                new byte[]{out[5], out[6]}, "the rest of the payload must be untouched");
    }

    @Test
    void rewritesTheSelfEntityIdAfterAMultiByteVarIntPacketId() {
        byte[] raw = {(byte) 0x80, 0x02, 0x00, 0x00, 0x00, 0x07, 0x5A};

        byte[] out = PrologueBuilder.withPhantomSelf(raw);

        assertEquals(PrologueBuilder.PHANTOM_SELF_ENTITY_ID, selfEntityId(out, 2));
        assertEquals(0x5A, out[6], "the rest of the payload must be untouched");
    }

    @Test
    void doesNotMutateTheInput() {
        byte[] raw = {0x31, 0x00, 0x00, 0x00, 0x01};

        byte[] out = PrologueBuilder.withPhantomSelf(raw);

        assertNotSame(raw, out);
        assertEquals(1, selfEntityId(raw, 1), "the captured packet must stay pristine");
    }

    @Test
    void leavesTooShortPayloadsAlone() {
        byte[] raw = {0x31, 0x00, 0x00};

        assertSame(raw, PrologueBuilder.withPhantomSelf(raw));
    }
}
