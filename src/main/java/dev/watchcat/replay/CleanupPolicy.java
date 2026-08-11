package dev.watchcat.replay;

public final class CleanupPolicy {

    private final boolean enabled;
    private final long retentionMillis;
    private final long minRetentionMillis;
    private final long maxStorageBytes;
    private final boolean protectFlagged;

    public CleanupPolicy(boolean enabled, int retentionHours, int minRetentionHours,
                         int maxStorageMb, boolean protectFlagged) {
        this.enabled = enabled;
        this.retentionMillis = Math.max(0L, retentionHours) * 3_600_000L;
        this.minRetentionMillis = Math.max(0L, minRetentionHours) * 3_600_000L;
        this.maxStorageBytes = Math.max(0L, maxStorageMb) * 1024L * 1024L;
        this.protectFlagged = protectFlagged;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getRetentionMillis() {
        return retentionMillis;
    }

    public long getMinRetentionMillis() {
        return minRetentionMillis;
    }

    public long getMaxStorageBytes() {
        return maxStorageBytes;
    }

    public boolean isProtectFlagged() {
        return protectFlagged;
    }
}
