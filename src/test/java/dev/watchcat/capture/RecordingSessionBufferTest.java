package dev.watchcat.capture;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingSessionBufferTest {
    private static final int CAPACITY = 50_000;

    private static CapturedPacket packet(int index) {
        return new CapturedPacket(index, "DUMMY", new byte[]{(byte) index});
    }

    private static void fillPast(RecordingSession session, int extra) {
        for (int i = 0; i < CAPACITY + extra; i++) {
            session.addPacket(packet(i));
        }
    }

    @Test
    void aRollingBufferKeepsTheMostRecentPackets() {
        RecordingSession session = new RecordingSession(UUID.randomUUID(), "Suspect", 5);

        fillPast(session, 10);

        List<CapturedPacket> packets = session.getPackets();
        assertEquals(CAPACITY, packets.size());
        assertEquals(CAPACITY + 9, packets.get(packets.size() - 1).getTimestampMillis(),
                "the newest packet must survive");
        assertEquals(10, packets.get(0).getTimestampMillis(),
                "the oldest packets are the ones evicted");
        assertFalse(session.isTruncated());
    }

    @Test
    void aFlagRecordingKeepsItsOpeningMomentAndReportsTheLoss() {
        RecordingSession session = new RecordingSession(
                UUID.randomUUID(), "Suspect", 0, s -> {}, false);
        session.markFlagged("reach", 600);

        fillPast(session, 10);

        List<CapturedPacket> packets = session.getPackets();
        assertEquals(CAPACITY, packets.size());
        assertEquals(0, packets.get(0).getTimestampMillis(),
                "the moment the flag was raised must never be dropped");
        assertEquals(CAPACITY - 1, packets.get(packets.size() - 1).getTimestampMillis());
        assertTrue(session.isTruncated(),
                "a shortened recording has to say so, or it reads as a complete one");
    }

    @Test
    void aFlagRecordingCarriesItsReasonAndDuration() {
        RecordingSession session = new RecordingSession(
                UUID.randomUUID(), "Suspect", 0, s -> {}, false);

        assertFalse(session.isFlagged());

        session.markFlagged("reach", 120);
        assertTrue(session.isFlagged());
        assertEquals("reach", session.getFlagReason());
        assertEquals(120, session.getFlagDurationSeconds());

        session.setFlagReason("reach+killaura");
        assertEquals("reach+killaura", session.getFlagReason(),
                "extending a flag has to update the reason the file is named after");
    }
}
