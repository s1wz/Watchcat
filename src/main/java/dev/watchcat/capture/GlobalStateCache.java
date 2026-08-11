package dev.watchcat.capture;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import dev.watchcat.Watchcat;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class GlobalStateCache implements Listener, PrologueSource {
    private static final int MAX_CONFIGURATION_PACKETS = 512;

    private final Watchcat plugin;
    private final Map<UUID, List<CapturedPacket>> statePackets = new ConcurrentHashMap<>();

    private final Object configurationProtocol = PacketSerializer.staticProtocolInfo(
            "net.minecraft.network.protocol.configuration.ConfigurationProtocols", "CLIENTBOUND");

    private final Object configurationLock = new Object();
    private final List<CapturedPacket> pendingConfiguration = new ArrayList<>();
    private volatile List<CapturedPacket> configurationPrologue = List.of();

    private volatile PacketType finishConfigurationType;

    public GlobalStateCache(Watchcat plugin, ProtocolManager protocolManager) {
        this.plugin = plugin;

        registerPlayStateListener(protocolManager);
        registerConfigurationListener(protocolManager);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private void registerPlayStateListener(ProtocolManager protocolManager) {
        List<PacketType> types = new ArrayList<>();
        types.add(PacketType.Play.Server.LOGIN);
        types.add(PacketType.Play.Server.RESPAWN);

        safeAddType(types, "PLAYER_INFO");
        safeAddType(types, "PLAYER_INFO_UPDATE");
        safeAddType(types, "PLAYER_INFO_REMOVE");

        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.MONITOR, types) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled() || event.getPlayer() == null) return;

                UUID uuid = event.getPlayer().getUniqueId();
                try {
                    byte[] rawBytes = PacketSerializer.serialize(event.getPlayer(), event.getPacket().getHandle());
                    boolean isLogin = event.getPacketType() == PacketType.Play.Server.LOGIN;
                    if (isLogin) {
                        rawBytes = PrologueBuilder.withPhantomSelf(rawBytes);
                    }
                    CapturedPacket cp = new CapturedPacket(
                            System.currentTimeMillis(),
                            event.getPacketType().name(),
                            rawBytes
                    );

                    if (isLogin) {
                        statePackets.put(uuid, new ArrayList<>(List.of(cp)));
                    } else {
                        statePackets.computeIfAbsent(uuid, k -> new ArrayList<>()).add(cp);
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to cache global state packet", e);
                }
            }
        });
    }

    private void registerConfigurationListener(ProtocolManager protocolManager) {
        List<PacketType> types = configurationServerTypes();
        if (types.isEmpty()) {
            plugin.getLogger().warning("No configuration packet types available from ProtocolLib — "
                    + "replays will be missing their registry prologue and will not open in ReplayMod.");
            return;
        }
        if (configurationProtocol == null) {
            plugin.getLogger().warning("Configuration protocol codec unavailable — "
                    + "replays will be missing their registry prologue.");
            return;
        }

        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.MONITOR, types) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled() || !configurationPrologue.isEmpty()) {
                    return;
                }
                captureConfigurationPacket(event);
            }
        });

        plugin.getLogger().info("Watching for a configuration prologue across "
                + types.size() + " packet type(s).");
    }

    private void captureConfigurationPacket(PacketEvent event) {
        String typeName = event.getPacketType().name();

        byte[] rawBytes;
        try {
            rawBytes = PacketSerializer.serializeWithProtocol(
                    configurationProtocol, event.getPacket().getHandle());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to cache configuration packet " + typeName, e);
            return;
        }

        CapturedPacket captured = new CapturedPacket(System.currentTimeMillis(), typeName, rawBytes);

        synchronized (configurationLock) {
            if (!configurationPrologue.isEmpty()) {
                return;
            }
            if (pendingConfiguration.size() >= MAX_CONFIGURATION_PACKETS) {
                plugin.getLogger().warning("Configuration sequence exceeded "
                        + MAX_CONFIGURATION_PACKETS + " packets without completing — restarting capture.");
                pendingConfiguration.clear();
            }
            pendingConfiguration.add(captured);

            if (event.getPacketType().equals(finishConfigurationType)
                    || typeName.equals("FINISH_CONFIGURATION")) {
                configurationPrologue = List.copyOf(pendingConfiguration);
                pendingConfiguration.clear();
                plugin.getLogger().info("Captured configuration prologue: "
                        + configurationPrologue.size() + " packet(s). Replays are now openable.");
            }
        }
    }

    private List<PacketType> configurationServerTypes() {
        List<PacketType> types = new ArrayList<>();
        for (Field field : PacketType.Configuration.Server.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !PacketType.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                PacketType type = (PacketType) field.get(null);
                if (type != null && type.isSupported()) {
                    types.add(type);
                    if (field.getName().equals("FINISH_CONFIGURATION")) {
                        finishConfigurationType = type;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return types;
    }

    private void safeAddType(List<PacketType> types, String fieldName) {
        try {
            Field field = PacketType.Play.Server.class.getDeclaredField(fieldName);
            if (PacketType.class.isAssignableFrom(field.getType())) {
                types.add((PacketType) field.get(null));
            }
        } catch (Exception ignored) {}
    }

    @Override
    public List<CapturedPacket> getPrologue(UUID playerUuid, String playerName) {
        List<CapturedPacket> prologue = new ArrayList<>();
        long now = System.currentTimeMillis();

        CapturedPacket loginSuccess = PrologueBuilder.loginSuccess(playerUuid, playerName, now);
        if (loginSuccess != null) {
            prologue.add(loginSuccess);
        }

        List<CapturedPacket> configuration = configurationPrologue;
        if (configuration.isEmpty()) {
            plugin.getLogger().warning("No configuration prologue captured yet (no player has joined "
                    + "since Watchcat loaded) — the replay of " + playerName
                    + " will be missing its registries and may not open in ReplayMod.");
        }
        prologue.addAll(configuration);

        List<CapturedPacket> playState = statePackets.get(playerUuid);
        if (playState != null) {
            prologue.addAll(playState);
        }

        org.bukkit.entity.Player player = plugin.getServer().getPlayer(playerUuid);
        if (player != null) {
            prologue.addAll(WorldSnapshot.capture(player));
        }

        return prologue;
    }

    @Override
    public boolean hasConfigurationPrologue() {
        return !configurationPrologue.isEmpty();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        statePackets.remove(event.getPlayer().getUniqueId());
    }
}
