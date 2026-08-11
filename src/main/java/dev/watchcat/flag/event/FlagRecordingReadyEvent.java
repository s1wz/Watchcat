package dev.watchcat.flag.event;

import dev.watchcat.flag.FlagRecord;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired once a flag recording has stopped and its replay has been written to disk.
 * <p>
 * The counterpart to {@link FlagRecordingStartedEvent}, and the point at which a replay
 * first exists as a file: a plugin that flagged a player can wait for this to attach the
 * finished recording to its own report, and staff tooling can announce that a review is
 * waiting.
 * <p>
 * Fired on the main server thread once the asynchronous write has finished. It is also
 * fired when the recording produced nothing usable — the player disconnected before it
 * started, or the write failed — so check {@link #isSuccessful()} before reading the path.
 */
public final class FlagRecordingReadyEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final FlagRecord record;

    public FlagRecordingReadyEvent(FlagRecord record) {
        this.record = record;
    }

    public FlagRecord getRecord() {
        return record;
    }

    /**
     * @return the absolute path of the written replay, or null if the recording failed
     */
    public String getFilePath() {
        return record.getFilePath();
    }

    /**
     * @return true if a replay was actually written. When false,
     *         {@link FlagRecord#getFailure()} explains why.
     */
    public boolean isSuccessful() {
        return record.getStatus() == FlagRecord.Status.READY;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
