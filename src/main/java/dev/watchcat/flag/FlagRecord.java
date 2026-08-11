package dev.watchcat.flag;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The state of one flag, from the moment it is raised to the finished file on disk.
 * <p>
 * A record outlives the recording it describes: staff who flagged a player need to be able
 * to ask later whether that recording is ready, so it stays in Watchcat's history with its
 * final {@link Status} and file path.
 * <p>
 * Instances are mutable and read from several threads — a flag can be raised from an
 * anticheat's asynchronous thread, extended from the main thread, and completed on the
 * replay writer's thread — so treat every getter as a snapshot rather than a stable value.
 *
 * @see dev.watchcat.api.WatchcatAPI#getRecentFlags(String, int)
 */
public final class FlagRecord {

    /** Where a flag is in its lifecycle. */
    public enum Status {

        /** The recording is running. */
        RECORDING,

        /** The recording has stopped and the file is being written. */
        SAVING,

        /** The file is on disk and can be reviewed. */
        READY,

        /** Nothing usable was produced; see {@link FlagRecord#getFailure()}. */
        FAILED
    }

    private static final int MAX_REASONS = 5;

    private final UUID playerUuid;
    private final String playerName;
    private final String issuedBy;
    private final UUID issuedByUuid;
    private final long startedAt;

    private final Set<String> reasons = new LinkedHashSet<>();

    private volatile int durationSeconds;
    private volatile long expiresAt;
    private volatile int extensions;
    private volatile Status status = Status.RECORDING;
    private volatile String filePath;
    private volatile String failure;
    private volatile boolean truncated;

    FlagRecord(UUID playerUuid, String playerName, String issuedBy, UUID issuedByUuid,
               String reason, int durationSeconds) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.issuedBy = issuedBy;
        this.issuedByUuid = issuedByUuid;
        this.startedAt = System.currentTimeMillis();
        this.durationSeconds = durationSeconds;
        this.expiresAt = startedAt + durationSeconds * 1000L;
        addReason(reason);
    }

    long extend(int durationSeconds, String reason) {
        this.durationSeconds = durationSeconds;
        this.expiresAt = System.currentTimeMillis() + durationSeconds * 1000L;
        this.extensions++;
        addReason(reason);
        return expiresAt;
    }

    private synchronized void addReason(String reason) {
        if (reason == null || reason.isBlank() || reasons.size() >= MAX_REASONS) {
            return;
        }
        reasons.add(reason.trim());
    }

    /**
     * @return every reason recorded for this flag, joined with {@code +}. A flag extended
     *         by further detections accumulates them, so this can read
     *         {@code "reach+killaura"}.
     */
    public synchronized String getReason() {
        return String.join("+", reasons);
    }

    void markSaving() {
        this.status = Status.SAVING;
    }

    void markReady(String filePath, boolean truncated) {
        this.filePath = filePath;
        this.truncated = truncated;
        this.status = Status.READY;
    }

    void markFailed(String failure) {
        this.failure = failure;
        this.status = Status.FAILED;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getIssuedBy() {
        return issuedBy;
    }

    /**
     * @return the issuing player's UUID, or null if the flag came from the console or from
     *         a plugin calling the API
     */
    public UUID getIssuedByUuid() {
        return issuedByUuid;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    /**
     * @return seconds left before the recording stops, or 0 once it has
     */
    public long getSecondsRemaining() {
        if (status != Status.RECORDING) {
            return 0;
        }
        return Math.max(0, (expiresAt - System.currentTimeMillis() + 999) / 1000);
    }

    /**
     * @return how many times this flag was re-raised while already recording
     */
    public int getExtensions() {
        return extensions;
    }

    public Status getStatus() {
        return status;
    }

    /**
     * @return the saved replay's absolute path, or null until the status is
     *         {@link Status#READY}
     */
    public String getFilePath() {
        return filePath;
    }

    /**
     * @return why the flag produced no replay, or null unless the status is
     *         {@link Status#FAILED}
     */
    public String getFailure() {
        return failure;
    }

    /**
     * @return true if the recording hit its packet limit and so covers less than the full
     *         duration that was asked for
     */
    public boolean isTruncated() {
        return truncated;
    }
}
