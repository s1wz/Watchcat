package dev.watchcat.capture;

import dev.watchcat.Watchcat;
import dev.watchcat.replay.ReplaySaver;
import org.bukkit.entity.Player;

import java.util.List;

public final class PacketCaptureManager {

    private static final long TRIM_INTERVAL_TICKS = 100L;
    private static final long SAMPLE_INTERVAL_TICKS = 2L;
    private static final long EAGER_EQUIPMENT_WINDOW_MILLIS = 1000L;
    private static final int EQUIPMENT_SAMPLE_EVERY = 5;

    private final Watchcat plugin;
    private final boolean active;
    private final SessionManager sessionManager;
    private final CaptureBackend backend;

    private long sampleTick;

    public PacketCaptureManager(Watchcat plugin, boolean protocolLibAvailable, ReplaySaver replaySaver) {
        this.plugin = plugin;
        this.active = protocolLibAvailable;

        this.sessionManager = new SessionManager(
                session -> replaySaver.save(session, session.isFlagged()),
                (session, reason) -> {
                    plugin.getLogger().info("Flushing recording buffer for "
                            + session.getPlayerName() + " (Reason: " + reason + ")");
                    replaySaver.save(session, plugin.getConfigManager().isProtectFlaggedReplays());
                },
                null);

        this.backend = protocolLibAvailable ? new ProtocolCapture(plugin, sessionManager) : null;

        if (backend != null) {
            sessionManager.setPrologueSource(backend.getPrologueSource());
            initialize();
        }
    }

    private void initialize() {
        plugin.getServer().getPluginManager().registerEvents(
                new SessionCleanupListener(plugin, sessionManager), plugin);
        plugin.getServer().getPluginManager().registerEvents(
                new PlayerActionListener(plugin, sessionManager), plugin);

        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin,
                sessionManager::trimAll, TRIM_INTERVAL_TICKS, TRIM_INTERVAL_TICKS);
        plugin.getServer().getScheduler().runTaskTimer(plugin,
                this::samplePlayerState, SAMPLE_INTERVAL_TICKS, SAMPLE_INTERVAL_TICKS);

        if (plugin.getConfigManager().isAlwaysRecord()) {
            int bufferMinutes = plugin.getConfigManager().getBufferMinutes();
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                try {
                    sessionManager.startSession(player.getUniqueId(), player.getName(), bufferMinutes);
                } catch (IllegalStateException ignored) {
                }
            }
        }
    }

    private void samplePlayerState() {
        boolean scheduledSample = ++sampleTick % EQUIPMENT_SAMPLE_EVERY == 0;
        long now = System.currentTimeMillis();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            List<RecordingSession> sessions = sessionManager.getActiveSessions(player.getUniqueId());
            if (sessions.isEmpty()) {
                continue;
            }
            PlayerStateRecorder.recordMotion(player, sessions);

            if (scheduledSample || isYoung(sessions, now)) {
                PlayerStateRecorder.recordEquipment(player, sessions);
            }
        }
    }

    private static boolean isYoung(List<RecordingSession> sessions, long now) {
        for (RecordingSession session : sessions) {
            if (now - session.getStartTimestamp() < EAGER_EQUIPMENT_WINDOW_MILLIS) {
                return true;
            }
        }
        return false;
    }

    public int getListenedPacketTypes() {
        return backend != null ? backend.getListenedPacketTypes() : 0;
    }

    public boolean hasConfigurationPrologue() {
        return backend != null && backend.getPrologueSource().hasConfigurationPrologue();
    }

    public boolean hasLoginPrologue() {
        return backend != null && backend.getPrologueSource().hasLoginPrologue();
    }

    public String getLoginPrologueFailure() {
        return backend != null ? backend.getPrologueSource().getLoginPrologueFailure() : "unknown";
    }

    public void shutdown() {
        if (backend == null) {
            return;
        }

        int count = sessionManager.getActiveCount();
        sessionManager.stopAll();
        backend.shutdown();

        plugin.getLogger().info("PacketCaptureManager shut down (" + count + " session(s) stopped).");
    }

    public boolean isActive() {
        return active;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }
}
