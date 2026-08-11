package dev.watchcat.capture;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class WorldSnapshot {
    private static final int MAX_ENTITIES = 2000;

    private static final int DEFAULT_VIEW_DISTANCE = 10;

    private static final Logger LOGGER = Logger.getLogger("Watchcat");

    private static volatile boolean resolved;
    private static volatile boolean resolutionFailed;

    private static Method getChunkSource;
    private static Method getLightEngine;
    private static Method getChunkNow;
    private static Method entityGetId;
    private static Method entityBlockPosition;
    private static Method entityGetEntityData;
    private static Method getNonDefaultValues;

    private static Constructor<?> levelChunkPacket;
    private static Constructor<?> chunkCacheCentrePacket;
    private static Constructor<?> chunkCacheRadiusPacket;
    private static Constructor<?> playerPositionPacket;
    private static Constructor<?> positionMoveRotation;
    private static Constructor<?> vec3;
    private static Constructor<?> gameEventPacket;
    private static Constructor<?> addEntityPacket;
    private static Constructor<?> setEntityDataPacket;

    private static Object levelChunksLoadStart;

    private WorldSnapshot() {}

    public static List<CapturedPacket> capture(Player player) {
        if (!resolve()) {
            return List.of();
        }
        if (!player.getServer().isPrimaryThread()) {
            LOGGER.warning("Watchcat: world snapshot skipped for " + player.getName()
                    + " — not on the main thread. The replay will open without terrain.");
            return List.of();
        }

        try {
            return build(player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Watchcat: could not build the world snapshot for "
                    + player.getName() + " — the replay will open without terrain", e);
            return List.of();
        }
    }

    private static List<CapturedPacket> build(Player player) throws ReflectiveOperationException {
        List<CapturedPacket> out = new ArrayList<>();
        long now = System.currentTimeMillis();

        Object serverLevel = handleOf(player.getWorld());
        Object chunkSource = getChunkSource.invoke(serverLevel);
        Object lightEngine = getLightEngine.invoke(chunkSource);

        Location location = player.getLocation();
        int centreX = location.getBlockX() >> 4;
        int centreZ = location.getBlockZ() >> 4;
        int radius = viewDistance(player);

        add(out, player, now, chunkCacheRadiusPacket.newInstance(radius));

        Object position = vec3.newInstance(location.getX(), location.getY(), location.getZ());
        Object zero = vec3.newInstance(0.0D, 0.0D, 0.0D);
        Object move = positionMoveRotation.newInstance(position, zero, location.getYaw(), location.getPitch());
        add(out, player, now, playerPositionPacket.newInstance(0, move, Set.of()));

        add(out, player, now, chunkCacheCentrePacket.newInstance(centreX, centreZ));

        int chunks = 0;
        for (int x = centreX - radius; x <= centreX + radius; x++) {
            for (int z = centreZ - radius; z <= centreZ + radius; z++) {
                Object chunk = getChunkNow.invoke(chunkSource, x, z);
                if (chunk == null) {
                    continue;
                }
                if (add(out, player, now,
                        levelChunkPacket.newInstance(chunk, lightEngine, (BitSet) null, (BitSet) null))) {
                    chunks++;
                }
            }
        }

        add(out, player, now, gameEventPacket.newInstance(levelChunksLoadStart, 0.0F));

        int entities = 0;
        int reach = radius * 16;
        for (org.bukkit.entity.Entity bukkitEntity : player.getWorld().getEntities()) {
            if (entities >= MAX_ENTITIES) {
                break;
            }

            if (!bukkitEntity.getUniqueId().equals(player.getUniqueId())
                    && bukkitEntity.getLocation().distanceSquared(location) > (double) reach * reach) {
                continue;
            }

            Object handle = handleOf(bukkitEntity);
            int id = (int) entityGetId.invoke(handle);
            Object blockPos = entityBlockPosition.invoke(handle);
            if (!add(out, player, now, addEntityPacket.newInstance(handle, 0, blockPos))) {
                continue;
            }
            entities++;

            List<?> values = (List<?>) getNonDefaultValues.invoke(entityGetEntityData.invoke(handle));
            if (values != null && !values.isEmpty()) {
                add(out, player, now, setEntityDataPacket.newInstance(id, values));
            }
        }

        LOGGER.info("Watchcat: world snapshot for " + player.getName() + " took "
                + (System.currentTimeMillis() - now) + "ms — "
                + chunks + " chunk(s), " + entities + " entit(ies).");
        return out;
    }

    private static boolean add(List<CapturedPacket> out, Player player, long timestamp, Object nmsPacket) {
        try {
            byte[] raw = PacketSerializer.serialize(player, nmsPacket);
            out.add(new CapturedPacket(timestamp, nmsPacket.getClass().getSimpleName(), raw));
            return true;
        } catch (Exception e) {
            LOGGER.fine("Watchcat: skipped snapshot packet "
                    + nmsPacket.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private static Object handleOf(Object craftObject) throws ReflectiveOperationException {
        Method handle = craftObject.getClass().getMethod("getHandle");
        handle.setAccessible(true);
        return handle.invoke(craftObject);
    }

    private static int viewDistance(Player player) {
        try {
            int distance = player.getWorld().getViewDistance();
            if (distance > 0) {
                return distance;
            }
        } catch (RuntimeException ignored) {
        }
        return DEFAULT_VIEW_DISTANCE;
    }

    private static boolean resolve() {
        if (resolved) {
            return !resolutionFailed;
        }
        synchronized (WorldSnapshot.class) {
            if (resolved) {
                return !resolutionFailed;
            }
            resolved = true;
            try {
                Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
                Class<?> chunkSource = Class.forName("net.minecraft.world.level.chunk.ChunkSource");
                Class<?> nmsEntity = Class.forName("net.minecraft.world.entity.Entity");
                Class<?> vec3Class = Class.forName("net.minecraft.world.phys.Vec3");
                Class<?> moveRotation = Class.forName("net.minecraft.world.entity.PositionMoveRotation");

                getChunkSource = serverLevel.getMethod("getChunkSource");
                getLightEngine = chunkSource.getMethod("getLightEngine");
                getChunkNow = chunkSource.getMethod("getChunkNow", int.class, int.class);
                entityGetId = nmsEntity.getMethod("getId");
                entityBlockPosition = nmsEntity.getMethod("blockPosition");
                entityGetEntityData = nmsEntity.getMethod("getEntityData");
                getNonDefaultValues = entityGetEntityData.getReturnType().getMethod("getNonDefaultValues");

                Class<?> levelChunk = getChunkNow.getReturnType();
                Class<?> lightEngine = getLightEngine.getReturnType();
                Class<?> blockPos = entityBlockPosition.getReturnType();

                String game = "net.minecraft.network.protocol.game.";
                levelChunkPacket = Class.forName(game + "ClientboundLevelChunkWithLightPacket")
                        .getConstructor(levelChunk, lightEngine, BitSet.class, BitSet.class);
                chunkCacheCentrePacket = Class.forName(game + "ClientboundSetChunkCacheCenterPacket")
                        .getConstructor(int.class, int.class);
                chunkCacheRadiusPacket = Class.forName(game + "ClientboundSetChunkCacheRadiusPacket")
                        .getConstructor(int.class);
                playerPositionPacket = Class.forName(game + "ClientboundPlayerPositionPacket")
                        .getConstructor(int.class, moveRotation, Set.class);
                addEntityPacket = Class.forName(game + "ClientboundAddEntityPacket")
                        .getConstructor(nmsEntity, int.class, blockPos);
                setEntityDataPacket = Class.forName(game + "ClientboundSetEntityDataPacket")
                        .getConstructor(int.class, List.class);

                positionMoveRotation = moveRotation.getConstructor(
                        vec3Class, vec3Class, float.class, float.class);
                vec3 = vec3Class.getConstructor(double.class, double.class, double.class);

                Class<?> gameEvent = Class.forName(game + "ClientboundGameEventPacket");
                Class<?> eventType = Class.forName(game + "ClientboundGameEventPacket$Type");
                gameEventPacket = gameEvent.getConstructor(eventType, float.class);
                levelChunksLoadStart = gameEvent.getField("LEVEL_CHUNKS_LOAD_START").get(null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                resolutionFailed = true;
                LOGGER.log(Level.WARNING, "Watchcat: world snapshots are unavailable on this server "
                        + "version — replays will open without terrain", e);
            }
            return !resolutionFailed;
        }
    }
}
