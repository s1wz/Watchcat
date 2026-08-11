package dev.watchcat.config;

import dev.watchcat.Watchcat;
import dev.watchcat.flag.FlagManager;
import dev.watchcat.replay.CleanupPolicy;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public final class ConfigManager {
    private final Watchcat plugin;

    private boolean alwaysRecord;
    private int bufferMinutes;
    private int defaultFlagDuration;
    private boolean autoCleanup;
    private int retentionHours;
    private int maxStorageMb;
    private int minRetentionHours;
    private boolean protectFlaggedReplays;
    private List<String> recordedPacketTypes;

    public ConfigManager(Watchcat plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        FileConfiguration cfg = plugin.getConfig();

        alwaysRecord          = cfg.getBoolean("always-record", false);
        bufferMinutes         = cfg.getInt("buffer-minutes", 5);
        defaultFlagDuration   = FlagManager.clampDuration(cfg.getInt("default-flag-duration", 120));
        autoCleanup           = cfg.getBoolean("auto-cleanup", false);
        retentionHours        = Math.max(0, cfg.getInt("retention-hours", 36));
        maxStorageMb          = cfg.getInt("max-storage-mb", 5000);
        minRetentionHours     = Math.max(0, cfg.getInt("min-retention-hours", 12));
        protectFlaggedReplays = cfg.getBoolean("protect-flagged-replays", true);
        recordedPacketTypes   = cfg.getStringList("recorded-packet-types");

        plugin.getLogger().info("Configuration loaded (" + recordedPacketTypes.size() + " packet types registered).");
    }

    public boolean isAlwaysRecord() {
        return alwaysRecord;
    }

    public int getBufferMinutes() {
        return bufferMinutes;
    }

    public int getDefaultFlagDuration() {
        return defaultFlagDuration;
    }

    public boolean isAutoCleanup() {
        return autoCleanup;
    }

    public int getRetentionHours() {
        return retentionHours;
    }

    public int getMaxStorageMb() {
        return maxStorageMb;
    }

    public CleanupPolicy getCleanupPolicy() {
        return new CleanupPolicy(autoCleanup, retentionHours, minRetentionHours,
                maxStorageMb, protectFlaggedReplays);
    }

    public int getMinRetentionHours() {
        return minRetentionHours;
    }

    public boolean isProtectFlaggedReplays() {
        return protectFlaggedReplays;
    }

    public List<String> getRecordedPacketTypes() {
        return recordedPacketTypes;
    }
}
