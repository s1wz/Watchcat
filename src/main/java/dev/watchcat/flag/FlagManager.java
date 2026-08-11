package dev.watchcat.flag;

import dev.watchcat.Watchcat;
import dev.watchcat.capture.RecordingSession;
import dev.watchcat.capture.SessionManager;
import dev.watchcat.flag.event.FlagRecordingReadyEvent;
import dev.watchcat.flag.event.FlagRecordingStartedEvent;
import dev.watchcat.replay.ReplayEntry;
import dev.watchcat.replay.ReplayIndex;
import dev.watchcat.replay.ReplaySaver;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

public final class FlagManager {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static final int MIN_DURATION_SECONDS = 1;

    public static final int MAX_DURATION_SECONDS = 3600;

    private static final int MAX_HISTORY = 100;

    public static final String DEFAULT_REASON = "manual-flag";

    private final Watchcat plugin;
    private final SessionManager sessions;
    private final ReplayIndex replayIndex;
    private final Path replaysRoot;

    private final ConcurrentMap<UUID, ActiveFlag> active = new ConcurrentHashMap<>();

    private final ConcurrentMap<RecordingSession, FlagRecord> pendingSaves = new ConcurrentHashMap<>();

    private final Deque<FlagRecord> history = new ConcurrentLinkedDeque<>();

    public FlagManager(Watchcat plugin, SessionManager sessions, ReplaySaver replaySaver,
                       ReplayIndex replayIndex) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.replayIndex = replayIndex;
        this.replaysRoot = plugin.getDataFolder().toPath().resolve("replays");

