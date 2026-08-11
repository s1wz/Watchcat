package dev.watchcat.flag.event;

import dev.watchcat.flag.FlagRecord;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a flag recording begins, or when an already-running one is extended.
 * <p>
 * This is how a plugin other than the one that raised the flag learns about it: an
 * anticheat can notice staff-issued flags, or a punishment plugin can note that a player
 * is under observation.
 * <p>
 * Always fired on the main server thread, even when the flag was raised from an
 * asynchronous detection thread. Purely informational — the recording has already started
 * and cannot be cancelled here.
 */
public final class FlagRecordingStartedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final FlagRecord record;
    private final boolean extension;

    public FlagRecordingStartedEvent(FlagRecord record, boolean extension) {
        this.record = record;
        this.extension = extension;
    }

    public FlagRecord getRecord() {
        return record;
    }

    /**
     * @return true if this extended a recording that was already running, false if it
     *         started a new one
     */
    public boolean isExtension() {
        return extension;
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
