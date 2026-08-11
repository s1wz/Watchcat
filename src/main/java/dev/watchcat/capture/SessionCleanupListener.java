package dev.watchcat.capture;

import dev.watchcat.Watchcat;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public final class SessionCleanupListener implements Listener {
    private final Watchcat plugin;
    private final SessionManager sessionManager;

    public SessionCleanupListener(Watchcat plugin, SessionManager sessionManager) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (plugin.getConfigManager().isAlwaysRecord()) {
            UUID uuid = event.getPlayer().getUniqueId();
            String name = event.getPlayer().getName();
            int bufferMinutes = plugin.getConfigManager().getBufferMinutes();

            try {
                sessionManager.startSession(uuid, name, bufferMinutes);
                plugin.getLogger().fine("Auto-started recording session for " + name);
            } catch (IllegalStateException ignored) {
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        RecordingSession session = sessionManager.handlePlayerQuit(uuid);
        if (session != null) {
            long durationSec = (System.currentTimeMillis() - session.getStartTimestamp()) / 1000;
            plugin.getLogger().warning("Auto-stopped recording session for "
                    + event.getPlayer().getName() + " (disconnected) — "
                    + session.getPacketCount() + " packets in " + durationSec + "s.");
        }
    }
}
