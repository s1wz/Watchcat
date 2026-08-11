package dev.watchcat.capture;

import dev.watchcat.Watchcat;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerAnimationEvent;

import java.util.List;
import java.util.Locale;

public final class PlayerActionListener implements Listener {
    private final Watchcat plugin;
    private final SessionManager sessionManager;

    public PlayerActionListener(Watchcat plugin, SessionManager sessionManager) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        List<RecordingSession> sessions = activeSessions(event.getPlayer());
        if (sessions.isEmpty()) {
            return;
        }

        String type = String.valueOf(event.getAnimationType()).toUpperCase(Locale.ROOT);
        int action = type.contains("OFF")
                ? PlayerStateRecorder.SWING_OFF_HAND
                : PlayerStateRecorder.SWING_MAIN_HAND;

        PlayerStateRecorder.recordSwing(event.getPlayer(), sessions, action);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        recordBlockChange(event.getPlayer(), event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        recordBlockChange(event.getPlayer(), event.getBlock().getLocation());
    }

    private void recordBlockChange(Player player, Location location) {
        if (activeSessions(player).isEmpty()) {
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin,
                () -> PlayerStateRecorder.recordBlockUpdate(player, activeSessions(player), location));
    }

    private List<RecordingSession> activeSessions(Player player) {
        return sessionManager.getActiveSessions(player.getUniqueId());
    }
}
