package dev.watchcat.replay;

import dev.watchcat.capture.RecordingSession;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ReplaySaver {
    @FunctionalInterface
    public interface Listener {
        void onReplaySaved(RecordingSession session, Path file, Throwable error);
    }

    private final ReplayWriter writer;
    private final ReplayIndex index;
    private final Path replaysRoot;
    private final String serverName;
    private final Logger logger;
    private final StorageUsage storageUsage;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public ReplaySaver(ReplayWriter writer, ReplayIndex index, Path replaysRoot,
                       String serverName, Logger logger, StorageUsage storageUsage) {
        this.writer = writer;
        this.index = index;
        this.replaysRoot = replaysRoot;
        this.serverName = serverName;
        this.logger = logger;
        this.storageUsage = storageUsage;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public Path getFlaggedDirectory() {
        return replaysRoot.resolve("flagged");
    }

    public void save(RecordingSession session, boolean protectedFromCleanup) {
        if (session.getPacketCount() == 0) {
            notifyListeners(session, null,
                    new IllegalStateException("no packets were captured for " + session.getPlayerName()));
            return;
        }

        Path outputDir = session.isFlagged()
                ? getFlaggedDirectory()
                : replaysRoot.resolve(session.getPlayerUuid().toString());

        writer.writeReplayAsync(session, serverName, session.getPlayerName(),
                        session.getPlayerUuid(), outputDir)
                .thenAccept(path -> {
                    index.addEntry(buildEntry(session, path, protectedFromCleanup));
                    storageUsage.refresh();
                    notifyListeners(session, path, null);
                })
                .exceptionally(error -> {
                    logger.log(Level.SEVERE,
                            "Failed to save replay for " + session.getPlayerName(), error);
                    notifyListeners(session, null, error);
                    return null;
                });
    }

    private ReplayEntry buildEntry(RecordingSession session, Path file, boolean protectedFromCleanup) {
        long endTimestamp = session.getLastPacketTimestamp();

        String relativePath = file.startsWith(replaysRoot)
                ? replaysRoot.relativize(file).toString()
                : file.toString();

        return new ReplayEntry(
                file.getFileName().toString(),
                relativePath,
                session.getPlayerUuid(),
                session.getPlayerName(),
                session.getStartTimestamp(),
                (int) (endTimestamp - session.getStartTimestamp()),
                session.isFlagged(),
                protectedFromCleanup,
                session.getFlagReason(),
                session.getFlagDurationSeconds());
    }

    private void notifyListeners(RecordingSession session, Path file, Throwable error) {
        for (Listener listener : listeners) {
            try {
                listener.onReplaySaved(session, file, error);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "A replay-saved listener threw", e);
            }
        }
    }
}