        replaySaver.addListener(this::onReplaySaved);
    }

    private static final class ActiveFlag {
        final FlagRecord record;
        volatile RecordingSession session;
        volatile BukkitTask stopTask;

        ActiveFlag(FlagRecord record) {
            this.record = record;
        }
    }

    public FlagResult flag(Player player, String reason, int durationSeconds, CommandSender issuer) {
        if (player == null) {
            return FlagResult.rejected("No player was given.");
        }
        if (!plugin.isRecordingAvailable()) {
            return FlagResult.rejected("Recording is unavailable — ProtocolLib is not installed.");
        }

        String cleanReason = normalizeReason(reason);
        int duration = clampDuration(durationSeconds);

        FlagResult result;
        boolean extension;
        ActiveFlag opened = null;

        synchronized (this) {
            ActiveFlag existing = active.get(player.getUniqueId());
            if (existing != null) {
                extendFlag(existing, cleanReason, duration);
                result = FlagResult.extended(existing.record);
                extension = true;
            } else {
                opened = openFlag(player, cleanReason, duration, issuer);
                result = FlagResult.started(opened.record);
                extension = false;
            }
        }

        if (opened != null) {
            ActiveFlag flag = opened;
            runOnMain(() -> beginRecording(flag));
        }

        announceStart(result.getRecord(), extension, issuer, duration);
        FlagRecord record = result.getRecord();
        runOnMain(() -> plugin.getServer().getPluginManager()
                .callEvent(new FlagRecordingStartedEvent(record, extension)));
        return result;
    }

    private ActiveFlag openFlag(Player player, String reason, int duration, CommandSender issuer) {
        UUID issuerUuid = issuer instanceof Player issuingPlayer ? issuingPlayer.getUniqueId() : null;
        String issuerName = issuer != null ? issuer.getName() : "api";

        FlagRecord record = new FlagRecord(player.getUniqueId(), player.getName(),
                issuerName, issuerUuid, reason, duration);
        ActiveFlag flag = new ActiveFlag(record);
        active.put(player.getUniqueId(), flag);
        pushHistory(record);
        return flag;
    }

    private void extendFlag(ActiveFlag flag, String reason, int duration) {
        flag.record.extend(duration, reason);

        RecordingSession session = flag.session;
        if (session != null) {
            session.setFlagReason(flag.record.getReason());
        }

        if (flag.stopTask != null) {
            flag.stopTask.cancel();
            flag.stopTask = null;
            scheduleStop(flag);
        }
    }

    private void beginRecording(ActiveFlag flag) {
        FlagRecord record = flag.record;

        Player player = plugin.getServer().getPlayer(record.getPlayerUuid());
        if (player == null) {
            failFlag(flag, "the player disconnected before recording could start");
            return;
        }

        try {
            flag.session = sessions.startFlagSession(player.getUniqueId(), player.getName(),
                    record.getReason(), record.getDurationSeconds(), this::onFlagSessionStopped);
        } catch (RuntimeException e) {
            failFlag(flag, "could not start the recording: " + e.getMessage());
            return;
        }
        scheduleStop(flag);
    }

    private void scheduleStop(ActiveFlag flag) {
        long remaining = flag.record.getExpiresAt() - System.currentTimeMillis();
        long ticks = Math.max(1L, remaining / 50L);
        flag.stopTask = plugin.getServer().getScheduler()
                .runTaskLater(plugin, () -> expire(flag), ticks);
    }

    private void expire(ActiveFlag flag) {
        flag.stopTask = null;
        if (active.get(flag.record.getPlayerUuid()) != flag) {
            return;
        }
        sessions.stopFlagSession(flag.record.getPlayerUuid());
    }

    private void failFlag(ActiveFlag flag, String why) {
        active.remove(flag.record.getPlayerUuid(), flag);
        flag.record.markFailed(why);

        plugin.getLogger().warning("Flag recording for " + flag.record.getPlayerName()
                + " produced nothing — " + why);
        notifyIssuer(flag.record, "<red>[Watchcat] Flag recording for <white>"
                + flag.record.getPlayerName() + "</white> failed: " + escape(why));
        plugin.getServer().getPluginManager().callEvent(new FlagRecordingReadyEvent(flag.record));
    }

    private void onFlagSessionStopped(RecordingSession session) {
        ActiveFlag flag = active.get(session.getPlayerUuid());
        if (flag == null || flag.session != session) {
            return;
        }
        active.remove(session.getPlayerUuid(), flag);

        BukkitTask stopTask = flag.stopTask;
        if (stopTask != null) {
            stopTask.cancel();
            flag.stopTask = null;
        }

        flag.record.markSaving();
        pendingSaves.put(session, flag.record);
    }

    private void onReplaySaved(RecordingSession session, Path file, Throwable error) {
        FlagRecord record = pendingSaves.remove(session);
        if (record == null) {
            return;
        }

        if (error != null || file == null) {
            String why = error != null ? String.valueOf(error.getMessage()) : "no file was written";
            record.markFailed(why);
            plugin.getLogger().warning("Flagged recording of " + record.getPlayerName()
                    + " could not be saved — " + why);
        } else {
            record.markReady(file.toAbsolutePath().toString(), session.isTruncated());
            plugin.getLogger().info("Flagged recording of " + record.getPlayerName()
                    + " is ready for review (reason: " + record.getReason() + ") — " + file
                    + (session.isTruncated() ? " [truncated: the packet buffer filled before the "
                    + "requested duration elapsed]" : ""));
        }

        runOnMain(() -> {
            announceReady(record);
            plugin.getServer().getPluginManager().callEvent(new FlagRecordingReadyEvent(record));
        });
    }

    public List<FlagRecord> getRecentFlags(String playerName, int limit) {
        List<FlagRecord> out = new ArrayList<>();

        for (FlagRecord record : history) {
            if (matches(record.getPlayerName(), playerName)) {
                out.add(record);
            }
            if (out.size() >= limit) {
                return out;
            }
        }

        List<ReplayEntry> flaggedEntries = new ArrayList<>();
        for (ReplayEntry entry : replayIndex.getEntries()) {
            if (entry.isFlagged() && matches(entry.getPlayerName(), playerName)) {
                flaggedEntries.add(entry);
            }
        }
        flaggedEntries.sort((a, b) -> Long.compare(b.getStartTime(), a.getStartTime()));

        for (ReplayEntry entry : flaggedEntries) {
            if (out.size() >= limit) {
                break;
            }
            String path = resolve(entry);
            if (isAlreadyListed(out, path)) {
                continue;
            }
            out.add(fromIndex(entry, path));
        }
        return out;
    }

    public FlagRecord getActiveFlag(UUID playerUuid) {
        ActiveFlag flag = active.get(playerUuid);
        return flag != null ? flag.record : null;
    }

    public int getActiveCount() {
        return active.size();
    }

    public void shutdown() {
        for (ActiveFlag flag : List.copyOf(active.values())) {
            BukkitTask stopTask = flag.stopTask;
            if (stopTask != null) {
                stopTask.cancel();
                flag.stopTask = null;
            }
            sessions.stopFlagSession(flag.record.getPlayerUuid());
        }
    }

    private static boolean matches(String candidate, String filter) {
        return filter == null || (candidate != null && candidate.equalsIgnoreCase(filter));
    }

    private boolean isAlreadyListed(List<FlagRecord> listed, String path) {
        for (FlagRecord record : listed) {
            if (path.equals(record.getFilePath())) {
                return true;
            }
        }
        return false;
    }

    private String resolve(ReplayEntry entry) {
        return replaysRoot.resolve(entry.getRelativePath()).toAbsolutePath().toString();
    }

    private FlagRecord fromIndex(ReplayEntry entry, String path) {
        FlagRecord record = new FlagRecord(entry.getPlayerUuid(), entry.getPlayerName(),
                "unknown", null, entry.getFlagReason(), entry.getFlagDurationSeconds());
        record.markReady(path, false);
        return record;
    }

    private void pushHistory(FlagRecord record) {
        history.addFirst(record);
        while (history.size() > MAX_HISTORY) {
            history.pollLast();
        }
    }

    private void announceStart(FlagRecord record, boolean extension, CommandSender issuer, int duration) {
        String reason = record.getReason();

        if (extension) {
            plugin.getLogger().info("Extended the flag recording of " + record.getPlayerName()
                    + " to " + duration + "s (extension #" + record.getExtensions()
                    + ", reason now: " + reason + ") — requested by "
                    + (issuer != null ? issuer.getName() : "api"));
        } else {
            plugin.getLogger().info("Flag recording started for " + record.getPlayerName()
                    + " — " + duration + "s, reason: " + reason + ", issued by " + record.getIssuedBy());
        }

        String message = extension
                ? "<yellow>[Watchcat] <white>" + record.getPlayerName()
                        + "</white> is already being recorded — reset to <white>" + duration
                        + "s</white> (reason: <white>" + escape(reason) + "</white>)."
                : "<green>[Watchcat] Recording <white>" + record.getPlayerName()
                        + "</white> for <white>" + duration + "s</white> (reason: <white>"
                        + escape(reason) + "</white>). You will be told when it is ready.";

        if (issuer != null) {
            issuer.sendMessage(MM.deserialize(message));
        } else {
            notifyIssuer(record, message);
        }
    }

    private void announceReady(FlagRecord record) {
        if (record.getStatus() != FlagRecord.Status.READY) {
            notifyIssuer(record, "<red>[Watchcat] The flagged recording of <white>"
                    + record.getPlayerName() + "</white> produced no replay: "
                    + escape(String.valueOf(record.getFailure())));
            return;
        }

        String truncated = record.isTruncated()
                ? " <yellow>(truncated — the packet buffer filled early)</yellow>"
                : "";
        notifyIssuer(record, "<green>[Watchcat] Flagged recording of <white>"
                + record.getPlayerName() + "</white> is ready for review"
                + truncated + "<newline><gray>  reason: <white>" + escape(record.getReason())
                + "</white><newline>  file: <white>" + escape(record.getFilePath()) + "</white>");
    }

    private void notifyIssuer(FlagRecord record, String miniMessage) {
        UUID issuerUuid = record.getIssuedByUuid();
        if (issuerUuid == null) {
            return;
        }
        Player issuer = plugin.getServer().getPlayer(issuerUuid);
        if (issuer != null) {
            issuer.sendMessage(MM.deserialize(miniMessage));
        }
    }

    public static int clampDuration(int durationSeconds) {
        return Math.max(MIN_DURATION_SECONDS, Math.min(MAX_DURATION_SECONDS, durationSeconds));
    }

    public static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return DEFAULT_REASON;
        }
        String cleaned = reason.replaceAll("[\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isEmpty() ? DEFAULT_REASON : cleaned;
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("<", "\\<");
    }

    private void runOnMain(Runnable task) {
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
            return;
        }
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, task);
    }
}
