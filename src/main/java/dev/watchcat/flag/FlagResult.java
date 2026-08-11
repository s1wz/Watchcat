package dev.watchcat.flag;

/**
 * What happened when a flag was raised.
 * <p>
 * Returned rather than thrown, because a plugin flagging a player is reporting a
 * detection, not making a request that can be refused: it should not have to guard the
 * call in a try/catch to keep its own detection loop alive.
 *
 * @see dev.watchcat.api.WatchcatAPI#flagMoment(org.bukkit.entity.Player, String)
 */
public final class FlagResult {

    public enum Outcome {

        /** A new recording was started. */
        STARTED,

        /** A recording was already running for this player and had its clock reset. */
        EXTENDED,

        /** Nothing was recorded; see {@link FlagResult#getMessage()}. */
        REJECTED
    }

    private final Outcome outcome;
    private final FlagRecord record;
    private final String message;

    private FlagResult(Outcome outcome, FlagRecord record, String message) {
        this.outcome = outcome;
        this.record = record;
        this.message = message;
    }

    static FlagResult started(FlagRecord record) {
        return new FlagResult(Outcome.STARTED, record,
                "Started a " + record.getDurationSeconds() + "s recording of " + record.getPlayerName());
    }

    static FlagResult extended(FlagRecord record) {
        return new FlagResult(Outcome.EXTENDED, record,
                "Extended the recording of " + record.getPlayerName()
                        + " to " + record.getDurationSeconds() + "s");
    }

    static FlagResult rejected(String message) {
        return new FlagResult(Outcome.REJECTED, null, message);
    }

    public Outcome getOutcome() {
        return outcome;
    }

    /**
     * @return true if a recording is running because of this call — whether it started one
     *         or extended the one already going. This is usually the only check a caller
     *         needs; the distinction between the two matters for logging, not for control
     *         flow.
     */
    public boolean isRecording() {
        return outcome != Outcome.REJECTED;
    }

    /**
     * @return the flag being tracked, or null if the flag was rejected
     */
    public FlagRecord getRecord() {
        return record;
    }

    /**
     * @return a human-readable summary, suitable for logging or showing to staff. On a
     *         rejection this explains why, for example that ProtocolLib is not installed.
     */
    public String getMessage() {
        return message;
    }
}
