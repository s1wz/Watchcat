package dev.watchcat.api;

import dev.watchcat.Watchcat;
import dev.watchcat.flag.FlagManager;
import dev.watchcat.flag.FlagRecord;
import dev.watchcat.flag.FlagResult;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Watchcat's public API, for anticheat and staff plugins.
 *
 * <h2>Obtaining it</h2>
 * Prefer Bukkit's services manager, which returns {@code null} instead of throwing when
 * Watchcat is not installed, so a soft dependency needs no reflection:
 * <pre>{@code
 * WatchcatAPI watchcat = getServer().getServicesManager().load(WatchcatAPI.class);
 * if (watchcat != null) {
 *     watchcat.flagMoment(player, "reach: 3.4 blocks");
 * }
 * }</pre>
 * {@link Watchcat#getAPI()} is equivalent but throws if Watchcat is not enabled, which
 * suits a hard dependency declaring {@code depend: [Watchcat]}.
 *
 * <h2>What flagging does</h2>
 * Flagging starts a dedicated recording of the player and, when it expires, writes a
 * ReplayMod-compatible {@code .mcpr} file to {@code plugins/Watchcat/replays/flagged/}
 * for a human to review. It is <b>forward-looking</b>: the recording begins at the moment
 * of the call and captures what the player does <i>next</i>. It is not a dump of a prior
 * buffer, and it works whether or not {@code always-record} is enabled.
 * <p>
 * A flag recording runs alongside any recording already in progress rather than replacing
 * it. Flagging a player who is already being flag-recorded resets that recording's clock
 * instead of opening a second one, and the new reason is added to the ones already
 * recorded, so the saved file is named after every detection in the window.
 *
 * <h2>Threading</h2>
 * Every method is safe to call from any thread, including an anticheat's own detection
 * thread. Nothing here throws for an ordinary rejection — a detection is a report, not a
 * request that has to be guarded — so inspect the returned {@link FlagResult} instead of
 * writing a try/catch around your detection loop.
 *
 * <h2>Observability</h2>
 * The flagged player is told nothing. No packet is sent to them and no gameplay behaviour
 * changes; the recording is assembled server-side from state the server already has.
 * Notifications go to the issuing staff member, the console, and any plugin listening for
 * {@link dev.watchcat.flag.event.FlagRecordingStartedEvent} and
 * {@link dev.watchcat.flag.event.FlagRecordingReadyEvent} — the latter carries the path of
 * the finished file, which is how you attach a replay to your own report.
 */
public final class WatchcatAPI {

    private final Watchcat plugin;
    private final FlagManager flags;

    public WatchcatAPI(Watchcat plugin, FlagManager flags) {
        this.plugin = plugin;
        this.flags = flags;
    }

    /**
     * Flags a player and records what they do next, for the server's configured
     * {@code default-flag-duration}.
     *
     * @param player the player to record
     * @param reason why they were flagged, for example {@code "reach: 3.4 blocks"}. This
     *               reaches the replay's filename and its metadata, and is what a reviewer
     *               reads first, so make it specific enough to be worth reading.
     * @return what happened; never null
     */
    public FlagResult flagMoment(Player player, String reason) {
        return flagMoment(player, reason, plugin.getConfigManager().getDefaultFlagDuration());
    }

    /**
     * Flags a player for an explicit duration.
     *
     * @param durationSeconds how long to record for. Clamped to
     *                        [{@value FlagManager#MIN_DURATION_SECONDS},
     *                        {@value FlagManager#MAX_DURATION_SECONDS}] seconds; a request
     *                        outside that range is honoured at the nearest bound rather
     *                        than rejected.
     * @see #flagMoment(Player, String)
     */
    public FlagResult flagMoment(Player player, String reason, int durationSeconds) {
        return flags.flag(player, reason, durationSeconds, null);
    }

    /**
     * Flags a player on behalf of a specific staff member, who is messaged when the
     * recording starts and again when the file is ready for review.
     *
     * @param issuer the staff member to notify, or null for none
     * @see #flagMoment(Player, String)
     */
    public FlagResult flagMoment(Player player, String reason, int durationSeconds, CommandSender issuer) {
        return flags.flag(player, reason, durationSeconds, issuer);
    }

    /**
     * @return the flag currently recording this player, or null if none is. Use this to
     *         avoid raising a duplicate flag, though doing so is harmless — a repeat flag
     *         extends the existing recording rather than starting a second one.
     */
    public FlagRecord getActiveFlag(UUID playerUuid) {
        return flags.getActiveFlag(playerUuid);
    }

    /**
     * Returns recent flags, newest first, whether still recording or already saved.
     * <p>
     * Flags raised since the server started carry live status; older ones are recovered
     * from the replay index, so a flag issued before a restart is still listed with its
     * file.
     *
     * @param playerName only flags for this player, or null for all
     * @param limit      the most entries to return
     */
    public List<FlagRecord> getRecentFlags(String playerName, int limit) {
        return flags.getRecentFlags(playerName, limit);
    }

    /**
     * @return true if Watchcat can actually record. False when ProtocolLib is missing, in
     *         which case every call to {@code flagMoment} is rejected — worth checking once
     *         at startup so you can log a clear warning rather than silently recording
     *         nothing.
     */
    public boolean isRecordingAvailable() {
        return plugin.isRecordingAvailable();
    }
}
