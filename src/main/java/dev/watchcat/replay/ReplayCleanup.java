package dev.watchcat.replay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class ReplayCleanup {

    public static final String FLAGGED_DIRECTORY = "flagged";

    public static final class Candidate {

        private final Path file;
        private final long recordedAt;
        private final long bytes;
        private final boolean protectedFromCleanup;

        public Candidate(Path file, long recordedAt, long bytes, boolean protectedFromCleanup) {
            this.file = file;
            this.recordedAt = recordedAt;
            this.bytes = bytes;
            this.protectedFromCleanup = protectedFromCleanup;
        }

        public Path getFile() {
            return file;
        }

        public long getRecordedAt() {
            return recordedAt;
        }

        public long getBytes() {
            return bytes;
        }

        public boolean isProtectedFromCleanup() {
            return protectedFromCleanup;
        }
    }

    public static final class Result {

        private final int deleted;
        private final long bytesFreed;
        private final int keptProtected;

        Result(int deleted, long bytesFreed, int keptProtected) {
            this.deleted = deleted;
            this.bytesFreed = bytesFreed;
            this.keptProtected = keptProtected;
        }

        public int getDeleted() {
            return deleted;
        }

        public long getBytesFreed() {
            return bytesFreed;
        }

        public int getKeptProtected() {
            return keptProtected;
        }

        public boolean isEmpty() {
            return deleted == 0;
        }
    }

    private final Path replaysRoot;
    private final ReplayIndex index;
    private final StorageUsage storageUsage;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean();

    public ReplayCleanup(Path replaysRoot, ReplayIndex index, StorageUsage storageUsage, Logger logger) {
        this.replaysRoot = replaysRoot;
        this.index = index;
        this.storageUsage = storageUsage;
        this.logger = logger;
    }

    public static List<Candidate> selectForDeletion(List<Candidate> all, CleanupPolicy policy, long now) {
        List<Candidate> doomed = new ArrayList<>();
        if (!policy.isEnabled()) {
            return doomed;
        }

        List<Candidate> deletable = new ArrayList<>();
        long totalBytes = 0L;
        for (Candidate candidate : all) {
            totalBytes += candidate.getBytes();
            if (!candidate.isProtectedFromCleanup()) {
                deletable.add(candidate);
            }
        }
        deletable.sort(Comparator.comparingLong(Candidate::getRecordedAt));

        Set<Candidate> chosen = new HashSet<>();
        if (policy.getRetentionMillis() > 0L) {
            for (Candidate candidate : deletable) {
                if (now - candidate.getRecordedAt() >= policy.getRetentionMillis()) {
                    chosen.add(candidate);
                    doomed.add(candidate);
                    totalBytes -= candidate.getBytes();
                }
            }
        }

        if (policy.getMaxStorageBytes() > 0L) {
            for (Candidate candidate : deletable) {
                if (totalBytes <= policy.getMaxStorageBytes()) {
                    break;
                }
                if (chosen.contains(candidate)) {
                    continue;
                }
                if (now - candidate.getRecordedAt() < policy.getMinRetentionMillis()) {
                    continue;
                }
                chosen.add(candidate);
                doomed.add(candidate);
                totalBytes -= candidate.getBytes();
            }
        }

        return doomed;
    }

    public Result run(CleanupPolicy policy) {
        if (!policy.isEnabled() || !running.compareAndSet(false, true)) {
            return new Result(0, 0L, 0);
        }
        try {
            return sweep(policy);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Replay cleanup failed", e);
            return new Result(0, 0L, 0);
        } finally {
            running.set(false);
        }
    }

    private Result sweep(CleanupPolicy policy) {
        if (!Files.isDirectory(replaysRoot)) {
            return new Result(0, 0L, 0);
        }

        Map<String, ReplayEntry> byPath = new HashMap<>();
        for (ReplayEntry entry : index.getEntries()) {
            byPath.put(normalise(entry.getRelativePath()), entry);
        }

        List<Candidate> candidates = new ArrayList<>();
        int keptProtected = 0;

        try (Stream<Path> walk = Files.walk(replaysRoot)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (!path.toString().endsWith(".mcpr") || !Files.isRegularFile(path)) {
                    continue;
                }
                String relative = normalise(replaysRoot.relativize(path).toString());
                ReplayEntry entry = byPath.get(relative);

                boolean inFlaggedDirectory = relative.startsWith(FLAGGED_DIRECTORY + "/");
                boolean isProtected = (entry != null && entry.isProtectedFromCleanup())
                        || (policy.isProtectFlagged() && (inFlaggedDirectory
                            || (entry != null && entry.isFlagged())));

                long recordedAt = entry != null
                        ? entry.getStartTime()
                        : Files.getLastModifiedTime(path).toMillis();

                if (isProtected) {
                    keptProtected++;
                }
                candidates.add(new Candidate(path, recordedAt, Files.size(path), isProtected));
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not scan " + replaysRoot + " for cleanup", e);
            return new Result(0, 0L, 0);
        }

        List<Candidate> doomed = selectForDeletion(candidates, policy, System.currentTimeMillis());
        if (doomed.isEmpty()) {
            return new Result(0, 0L, keptProtected);
        }

        int deleted = 0;
        long freed = 0L;
        Set<String> deletedPaths = new HashSet<>();
        for (Candidate candidate : doomed) {
            try {
                Files.deleteIfExists(candidate.getFile());
                deletedPaths.add(normalise(replaysRoot.relativize(candidate.getFile()).toString()));
                deleted++;
                freed += candidate.getBytes();
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not delete replay " + candidate.getFile(), e);
            }
        }

        if (!deletedPaths.isEmpty()) {
            index.removeIf(entry -> deletedPaths.contains(normalise(entry.getRelativePath())));
        }
        removeEmptyPlayerDirectories();
        storageUsage.refresh();

        logger.info("Replay cleanup removed " + deleted + " replay(s), freeing "
                + String.format("%.1f", freed / 1024.0 / 1024.0) + " MB ("
                + keptProtected + " protected replay(s) kept).");
        return new Result(deleted, freed, keptProtected);
    }

    private void removeEmptyPlayerDirectories() {
        try (Stream<Path> children = Files.list(replaysRoot)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                if (!Files.isDirectory(child) || child.getFileName().toString().equals(FLAGGED_DIRECTORY)) {
                    continue;
                }
                try (Stream<Path> contents = Files.list(child)) {
                    if (contents.findAny().isEmpty()) {
                        Files.deleteIfExists(child);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.log(Level.FINE, "Could not prune empty replay directories", e);
        }
    }

    private static String normalise(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }
}
