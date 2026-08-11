package dev.watchcat.replay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class StorageUsage {
    public static final class Snapshot {
        private final long bytes;
        private final int files;
        private final long measuredAt;

        Snapshot(long bytes, int files, long measuredAt) {
            this.bytes = bytes;
            this.files = files;
            this.measuredAt = measuredAt;
        }

        public long getBytes() {
            return bytes;
        }

        public double getMegabytes() {
            return bytes / 1024.0 / 1024.0;
        }

        public int getFiles() {
            return files;
        }

        public long getMeasuredAt() {
            return measuredAt;
        }

        public boolean isMeasured() {
            return measuredAt > 0;
        }
    }

    private static final Snapshot NEVER_MEASURED = new Snapshot(0L, 0, 0L);

    private final Path replaysRoot;
    private final Logger logger;
    private final AtomicReference<Snapshot> latest = new AtomicReference<>(NEVER_MEASURED);
    private final AtomicBoolean measuring = new AtomicBoolean();

    public StorageUsage(Path replaysRoot, Logger logger) {
        this.replaysRoot = replaysRoot;
        this.logger = logger;
    }

    public Snapshot getLatest() {
        return latest.get();
    }

    public void refresh() {
        if (!measuring.compareAndSet(false, true)) {
            return;
        }
        try {
            latest.set(measure());
        } finally {
            measuring.set(false);
        }
    }

    private Snapshot measure() {
        if (!Files.isDirectory(replaysRoot)) {
            return new Snapshot(0L, 0, System.currentTimeMillis());
        }

        long bytes = 0L;
        int files = 0;
        try (Stream<Path> walk = Files.walk(replaysRoot)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (!path.toString().endsWith(".mcpr")) {
                    continue;
                }
                try {
                    bytes += Files.size(path);
                    files++;
                } catch (IOException ignored) {
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not measure replay storage under " + replaysRoot, e);
            return latest.get();
        }
        return new Snapshot(bytes, files, System.currentTimeMillis());
    }
}
