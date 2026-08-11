package dev.watchcat.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class SessionManager {
    private final ConcurrentHashMap<UUID, RecordingSession> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RecordingSession> flagSessions = new ConcurrentHashMap<>();
    private final Consumer<RecordingSession> onSessionComplete;
    private final BiConsumer<RecordingSession, String> onFlush;
    private volatile PrologueSource prologueSource;

    public SessionManager(Consumer<RecordingSession> onSessionComplete,
                          BiConsumer<RecordingSession, String> onFlush,
                          PrologueSource prologueSource) {
        this.onSessionComplete = onSessionComplete;
        this.onFlush = onFlush;
        this.prologueSource = prologueSource;
    }

    public void setPrologueSource(PrologueSource prologueSource) {
        this.prologueSource = prologueSource;
    }

    public SessionManager() {
        this(null, null, null);
    }

    public RecordingSession startSession(UUID playerUuid, String playerName, int bufferMinutes) {
        RecordingSession session = new RecordingSession(playerUuid, playerName, bufferMinutes, s -> {
            activeSessions.remove(playerUuid);
            if (onSessionComplete != null) {
                onSessionComplete.accept(s);
            }
        });

        PrologueSource prologue = prologueSource;
        if (prologue != null) {
            session.setGlobalStatePackets(prologue.getPrologue(playerUuid, playerName));
        }
        RecordingSession existing = activeSessions.putIfAbsent(playerUuid, session);
        if (existing != null) {
            throw new IllegalStateException("A recording session is already active for " + playerUuid);
        }
        return session;
    }

    public RecordingSession startFlagSession(UUID playerUuid, String playerName, String reason,
                                             int durationSeconds, Consumer<RecordingSession> onStopped) {
        RecordingSession session = new RecordingSession(playerUuid, playerName, 0, s -> {
            flagSessions.remove(playerUuid, s);
            if (onStopped != null) {
                onStopped.accept(s);
            }
            if (onSessionComplete != null) {
                onSessionComplete.accept(s);
            }
        }, false);
        session.markFlagged(reason, durationSeconds);

        PrologueSource prologue = prologueSource;
        if (prologue != null) {
            session.setGlobalStatePackets(prologue.getPrologue(playerUuid, playerName));
        }
        RecordingSession existing = flagSessions.putIfAbsent(playerUuid, session);
        if (existing != null) {
            throw new IllegalStateException("A flag recording is already active for " + playerUuid);
        }
        return session;
    }

    public RecordingSession stopSession(UUID playerUuid) {
        RecordingSession session = activeSessions.remove(playerUuid);
        if (session != null) {
            session.stop();
        }
        return session;
    }

    public RecordingSession stopFlagSession(UUID playerUuid) {
        RecordingSession session = flagSessions.remove(playerUuid);
        if (session != null) {
            session.stop();
        }
        return session;
    }

    public RecordingSession handlePlayerQuit(UUID playerUuid) {
        stopFlagSession(playerUuid);
        return stopSession(playerUuid);
    }

    public RecordingSession getSession(UUID playerUuid) {
        return activeSessions.get(playerUuid);
    }

    public RecordingSession getFlagSession(UUID playerUuid) {
        return flagSessions.get(playerUuid);
    }

    public List<RecordingSession> getActiveSessions(UUID playerUuid) {
        RecordingSession ordinary = activeSessions.get(playerUuid);
        RecordingSession flagged = flagSessions.get(playerUuid);

        if (flagged == null || !flagged.isActive()) {
            return ordinary != null && ordinary.isActive() ? List.of(ordinary) : List.of();
        }
        if (ordinary == null || !ordinary.isActive()) {
            return List.of(flagged);
        }

        List<RecordingSession> both = new ArrayList<>(2);
        both.add(ordinary);
        both.add(flagged);
        return both;
    }

    public boolean hasActiveSession(UUID playerUuid) {
        return activeSessions.containsKey(playerUuid);
    }

    public boolean hasFlagSession(UUID playerUuid) {
        return flagSessions.containsKey(playerUuid);
    }

    public void stopAll() {
        for (RecordingSession session : flagSessions.values()) {
            session.stop();
        }
        flagSessions.clear();
        for (RecordingSession session : activeSessions.values()) {
            session.stop();
        }
        activeSessions.clear();
    }

    public int getActiveCount() {
        return activeSessions.size() + flagSessions.size();
    }

    public List<RecordingSession> getOrdinarySessions() {
        return List.copyOf(activeSessions.values());
    }

    public List<RecordingSession> getFlagSessions() {
        return List.copyOf(flagSessions.values());
    }

    public void flushToDisk(UUID playerUuid, String reason) {
        RecordingSession session = getSession(playerUuid);
        if (session != null && onFlush != null) {
            onFlush.accept(session, reason);
        }
    }

    public void trimAll() {
        for (RecordingSession session : activeSessions.values()) {
            session.trimBuffer();
        }
    }
}
