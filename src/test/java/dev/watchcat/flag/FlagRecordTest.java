package dev.watchcat.flag;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlagRecordTest {
    private static FlagRecord record(String reason, int durationSeconds) {
        return new FlagRecord(UUID.randomUUID(), "Suspect", "Staff", UUID.randomUUID(),
                reason, durationSeconds);
    }

    @Test
    void startsRecordingWithTheGivenReasonAndDuration() {
        FlagRecord record = record("reach", 120);

        assertEquals(FlagRecord.Status.RECORDING, record.getStatus());
        assertEquals("reach", record.getReason());
        assertEquals(120, record.getDurationSeconds());
        assertEquals(0, record.getExtensions());
        assertNull(record.getFilePath());
    }

    @Test
    void accumulatesDistinctReasonsAcrossExtensions() {
        FlagRecord record = record("reach", 60);

        record.extend(60, "killaura");
        record.extend(60, "reach");

        assertEquals("reach+killaura", record.getReason(),
                "a repeated detection must not be listed twice");
        assertEquals(2, record.getExtensions());
    }

    @Test
    void extendingMeasuresTheNewDurationFromNowRatherThanAddingToIt() {
        FlagRecord record = record("reach", 600);
        long originalExpiry = record.getExpiresAt();

        long newExpiry = record.extend(30, "killaura");

        assertEquals(30, record.getDurationSeconds());
        assertTrue(newExpiry < originalExpiry,
                "a shorter extension must shorten the window, not leave the old one running");
        assertTrue(record.getSecondsRemaining() <= 30);
    }

    @Test
    void ignoresBlankReasonsInsteadOfRecordingEmptyOnes() {
        FlagRecord record = record("reach", 60);

        record.extend(60, "  ");
        record.extend(60, null);

        assertEquals("reach", record.getReason());
    }

    @Test
    void stopsCountingDownOnceTheRecordingHasFinished() {
        FlagRecord record = record("reach", 600);
        assertTrue(record.getSecondsRemaining() > 0);

        record.markSaving();
        assertEquals(0, record.getSecondsRemaining());

        record.markReady("/replays/flagged/Suspect.mcpr", false);
        assertEquals(FlagRecord.Status.READY, record.getStatus());
        assertEquals("/replays/flagged/Suspect.mcpr", record.getFilePath());
        assertEquals(0, record.getSecondsRemaining());
        assertFalse(record.isTruncated());
    }

    @Test
    void recordsWhyAFlagProducedNothing() {
        FlagRecord record = record("reach", 60);

        record.markFailed("the player disconnected");

        assertEquals(FlagRecord.Status.FAILED, record.getStatus());
        assertEquals("the player disconnected", record.getFailure());
        assertNull(record.getFilePath());
    }
}
