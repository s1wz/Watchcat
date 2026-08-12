package dev.watchcat.capture;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import dev.watchcat.Watchcat;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class ProtocolCapture implements CaptureBackend {

    private static final List<String> REQUIRED_PACKET_TYPES = List.of(
            "MAP_CHUNK", "LIGHT_UPDATE", "UNLOAD_CHUNK", "VIEW_CENTRE", "VIEW_DISTANCE",
            "BLOCK_CHANGE", "MULTI_BLOCK_CHANGE",
            "POSITION", "GAME_STATE_CHANGE", "RESPAWN", "ABILITIES", "UPDATE_TIME", "BUNDLE",
            "SPAWN_ENTITY", "ENTITY_METADATA", "ENTITY_DESTROY", "ENTITY_VELOCITY",
            "REL_ENTITY_MOVE", "REL_ENTITY_MOVE_LOOK", "ENTITY_LOOK", "ENTITY_HEAD_ROTATION",
            "ENTITY_TELEPORT", "ENTITY_POSITION_SYNC", "ENTITY_EQUIPMENT", "UPDATE_ATTRIBUTES",
            "PLAYER_INFO", "PLAYER_INFO_REMOVE");

    private static final Map<String, List<String>> PACKET_TYPE_ALIASES = Map.of(
            "CHUNK_DATA", List.of("MAP_CHUNK"),
            "PLAYER_POSITION", List.of("POSITION"),
            "GAME_EVENT", List.of("GAME_STATE_CHANGE"),
            "SPAWN_PLAYER", List.of("NAMED_ENTITY_SPAWN"),
            "ENTITY_POSITION", List.of("REL_ENTITY_MOVE", "REL_ENTITY_MOVE_LOOK", "ENTITY_LOOK"),
            "ENTITY_ROTATION", List.of("ENTITY_LOOK", "ENTITY_HEAD_ROTATION"),
            "ENTITY_TELEPORT", List.of("ENTITY_TELEPORT", "ENTITY_POSITION_SYNC"));

    private final Watchcat plugin;
    private final SessionManager sessionManager;
    private final GlobalStateCache globalStateCache;
    private final Map<UUID, AtomicLong> serializationErrors = new ConcurrentHashMap<>();

    private int listenedPacketTypes;

    public ProtocolCapture(Watchcat plugin, SessionManager sessionManager) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
        this.globalStateCache = new GlobalStateCache(plugin, ProtocolLibrary.getProtocolManager());

        registerListener();
        checkLoginPrologue();
    }

    private void checkLoginPrologue() {
        String failure = PrologueBuilder.selfTest();
        if (failure != null) {
            plugin.getLogger().severe("The login prologue cannot be built on this server version ("
                    + failure + "). Recordings will still be saved, but they will not open in "
                    + "ReplayMod. Please report this along with your server version.");
        }
    }

    private void registerListener() {
        List<PacketType> resolved = resolvePacketTypes(plugin.getConfigManager().getRecordedPacketTypes());
        if (resolved.isEmpty()) {
            plugin.getLogger().warning("No valid packet types resolved from config — packet capture is effectively disabled.");
            return;
        }

        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.MONITOR, resolved) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) {
                    return;
                }
                handleOutgoingPacket(event);
            }
        });

        listenedPacketTypes = resolved.size();
        plugin.getLogger().info("Packet capture initialized — listening to "
                + resolved.size() + " packet type(s).");
    }

    @Override
    public PrologueSource getPrologueSource() {
        return globalStateCache;
    }

    @Override
    public int getListenedPacketTypes() {
        return listenedPacketTypes;
    }

    @Override
    public void shutdown() {
        ProtocolLibrary.getProtocolManager().removePacketListeners(plugin);
    }

    private void handleOutgoingPacket(PacketEvent event) {
        if (event.getPlayer() == null) {
            return;
        }

        List<RecordingSession> sessions = sessionManager.getActiveSessions(event.getPlayer().getUniqueId());
        if (sessions.isEmpty()) {
            return;
        }

        Object handle = event.getPacket().getHandle();

        if (BundleExpander.isBundle(handle)) {
            for (Object sub : BundleExpander.expand(handle)) {
                capture(sessions, event.getPlayer(), sub, sub.getClass().getSimpleName());
            }
            return;
        }

        CapturedPacket captured = capture(sessions, event.getPlayer(), handle, event.getPacketType().name());
        if (captured != null) {
            updateStateTracking(sessions, event, captured);
        }
    }

    private CapturedPacket capture(List<RecordingSession> sessions, Player player,
                                   Object nmsPacket, String typeName) {
        try {
            byte[] rawBytes = PacketSerializer.serialize(player, nmsPacket);
            CapturedPacket captured = new CapturedPacket(System.currentTimeMillis(), typeName, rawBytes);
            for (RecordingSession session : sessions) {
                session.addPacket(captured);
            }
            serializationErrors.remove(player.getUniqueId());
            return captured;
        } catch (Exception e) {
            AtomicLong counter = serializationErrors.computeIfAbsent(
                    player.getUniqueId(), k -> new AtomicLong(0));
            long count = counter.incrementAndGet();
            if (count == 1 || (count % 100 == 0)) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to serialize packet " + typeName + " for " + player.getName()
                        + " (total failures: " + count + ")", e);
            }
            return null;
        }
    }

    private void updateStateTracking(List<RecordingSession> sessions, PacketEvent event, CapturedPacket captured) {
        try {
            PacketType type = event.getPacketType();
            PacketContainer packet = event.getPacket();

            if (type == PacketType.Play.Server.MAP_CHUNK || isPacketType(type, "CHUNK_DATA")) {
                int chunkX = packet.getIntegers().read(0);
                int chunkZ = packet.getIntegers().read(1);
                for (RecordingSession session : sessions) {
                    session.trackChunk(chunkX, chunkZ, captured);
                }
            } else if (type == PacketType.Play.Server.UNLOAD_CHUNK) {
                int chunkX = packet.getIntegers().read(0);
                int chunkZ = packet.getIntegers().read(1);
                for (RecordingSession session : sessions) {
                    session.untrackChunk(chunkX, chunkZ);
                }
            }

            if (type == PacketType.Play.Server.SPAWN_ENTITY || isPacketType(type, "SPAWN_PLAYER")) {
                int entityId = packet.getIntegers().read(0);
                for (RecordingSession session : sessions) {
                    session.trackEntity(entityId, captured);
                }
            } else if (isPacketType(type, "ENTITY_DESTROY")) {
                List<Integer> entityIds = readDestroyedEntityIds(packet);
                for (RecordingSession session : sessions) {
                    session.untrackEntities(entityIds);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("State tracking failed for " + event.getPacketType().name()
                    + ": " + e.getMessage());
        }
    }

    private List<Integer> readDestroyedEntityIds(PacketContainer packet) {
        try {
            if (packet.getIntLists().size() > 0) {
                return packet.getIntLists().read(0);
            }
        } catch (Exception ignored) {}

        try {
            if (packet.getIntegerArrays().size() > 0) {
                int[] arr = packet.getIntegerArrays().read(0);
                List<Integer> list = new ArrayList<>();
                for (int i : arr) list.add(i);
                return list;
            }
        } catch (Exception ignored) {}

        try {
            return List.of(packet.getIntegers().read(0));
        } catch (Exception ignored) {}

        return List.of();
    }

    private boolean isPacketType(PacketType type, String name) {
        return type.name().equals(name);
    }

    private List<PacketType> resolvePacketTypes(List<String> configNames) {
        Map<String, PacketType> resolved = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();

        for (String configName : configNames) {
            String fieldName = configName.toUpperCase(Locale.ROOT);
            List<String> candidates = PACKET_TYPE_ALIASES.getOrDefault(fieldName, List.of(fieldName));

            boolean matched = false;
            for (String candidate : candidates) {
                PacketType type = lookupServerPacketType(candidate);
                if (type != null && type.isSupported()) {
                    resolved.put(type.name(), type);
                    matched = true;
                }
            }
            if (!matched) {
                unknown.add(configName);
            }
        }

        List<String> added = new ArrayList<>();
        for (String required : REQUIRED_PACKET_TYPES) {
            if (resolved.containsKey(required)) {
                continue;
            }
            PacketType type = lookupServerPacketType(required);
            if (type != null && type.isSupported()) {
                resolved.put(type.name(), type);
                added.add(required);
            }
        }

        if (!unknown.isEmpty()) {
            plugin.getLogger().warning("Unknown packet type(s) in config, skipped: " + unknown
                    + " — check recorded-packet-types in config.yml.");
        }
        if (!added.isEmpty()) {
            plugin.getLogger().info("Added " + added.size()
                    + " packet type(s) required for a replay to be viewable: " + added);
        }

        return new ArrayList<>(resolved.values());
    }

    private PacketType lookupServerPacketType(String fieldName) {
        try {
            Field field = PacketType.Play.Server.class.getDeclaredField(fieldName);
            if (PacketType.class.isAssignableFrom(field.getType())) {
                return (PacketType) field.get(null);
            }
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
        }
        return null;
    }
}
