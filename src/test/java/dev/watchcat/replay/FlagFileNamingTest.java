package dev.watchcat.replay;

import dev.watchcat.capture.CapturedPacket;
import dev.watchcat.capture.RecordingSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlagFileNamingTest {
    private static RecordingSession flagged(String reason, int durationSeconds) {
        RecordingSession session = new RecordingSession(UUID.randomUUID(), "Suspect", 0);
        session.markFlagged(reason, durationSeconds);
        return session;
    }

    @Test
    void ordinaryRecordingsGetNoFlagFragment() {
        RecordingSession session = new RecordingSession(UUID.randomUUID(), "Suspect", 5);

        assertEquals("", ReplayWriter.flagSuffix(session));
    }

    @Test
    void namesTheFileAfterTheReasonAndDuration() {
        assertEquals("_flag_reach_120s", ReplayWriter.flagSuffix(flagged("reach", 120)));
    }

    @Test
    void replacesCharactersAFilesystemWouldObjectTo() {
        String suffix = ReplayWriter.flagSuffix(flagged("reach: 3.4 blocks / killaura", 60));

        assertEquals("_flag_reach-3-4-blocks-killaura_60s", suffix);
        assertFalse(suffix.matches(".*[/:\\\\?*\"<>|].*"),
                "no character that is illegal in a filename may survive");
    }

    @Test
    void truncatesAVerboseReasonRatherThanBuildingAnUnusablePath() {
        String verbose = "a".repeat(500);

        String suffix = ReplayWriter.flagSuffix(flagged(verbose, 60));

        assertTrue(suffix.length() < 60, "got: " + suffix);
        assertTrue(suffix.startsWith("_flag_aaa"));
        assertTrue(suffix.endsWith("_60s"));
    }

    @Test
    void survivesAReasonMadeEntirelyOfPunctuation() {
        assertEquals("_flag_60s", ReplayWriter.flagSuffix(flagged("///", 60)));
    }

    @Test
    void writesTheReasonIntoTheReplayMetadata(@TempDir Path tempDir) throws Exception {
        RecordingSession session = flagged("reach", 120);
        session.addPacket(new CapturedPacket(session.getStartTimestamp(), "DUMMY", new byte[]{1, 2, 3}));
        session.stop();

        Path file = new ReplayWriter("1.21.4", 769)
                .writeReplay(session, "TestServer", "Suspect", session.getPlayerUuid(), tempDir);

        assertTrue(file.getFileName().toString().startsWith("Suspect_flag_reach_120s_"));

        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry("metaData.json");
            try (InputStream in = zip.getInputStream(entry)) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(json.contains("\"watchcatFlagReason\": \"reach\""), json);
                assertTrue(json.contains("\"watchcatFlagDurationSeconds\": 120"), json);
            }
        }
    }

    @Test
    void leavesOrdinaryMetadataUntouched(@TempDir Path tempDir) throws Exception {
        RecordingSession session = new RecordingSession(UUID.randomUUID(), "Suspect", 5);
        session.addPacket(new CapturedPacket(session.getStartTimestamp(), "DUMMY", new byte[]{1}));
        session.stop();

        Path file = new ReplayWriter("1.21.4", 769)
                .writeReplay(session, "TestServer", "Suspect", session.getPlayerUuid(), tempDir);

        try (ZipFile zip = new ZipFile(file.toFile())) {
            try (InputStream in = zip.getInputStream(zip.getEntry("metaData.json"))) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertFalse(json.contains("watchcatFlagReason"),
                        "a non-flag replay must keep the exact metadata shape ReplayMod writes");
                assertFalse(json.contains("watchcatFlagDurationSeconds"), json);
            }
        }
    }
}
