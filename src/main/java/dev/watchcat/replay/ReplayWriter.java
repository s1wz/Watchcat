package dev.watchcat.replay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.watchcat.capture.CapturedPacket;
import dev.watchcat.capture.RecordingSession;
import org.bukkit.Server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ReplayWriter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = Logger.getLogger(ReplayWriter.class.getName());

    private static final String DEFAULT_MC_VERSION = "1.21.4";

    private static final int DEFAULT_PROTOCOL_VERSION = 769;

    private final String mcVersion;
    private final int protocolVersion;

    public ReplayWriter() {
        this(DEFAULT_MC_VERSION, DEFAULT_PROTOCOL_VERSION);
    }

    public static ReplayWriter forServer(Server server) {
        String version = DEFAULT_MC_VERSION;
        int protocol = DEFAULT_PROTOCOL_VERSION;

        try {
            version = server.getMinecraftVersion();
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Could not read the server's Minecraft version; "
                    + "replays will claim " + version + " and may not open in ReplayMod", t);
        }
        try {
            protocol = server.getUnsafe().getProtocolVersion();
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Could not read the server's protocol version; "
                    + "replays will claim " + protocol + " and may not open in ReplayMod", t);
        }

        LOGGER.info("Replays will be written for Minecraft " + version + " (protocol " + protocol + ")");
        return new ReplayWriter(version, protocol);
    }

    public ReplayWriter(String mcVersion, int protocolVersion) {
        this.mcVersion = mcVersion;
        this.protocolVersion = protocolVersion;
    }

    public CompletableFuture<Path> writeReplayAsync(RecordingSession session,
                                                     String serverName,
                                                     String playerName,
                                                     UUID playerUuid,
                                                     Path outputDir) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return writeReplay(session, serverName, playerName, playerUuid, outputDir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to write replay file", e);
            }
        });
    }

    Path writeReplay(RecordingSession session,
                     String serverName,
                     String playerName,
                     UUID playerUuid,
                     Path outputDir) throws IOException {
        Files.createDirectories(outputDir);

        String safeName = playerName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        String fileName = safeName + flagSuffix(session) + "_" + session.getStartTimestamp() + ".mcpr";
        Path outputFile = outputDir.resolve(fileName);

        Tmcpr tmcpr = buildTmcprStream(session);

        String uuidNoDashes = playerUuid.toString().replace("-", "");
        ReplayMetadata.Builder metadataBuilder = new ReplayMetadata.Builder()
                .serverName(serverName)
                .duration(tmcpr.durationMillis)
                .date(session.getStartTimestamp())
                .mcVersion(mcVersion)
                .protocolVersion(protocolVersion)
                .players(uuidNoDashes);

        if (session.isFlagged()) {
            metadataBuilder.flag(session.getFlagReason(), session.getFlagDurationSeconds());
        }
        ReplayMetadata metadata = metadataBuilder.build();

        CRC32 crc = new CRC32();
        crc.update(tmcpr.data);

        try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(outputFile))) {
            writeEntry(zipOut, "recording.tmcpr", tmcpr.data);
            writeEntry(zipOut, "recording.tmcpr.crc32",
                    Long.toString(crc.getValue()).getBytes(StandardCharsets.UTF_8));
            writeEntry(zipOut, "metaData.json",
                    GSON.toJson(metadata).getBytes(StandardCharsets.UTF_8));
            writeEntry(zipOut, "markers.json", "[]".getBytes(StandardCharsets.UTF_8));
            writeEntry(zipOut, "mods.json",
                    "{\"requiredMods\":[]}".getBytes(StandardCharsets.UTF_8));
        }

        LOGGER.info("Wrote replay file: " + outputFile + " (" + tmcpr.packetCount
                + " packets, " + tmcpr.durationMillis + "ms, mc " + mcVersion
                + " protocol " + protocolVersion + ")");

        return outputFile;
    }

    private static final int MAX_REASON_IN_FILENAME = 40;

    static String flagSuffix(RecordingSession session) {
        if (!session.isFlagged()) {
            return "";
        }

        String reason = session.getFlagReason();
        StringBuilder suffix = new StringBuilder("_flag");

        if (reason != null && !reason.isBlank()) {
            String safe = reason.replaceAll("[^a-zA-Z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
            if (safe.length() > MAX_REASON_IN_FILENAME) {
                safe = safe.substring(0, MAX_REASON_IN_FILENAME).replaceAll("-+$", "");
            }
            if (!safe.isEmpty()) {
                suffix.append('_').append(safe);
            }
        }
        if (session.getFlagDurationSeconds() > 0) {
            suffix.append('_').append(session.getFlagDurationSeconds()).append('s');
        }
        return suffix.toString();
    }

    private static void writeEntry(ZipOutputStream zipOut, String name, byte[] data) throws IOException {
        zipOut.putNextEntry(new ZipEntry(name));
        zipOut.write(data);
        zipOut.closeEntry();
    }

    private Tmcpr buildTmcprStream(RecordingSession session) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        long sessionStart = session.getStartTimestamp();
        int previous = 0;
        int count = 0;

        for (CapturedPacket packet : session.getPackets()) {
            long relative = packet.getTimestampMillis() - sessionStart;
            int timestamp = relative <= 0 ? 0 : (int) Math.min(relative, Integer.MAX_VALUE);
            if (timestamp < previous) {
                timestamp = previous;
            }
            previous = timestamp;

            byte[] rawBytes = packet.getRawBytes();
            writeInt(baos, timestamp);
            writeInt(baos, rawBytes.length);
            baos.write(rawBytes);
            count++;
        }

        return new Tmcpr(baos.toByteArray(), previous, count);
    }

    private static final class Tmcpr {
        final byte[] data;
        final int durationMillis;
        final int packetCount;

        Tmcpr(byte[] data, int durationMillis, int packetCount) {
            this.data = data;
            this.durationMillis = durationMillis;
            this.packetCount = packetCount;
        }
    }

    static void writeInt(OutputStream out, int x) throws IOException {
        out.write((x >>> 24) & 0xFF);
        out.write((x >>> 16) & 0xFF);
        out.write((x >>>  8) & 0xFF);
        out.write(x & 0xFF);
    }

    static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
             | ((data[offset + 1] & 0xFF) << 16)
             | ((data[offset + 2] & 0xFF) << 8)
             |  (data[offset + 3] & 0xFF);
    }
}
