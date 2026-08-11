package dev.watchcat;

import dev.watchcat.capture.PacketCaptureManager;
import dev.watchcat.capture.RecordingSession;
import dev.watchcat.capture.SessionManager;
import dev.watchcat.config.ConfigManager;
import dev.watchcat.flag.FlagManager;
import dev.watchcat.flag.FlagRecord;
import dev.watchcat.flag.FlagResult;
import dev.watchcat.replay.StorageUsage;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class WatchcatCommand implements CommandExecutor, TabCompleter {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final int FLAG_LIST_LIMIT = 10;

    private final Watchcat plugin;

    public WatchcatCommand(Watchcat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("watchcat.admin")) {
                    sender.sendMessage(MM.deserialize("<red>You do not have permission to use this command."));
                    return true;
                }
                plugin.getConfigManager().load();
                sender.sendMessage(MM.deserialize("<green>[Watchcat] Configuration reloaded."));
            }
            case "record" -> {
                if (!sender.hasPermission("watchcat.record")) {
                    sender.sendMessage(MM.deserialize("<red>You do not have permission to use this command."));
                    return true;
                }
                handleRecord(sender, args);
            }
            case "flag" -> {
                if (!sender.hasPermission("watchcat.flag")) {
                    sender.sendMessage(MM.deserialize("<red>You do not have permission to use this command."));
                    return true;
                }
                handleFlag(sender, args);
            }
            case "flags" -> {
                if (!sender.hasPermission("watchcat.flag")) {
                    sender.sendMessage(MM.deserialize("<red>You do not have permission to use this command."));
                    return true;
                }
                handleFlags(sender, args);
            }
            case "status" -> {
                if (!sender.hasPermission("watchcat.status")) {
                    sender.sendMessage(MM.deserialize("<red>You do not have permission to use this command."));
                    return true;
                }
                handleStatus(sender);
            }
            default -> sendHelp(sender);
        }

        return true;
    }

    private void handleFlag(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(MM.deserialize("<red>Usage: /watchcat flag <player> [duration] [reason]"));
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(MM.deserialize("<red>[Watchcat] Player '<white>" + args[1]
                    + "</white>' is not online."));
            return;
        }

        int reasonStart = 2;
        int requested = plugin.getConfigManager().getDefaultFlagDuration();
        if (args.length > 2) {
            Integer parsed = parsePositiveInt(args[2]);
            if (parsed != null) {
                requested = parsed;
                reasonStart = 3;
            }
        }

        String reason = args.length > reasonStart
                ? String.join(" ", Arrays.copyOfRange(args, reasonStart, args.length))
                : FlagManager.DEFAULT_REASON;

        int duration = FlagManager.clampDuration(requested);
        if (duration != requested) {
            sender.sendMessage(MM.deserialize("<yellow>[Watchcat] Duration <white>" + requested
                    + "s</white> is outside the supported range — using <white>" + duration
                    + "s</white>."));
        }

        FlagResult result = plugin.getFlagManager().flag(target, reason, duration, sender);
        if (!result.isRecording()) {
            sender.sendMessage(MM.deserialize("<red>[Watchcat] " + escape(result.getMessage())));
        }
    }

    private void handleFlags(CommandSender sender, String[] args) {
        String playerFilter = args.length > 1 ? args[1] : null;
        List<FlagRecord> records = plugin.getFlagManager().getRecentFlags(playerFilter, FLAG_LIST_LIMIT);

        if (records.isEmpty()) {
            sender.sendMessage(MM.deserialize("<yellow>[Watchcat] No flagged recordings"
                    + (playerFilter != null ? " for <white>" + escape(playerFilter) + "</white>" : "")
                    + " yet."));
            return;
        }

        sender.sendMessage(MM.deserialize("<gold>[Watchcat] Recent flagged recordings"
                + (playerFilter != null ? " for <white>" + escape(playerFilter) + "</white>" : "")
                + " <gray>(" + records.size() + ")"));

        for (FlagRecord record : records) {
            sender.sendMessage(MM.deserialize("<gray>  " + ago(record.getStartedAt()) + " <white>"
                    + escape(record.getPlayerName()) + "</white> " + describeStatus(record)
                    + " <gray>— " + escape(record.getReason())));

            if (record.getStatus() == FlagRecord.Status.READY) {
                sender.sendMessage(MM.deserialize("<dark_gray>      "
                        + escape(record.getFilePath())));
            } else if (record.getStatus() == FlagRecord.Status.FAILED) {
                sender.sendMessage(MM.deserialize("<dark_gray>      "
                        + escape(String.valueOf(record.getFailure()))));
            }
        }
    }

    private void handleStatus(CommandSender sender) {
        PacketCaptureManager capture = plugin.getPacketCaptureManager();
        SessionManager sessions = capture.getSessionManager();
        ConfigManager config = plugin.getConfigManager();

        sender.sendMessage(MM.deserialize("<gold>[Watchcat] <white>v"
                + plugin.getPluginMeta().getVersion() + " <gray>status"));

        if (!plugin.isRecordingAvailable()) {
            sender.sendMessage(MM.deserialize("<gray>  recording: <red>unavailable</red> "
                    + "<gray>— ProtocolLib is not installed or not enabled"));
        } else if (!capture.hasConfigurationPrologue()) {
            sender.sendMessage(MM.deserialize("<gray>  recording: <yellow>waiting</yellow> "
                    + "<gray>— no player has joined since Watchcat loaded, so replays cannot open yet"));
        } else {
            sender.sendMessage(MM.deserialize("<gray>  recording: <green>ready</green> <gray>("
                    + capture.getListenedPacketTypes() + " packet types)"));
        }

        List<RecordingSession> ordinary = sessions.getOrdinarySessions();
        List<RecordingSession> flagged = sessions.getFlagSessions();

        sender.sendMessage(MM.deserialize("<gray>  always-record: "
                + (config.isAlwaysRecord() ? "<green>on</green>" : "<red>off</red>")
                + " <gray>| buffer: <white>" + config.getBufferMinutes() + "m</white>"
                + " <gray>| default flag: <white>" + config.getDefaultFlagDuration() + "s"));

        sender.sendMessage(MM.deserialize("<gray>  sessions: <white>" + ordinary.size()
                + "</white> ordinary, <white>" + flagged.size() + "</white> flag"));
        for (RecordingSession session : ordinary) {
            sender.sendMessage(MM.deserialize(sessionLine(session, "ordinary")));
        }
        for (RecordingSession session : flagged) {
            sender.sendMessage(MM.deserialize(sessionLine(session, "flag")));
        }

        StorageUsage.Snapshot usage = plugin.getStorageUsage().getLatest();
        int capMb = config.getMaxStorageMb();
        if (!usage.isMeasured()) {
            sender.sendMessage(MM.deserialize("<gray>  storage: <yellow>not measured yet</yellow>"
                    + " <gray>(cap " + capMb + " MB)"));
        } else {
            double usedMb = usage.getMegabytes();
            int percent = capMb > 0 ? (int) Math.round(usedMb / capMb * 100.0) : 0;
            String colour = percent >= 90 ? "red" : percent >= 70 ? "yellow" : "green";
            sender.sendMessage(MM.deserialize("<gray>  storage: <" + colour + ">"
                    + String.format(Locale.ROOT, "%.1f", usedMb) + " MB</" + colour + ">"
                    + " <gray>of " + capMb + " MB (" + percent + "%), <white>" + usage.getFiles()
                    + "</white> replay(s), measured " + ago(usage.getMeasuredAt())));
        }

        if (!config.isAutoCleanup()) {
            sender.sendMessage(MM.deserialize("<gray>  cleanup: <red>off</red>"
                    + " <dark_gray>— replays are kept forever; set auto-cleanup: true to change that"));
        } else {
            sender.sendMessage(MM.deserialize("<gray>  cleanup: <green>on</green> <gray>— deleting after <white>"
                    + config.getRetentionHours() + "h</white>, or sooner over "
                    + capMb + " MB (never under <white>" + config.getMinRetentionHours() + "h</white>)"));
        }
    }

    private String sessionLine(RecordingSession session, String kind) {
        long seconds = (System.currentTimeMillis() - session.getStartTimestamp()) / 1000L;
        return "<dark_gray>      " + escape(session.getPlayerName()) + " <gray>(" + kind + ", "
                + session.getPacketCount() + " packets, " + seconds + "s"
                + (session.isTruncated() ? ", <yellow>truncated</yellow><gray>" : "") + ")";
    }

    private String describeStatus(FlagRecord record) {
        return switch (record.getStatus()) {
            case RECORDING -> "<yellow>recording</yellow> <gray>(" + record.getSecondsRemaining()
                    + "s left of " + record.getDurationSeconds() + "s"
                    + (record.getExtensions() > 0 ? ", extended ×" + record.getExtensions() : "") + ")";
            case SAVING -> "<aqua>saving</aqua>";
            case READY -> "<green>ready</green>"
                    + (record.isTruncated() ? " <yellow>(truncated)</yellow>" : "");
            case FAILED -> "<red>failed</red>";
        };
    }

    private static Integer parsePositiveInt(String token) {
        try {
            int value = Integer.parseInt(token);
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String ago(long epochMillis) {
        Duration elapsed = Duration.between(Instant.ofEpochMilli(epochMillis), Instant.now());
        long seconds = Math.max(0, elapsed.getSeconds());
        if (seconds < 60) {
            return seconds + "s ago";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m ago";
        }
        if (seconds < 86400) {
            return (seconds / 3600) + "h ago";
        }
        return (seconds / 86400) + "d ago";
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("<", "\\<");
    }

    private void handleRecord(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(MM.deserialize("<red>Usage: /watchcat record <player> start|stop"));
            return;
        }

        if (!plugin.isRecordingAvailable()) {
            sender.sendMessage(MM.deserialize("<red>[Watchcat] Recording is unavailable — ProtocolLib is not installed."));
            return;
        }

        String playerName = args[1];
        String action = args[2].toLowerCase();

        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            sender.sendMessage(MM.deserialize("<red>[Watchcat] Player '<white>" + playerName + "</white>' is not online."));
            return;
        }

        SessionManager sessions = plugin.getPacketCaptureManager().getSessionManager();

        switch (action) {
            case "start" -> {
                if (sessions.hasActiveSession(target.getUniqueId())) {
                    sender.sendMessage(MM.deserialize("<yellow>[Watchcat] A recording session is already active for "
                            + target.getName() + "."));
                    return;
                }
                int bufferMinutes = plugin.getConfigManager().getBufferMinutes();
                sessions.startSession(target.getUniqueId(), target.getName(), bufferMinutes);
                sender.sendMessage(MM.deserialize("<green>[Watchcat] Started recording session for "
                        + target.getName() + "."));
                plugin.getLogger().info("Recording session started for " + target.getName()
                        + " (" + target.getUniqueId() + ") by " + sender.getName());
            }
            case "stop" -> {
                RecordingSession session = sessions.stopSession(target.getUniqueId());
                if (session == null) {
                    sender.sendMessage(MM.deserialize("<yellow>[Watchcat] No active recording session for "
                            + target.getName() + "."));
                    return;
                }
                long durationSec = (System.currentTimeMillis() - session.getStartTimestamp()) / 1000;
                sender.sendMessage(MM.deserialize("<green>[Watchcat] Stopped recording session for "
                        + target.getName() + ". Duration: " + durationSec + "s, Packets captured: "
                        + session.getPacketCount()));
                plugin.getLogger().info("Recording session stopped for " + target.getName()
                        + " (" + target.getUniqueId() + ") — " + session.getPacketCount()
                        + " packets in " + durationSec + "s. Stopped by " + sender.getName());
            }
            default -> sender.sendMessage(MM.deserialize("<red>Usage: /watchcat record <player> start|stop"));
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<gold>[Watchcat] <white>v"
                + plugin.getPluginMeta().getVersion()));
        sender.sendMessage(MM.deserialize("<yellow>Usage:"));
        if (sender.hasPermission("watchcat.admin")) {
            sender.sendMessage(MM.deserialize("<yellow>  /watchcat reload<gray> — Reload configuration"));
        }
        if (sender.hasPermission("watchcat.record")) {
            sender.sendMessage(MM.deserialize("<yellow>  /watchcat record <player> start|stop<gray> — Start/stop recording a player"));
        }
        if (sender.hasPermission("watchcat.flag")) {
            sender.sendMessage(MM.deserialize("<yellow>  /watchcat flag <player> [duration] [reason]<gray> — Record the next "
                    + plugin.getConfigManager().getDefaultFlagDuration() + "s of a suspect"));
            sender.sendMessage(MM.deserialize("<yellow>  /watchcat flags [player]<gray> — List recent flagged recordings"));
        }
        if (sender.hasPermission("watchcat.status")) {
            sender.sendMessage(MM.deserialize("<yellow>  /watchcat status<gray> — Recording availability, sessions and storage"));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            if (sender.hasPermission("watchcat.admin")) {
                completions.add("reload");
            }
            if (sender.hasPermission("watchcat.record")) {
                completions.add("record");
            }
            if (sender.hasPermission("watchcat.flag")) {
                completions.add("flag");
                completions.add("flags");
            }
            if (sender.hasPermission("watchcat.status")) {
                completions.add("status");
            }
            return filterPrefix(completions, args[0]);
        }

        String sub = args[0].toLowerCase();
        boolean recordSub = sub.equals("record") && sender.hasPermission("watchcat.record");
        boolean flagSub = (sub.equals("flag") || sub.equals("flags")) && sender.hasPermission("watchcat.flag");

        if (args.length == 2 && (recordSub || flagSub)) {
            return filterPrefix(onlinePlayerNames(), args[1]);
        }

        if (args.length == 3 && recordSub) {
            return filterPrefix(List.of("start", "stop"), args[2]);
        }

        if (args.length == 3 && sub.equals("flag") && sender.hasPermission("watchcat.flag")) {
            return filterPrefix(
                    List.of("30", "60", "120", "300", FlagManager.DEFAULT_REASON), args[2]);
        }

        return Collections.emptyList();
    }

    private List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toList());
    }

    private List<String> filterPrefix(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(lower))
                .collect(Collectors.toList());
    }
}
