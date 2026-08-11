package dev.watchcat.replay;

import com.google.gson.annotations.SerializedName;

import java.util.UUID;

public final class ReplayEntry {
    private String filename;

    private String relativePath;

    private UUID playerUuid;
    private String playerName;
    private long startTime;
    private int duration;
    private boolean flagged;

    @SerializedName("protected")
    private boolean protectedFromCleanup;

    private String flagReason;

    private int flagDurationSeconds;

    public ReplayEntry(String filename, String relativePath, UUID playerUuid, String playerName,
                       long startTime, int duration, boolean flagged, boolean protectedFromCleanup,
                       String flagReason, int flagDurationSeconds) {
        this.filename = filename;
        this.relativePath = relativePath;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.startTime = startTime;
        this.duration = duration;
        this.flagged = flagged;
        this.protectedFromCleanup = protectedFromCleanup;
        this.flagReason = flagReason;
        this.flagDurationSeconds = flagDurationSeconds;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getRelativePath() {
        return relativePath != null ? relativePath : filename;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public void setPlayerUuid(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public boolean isFlagged() {
        return flagged;
    }

    public void setFlagged(boolean flagged) {
        this.flagged = flagged;
    }

    public boolean isProtectedFromCleanup() {
        return protectedFromCleanup;
    }

    public void setProtectedFromCleanup(boolean protectedFromCleanup) {
        this.protectedFromCleanup = protectedFromCleanup;
    }

    public String getFlagReason() {
        return flagReason;
    }

    public void setFlagReason(String flagReason) {
        this.flagReason = flagReason;
    }

    public int getFlagDurationSeconds() {
        return flagDurationSeconds;
    }

    public void setFlagDurationSeconds(int flagDurationSeconds) {
        this.flagDurationSeconds = flagDurationSeconds;
    }
}
