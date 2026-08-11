package dev.watchcat.replay;

import com.google.gson.annotations.SerializedName;

public final class ReplayMetadata {
    public static final int CURRENT_FILE_FORMAT_VERSION = 14;

    private boolean singleplayer;

    @SerializedName("serverName")
    private String serverName;

    private int duration;

    private long date;

    @SerializedName("mcversion")
    private String mcVersion;

    private String fileFormat;

    private int fileFormatVersion;

    @SerializedName("protocol")
    private int protocolVersion;

    private String generator;

    private int selfId;

    private String[] players;

    @SerializedName("watchcatFlagReason")
    private String flagReason;

    @SerializedName("watchcatFlagDurationSeconds")
    private Integer flagDurationSeconds;

    private ReplayMetadata() {
    }

    public boolean isSingleplayer() {
        return singleplayer;
    }

    public String getServerName() {
        return serverName;
    }

    public int getDuration() {
        return duration;
    }

    public long getDate() {
        return date;
    }

    public String getMcVersion() {
        return mcVersion;
    }

    public String getFileFormat() {
        return fileFormat;
    }

    public int getFileFormatVersion() {
        return fileFormatVersion;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public String getGenerator() {
        return generator;
    }

    public int getSelfId() {
        return selfId;
    }

    public String[] getPlayers() {
        return players;
    }

    public String getFlagReason() {
        return flagReason;
    }

    public Integer getFlagDurationSeconds() {
        return flagDurationSeconds;
    }

    public static final class Builder {
        private final ReplayMetadata meta = new ReplayMetadata();

        public Builder() {
            meta.singleplayer = false;
            meta.fileFormat = "MCPR";
            meta.fileFormatVersion = CURRENT_FILE_FORMAT_VERSION;
            meta.generator = "Watchcat";
            meta.selfId = -1;
        }

        public Builder serverName(String serverName) {
            meta.serverName = serverName;
            return this;
        }

        public Builder duration(int durationMillis) {
            meta.duration = durationMillis;
            return this;
        }

        public Builder date(long epochMillis) {
            meta.date = epochMillis;
            return this;
        }

        public Builder mcVersion(String mcVersion) {
            meta.mcVersion = mcVersion;
            return this;
        }

        public Builder protocolVersion(int protocolVersion) {
            meta.protocolVersion = protocolVersion;
            return this;
        }

        public Builder players(String... playerUuids) {
            meta.players = playerUuids;
            return this;
        }

        public Builder flag(String reason, int durationSeconds) {
            meta.flagReason = reason;
            meta.flagDurationSeconds = durationSeconds;
            return this;
        }

        public ReplayMetadata build() {
            return meta;
        }
    }
}
