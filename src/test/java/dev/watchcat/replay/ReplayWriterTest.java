package dev.watchcat.replay;

import com.google.gson.Gson;
import dev.watchcat.capture.CapturedPacket;
import dev.watchcat.capture.RecordingSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ReplayWriterTest {
    @Test
    void testWriteReplay(@TempDir Path tempDir) throws Exception {
        UUID playerUuid = UUID.randomUUID();
        String playerName = "TestPlayer";
        String serverName = "TestServer";

        RecordingSession session = new RecordingSession(playerUuid, "TestPlayer", 5);

        session.addPacket(new CapturedPacket(session.getStartTimestamp(), "DUMMY_1", new byte[]{1, 2, 3}));

        session.addPacket(new CapturedPacket(session.getStartTimestamp() + 100, "DUMMY_2", new byte[]{4, 5, 6, 7}));

        session.stop();

        ReplayWriter writer = new ReplayWriter("1.21.4", 769);

        Path outputPath = writer.writeReplayAsync(session, serverName, playerName, playerUuid, tempDir).join();

        assertTrue(Files.exists(outputPath), "Output .mcpr file should exist");

        try (ZipFile zipFile = new ZipFile(outputPath.toFile())) {
            ZipEntry metaEntry = zipFile.getEntry("metaData.json");
            assertNotNull(metaEntry, "metaData.json should exist in the archive");

            ZipEntry tmcprEntry = zipFile.getEntry("recording.tmcpr");
            assertNotNull(tmcprEntry, "recording.tmcpr should exist in the archive");

            try (Reader reader = new InputStreamReader(zipFile.getInputStream(metaEntry), StandardCharsets.UTF_8)) {
                Gson gson = new Gson();
                ReplayMetadata metadata = gson.fromJson(reader, ReplayMetadata.class);

                assertEquals(serverName, metadata.getServerName());
                assertEquals(100, metadata.getDuration());
                assertEquals("1.21.4", metadata.getMcVersion());
                assertEquals(769, metadata.getProtocolVersion());
                assertArrayEquals(new String[]{playerUuid.toString().replace("-", "")}, metadata.getPlayers());
                assertEquals("Watchcat", metadata.getGenerator());
            }

            byte[] tmcpr;
            try (InputStream in = zipFile.getInputStream(tmcprEntry)) {
                tmcpr = in.readAllBytes();
            }

            assertFalse(tmcpr.length > 1 && (tmcpr[0] & 0xFF) == 0x1F && (tmcpr[1] & 0xFF) == 0x8B,
                    "recording.tmcpr must not be gzip-compressed");

            try (InputStream in = new ByteArrayInputStream(tmcpr)) {
                assertEquals(0, readIntFromStream(in));
                assertEquals(3, readIntFromStream(in));
                assertArrayEquals(new byte[]{1, 2, 3}, readBytes(in, 3));

                assertEquals(100, readIntFromStream(in));
                assertEquals(4, readIntFromStream(in));
                assertArrayEquals(new byte[]{4, 5, 6, 7}, readBytes(in, 4));

                assertEquals(-1, in.read(), "Should be end of stream");
            }

            ZipEntry crcEntry = zipFile.getEntry("recording.tmcpr.crc32");
            assertNotNull(crcEntry, "recording.tmcpr.crc32 should exist in the archive");
            CRC32 expected = new CRC32();
            expected.update(tmcpr);
            try (InputStream in = zipFile.getInputStream(crcEntry)) {
                assertEquals(Long.toString(expected.getValue()),
                        new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }

            assertNotNull(zipFile.getEntry("markers.json"), "markers.json should exist");
            assertNotNull(zipFile.getEntry("mods.json"), "mods.json should exist");
        }
    }

    @Test
    void testProloguePacketsAreClampedToZero(@TempDir Path tempDir) throws Exception {
        UUID playerUuid = UUID.randomUUID();
        RecordingSession session = new RecordingSession(playerUuid, "TestPlayer", 5);

        session.setGlobalStatePackets(List.of(
                new CapturedPacket(session.getStartTimestamp() - 60_000, "LOGIN", new byte[]{9})));
        session.addPacket(new CapturedPacket(session.getStartTimestamp() + 50, "DUMMY", new byte[]{1}));
        session.stop();

        Path outputPath = new ReplayWriter("1.21.4", 769)
                .writeReplayAsync(session, "TestServer", "TestPlayer", playerUuid, tempDir).join();

        try (ZipFile zipFile = new ZipFile(outputPath.toFile());
             InputStream in = zipFile.getInputStream(zipFile.getEntry("recording.tmcpr"))) {
            assertEquals(0, readIntFromStream(in), "prologue packet must be emitted at t=0");
            assertEquals(1, readIntFromStream(in));
            assertArrayEquals(new byte[]{9}, readBytes(in, 1));

            assertEquals(50, readIntFromStream(in));
            assertEquals(1, readIntFromStream(in));
            assertArrayEquals(new byte[]{1}, readBytes(in, 1));

            assertEquals(-1, in.read());
        }
    }

    @Test
    void testTrackedPacketStillInBufferIsNotDuplicated() {
        RecordingSession session = new RecordingSession(UUID.randomUUID(), "TestPlayer", 5);

        CapturedPacket chunk = new CapturedPacket(session.getStartTimestamp(), "CHUNK_DATA", new byte[]{1});
        session.addPacket(chunk);
        session.trackChunk(0, 0, chunk);

        assertEquals(1, session.getPackets().size(), "tracked packet should not be emitted twice");
    }

    private int readIntFromStream(InputStream in) throws Exception {
        byte[] bytes = readBytes(in, 4);
        return ((bytes[0] & 0xFF) << 24)
             | ((bytes[1] & 0xFF) << 16)
             | ((bytes[2] & 0xFF) << 8)
             |  (bytes[3] & 0xFF);
    }

    private byte[] readBytes(InputStream in, int length) throws Exception {
        byte[] buffer = new byte[length];
        int read = 0;
        while (read < length) {
            int r = in.read(buffer, read, length - read);
            if (r == -1) {
                throw new java.io.EOFException("Unexpected EOF");
            }
            read += r;
        }
        return buffer;
    }
}
