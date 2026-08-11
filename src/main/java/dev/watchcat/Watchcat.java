package dev.watchcat;

import dev.watchcat.api.WatchcatAPI;
import dev.watchcat.capture.PacketCaptureManager;
import dev.watchcat.config.ConfigManager;
import dev.watchcat.flag.FlagManager;
import dev.watchcat.replay.ReplayIndex;
import dev.watchcat.replay.ReplayCleanup;
import dev.watchcat.replay.ReplaySaver;
import dev.watchcat.replay.ReplayWriter;
import dev.watchcat.replay.StorageUsage;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

public final class Watchcat extends JavaPlugin {
    private static final long STORAGE_REFRESH_TICKS = 20L * 60L * 5L;
    private static final long CLEANUP_INITIAL_DELAY_TICKS = 20L * 60L;
    private static final long CLEANUP_INTERVAL_TICKS = 20L * 60L * 30L;

    private static Watchcat instance;
    private static volatile WatchcatAPI api;

    private ConfigManager configManager;
    private PacketCaptureManager packetCaptureManager;
    private FlagManager flagManager;
    private StorageUsage storageUsage;
    private ReplayCleanup replayCleanup;
    private boolean recordingAvailable;

    @Override
    public void onEnable() {
        instance = this;

        configManager = new ConfigManager(this);
        configManager.load();

        recordingAvailable = isPluginPresent("ProtocolLib");
        if (!recordingAvailable) {
            getLogger().warning("========================================");
            getLogger().warning(" ProtocolLib not found or not enabled!");
            getLogger().warning(" Packet recording features are DISABLED.");
            getLogger().warning(" Install ProtocolLib to enable replays.");
            getLogger().warning("========================================");
        }

        ReplayWriter replayWriter = ReplayWriter.forServer(getServer());
        Path replaysRoot = getDataFolder().toPath().resolve("replays");
        ReplayIndex replayIndex = new ReplayIndex(replaysRoot.resolve("index.json"));
        storageUsage = new StorageUsage(replaysRoot, getLogger());
        ReplaySaver replaySaver = new ReplaySaver(replayWriter, replayIndex, replaysRoot,
                getServer().getName(), getLogger(), storageUsage);

        packetCaptureManager = new PacketCaptureManager(this, recordingAvailable, replaySaver);

        flagManager = new FlagManager(this, packetCaptureManager.getSessionManager(),
                replaySaver, replayIndex);
        api = new WatchcatAPI(this, flagManager);
        getServer().getServicesManager().register(WatchcatAPI.class, api, this, ServicePriority.Normal);

        WatchcatCommand command = new WatchcatCommand(this);
        getCommand("watchcat").setExecutor(command);
        getCommand("watchcat").setTabCompleter(command);

        replayCleanup = new ReplayCleanup(replaysRoot, replayIndex, storageUsage, getLogger());

        getServer().getScheduler().runTaskTimerAsynchronously(this,
                storageUsage::refresh, 20L, STORAGE_REFRESH_TICKS);
        getServer().getScheduler().runTaskTimerAsynchronously(this,
                () -> replayCleanup.run(configManager.getCleanupPolicy()),
                CLEANUP_INITIAL_DELAY_TICKS, CLEANUP_INTERVAL_TICKS);

        getLogger().info("Watchcat v" + getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (flagManager != null) {
            flagManager.shutdown();
        }
        if (packetCaptureManager != null) {
            packetCaptureManager.shutdown();
        }
        getServer().getServicesManager().unregisterAll(this);

        getLogger().info("Watchcat disabled.");
        api = null;
        instance = null;
    }

    private boolean isPluginPresent(String pluginName) {
        var plugin = getServer().getPluginManager().getPlugin(pluginName);
        return plugin != null && plugin.isEnabled();
    }

    public static Watchcat getInstance() {
        return instance;
    }

    public static WatchcatAPI getAPI() {
        WatchcatAPI current = api;
        if (current == null) {
            throw new IllegalStateException("Watchcat is not enabled — declare it as a "
                    + "dependency, or load the API through the services manager instead.");
        }
        return current;
    }

    public FlagManager getFlagManager() {
        return flagManager;
    }

    public StorageUsage getStorageUsage() {
        return storageUsage;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public PacketCaptureManager getPacketCaptureManager() {
        return packetCaptureManager;
    }

    public boolean isRecordingAvailable() {
        return recordingAvailable;
    }
}
