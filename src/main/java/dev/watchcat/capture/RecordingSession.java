package dev.watchcat.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class RecordingSession {
    private static final int MAX_PACKETS = 50_000;

    private final UUID playerUuid;
    private final String playerName;
    private final long startTimestamp;
    private final LinkedBlockingDeque<CapturedPacket> packets;

    private final Map<Long, CapturedPacket> loadedChunks = new ConcurrentHashMap<>();
    private final Map<Integer, CapturedPacket> spawnedEntities = new ConcurrentHashMap<>();
    private List<CapturedPacket> globalStatePackets = new ArrayList<>();
    private final AtomicBoolean active;
    private final Consumer<RecordingSession> onStop;
    private final int bufferMinutes;
    private final boolean dropOldestWhenFull;

    private final AtomicLong lastPacketTimestamp = new AtomicLong();

    private volatile boolean truncated;
    private volatile boolean flagged;
    private volatile String flagReason;
    private volatile int flagDurationSeconds;

    public RecordingSession(UUID playerUuid, String playerName, int bufferMinutes, Consumer<RecordingSession> onStop) {
        this(playerUuid, playerName, bufferMinutes, onStop, true);
    }

    public RecordingSession(UUID playerUuid, String playerName, int bufferMinutes,
                            Consumer<RecordingSession> onStop, boolean dropOldestWhenFull) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.startTimestamp = System.currentTimeMillis();
        this.packets = new LinkedBlockingDeque<>(MAX_PACKETS);
        this.active = new AtomicBoolean(true);
        this.onStop = onStop;
        this.bufferMinutes = bufferMinutes;
        this.dropOldestWhenFull = dropOldestWhenFull;
    }

    public RecordingSession(UUID playerUuid, String playerName, int bufferMinutes) {
        this(playerUuid, playerName, bufferMinutes, session -> {});
    }

    public void trimBuffer() {
        if (bufferMinutes <= 0 || !active.get()) {
            return;
        }
        long cutoff = System.currentTimeMillis() - (bufferMinutes * 60_000L);
        CapturedPacket oldest;
        while ((oldest = packets.peekFirst()) != null && oldest.getTimestampMillis() < cutoff) {
            packets.pollFirst();
        }
    }

    public void setGlobalStatePackets(List<CapturedPacket> packets) {
        this.globalStatePackets = new ArrayList<>(packets);
    }

    public boolean addPacket(CapturedPacket packet) {
        if (!active.get()) {
            return false;
        }

        if (!dropOldestWhenFull) {
            if (!packets.offer(packet)) {
                truncated = true;
                return false;
            }
            recordTimestamp(packet);
            return true;
        }

        while (!packets.offer(packet)) {
            packets.pollFirst();
        }
        recordTimestamp(packet);
        return true;
    }

    private void recordTimestamp(CapturedPacket packet) {
        lastPacketTimestamp.accumulateAndGet(packet.getTimestampMillis(), Math::max);
    }

    public long getLastPacketTimestamp() {
        return Math.max(startTimestamp, lastPacketTimestamp.get());
    }

    public void markFlagged(String reason, int durationSeconds) {
        this.flagged = true;
        this.flagReason = reason;
        this.flagDurationSeconds = durationSeconds;
    }

    public void setFlagReason(String reason) {
        this.flagReason = reason;
    }

    public boolean isFlagged() {
        return flagged;
    }

    public String getFlagReason() {
        return flagReason;
    }

    public int getFlagDurationSeconds() {
        return flagDurationSeconds;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    public void trackChunk(int chunkX, int chunkZ, CapturedPacket packet) {
        loadedChunks.put(chunkKey(chunkX, chunkZ), packet);
    }

    public void untrackChunk(int chunkX, int chunkZ) {
        loadedChunks.remove(chunkKey(chunkX, chunkZ));
    }

    public void trackEntity(int entityId, CapturedPacket packet) {
        spawnedEntities.put(entityId, packet);
    }

    public void untrackEntities(List<Integer> entityIds) {
        for (int id : entityIds) {
            spawnedEntities.remove(id);
        }
    }

    public boolean stop() {
        boolean transitioned = active.compareAndSet(true, false);
        if (transitioned && onStop != null) {
            onStop.accept(this);
        }
        return transitioned;
    }

    public boolean isActive() {
        return active.get();
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public long getStartTimestamp() {
        return startTimestamp;
    }

    public int getPacketCount() {
        return packets.size();
    }

    public Stream<CapturedPacket> streamPackets() {
        return getPackets().stream();
    }

    public List<CapturedPacket> getPackets() {
        List<CapturedPacket> live = new ArrayList<>(packets);

        Set<CapturedPacket> stillBuffered = Collections.newSetFromMap(new IdentityHashMap<>());
        stillBuffered.addAll(live);

        List<CapturedPacket> all = new ArrayList<>(
                globalStatePackets.size() + loadedChunks.size() + spawnedEntities.size() + live.size());
        all.addAll(globalStatePackets);
        for (CapturedPacket packet : loadedChunks.values()) {
            if (!stillBuffered.contains(packet)) {
                all.add(packet);
            }
        }
        for (CapturedPacket packet : spawnedEntities.values()) {
            if (!stillBuffered.contains(packet)) {
                all.add(packet);
            }
        }
        all.addAll(live);
        return Collections.unmodifiableList(all);
    }
}
