package dev.watchcat.capture;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PlayerStateRecorder {
    private static final Logger LOGGER = Logger.getLogger("Watchcat");

    private static final float ROTATION_SCALE = 256.0F / 360.0F;

    public static final int SWING_MAIN_HAND = 0;
    public static final int SWING_OFF_HAND = 3;

    private static volatile boolean resolved;
    private static volatile boolean resolutionFailed;

    private static Method positionSyncOf;
    private static Constructor<?> rotateHeadPacket;
    private static Constructor<?> setMotionPacket;
    private static Constructor<?> setEquipmentPacket;
    private static Constructor<?> animatePacket;
    private static Constructor<?> blockUpdatePacket;
    private static Constructor<?> blockPos;

    private static Method getItemBySlot;
    private static Method pairOf;
    private static Object[] equipmentSlots;

    private PlayerStateRecorder() {}

    public static void recordMotion(Player player, Collection<RecordingSession> sessions) {
        if (!ready(sessions)) {
            return;
        }
        try {
            Object handle = handleOf(player);
            long now = System.currentTimeMillis();
            byte headRotation = (byte) (int) (player.getLocation().getYaw() * ROTATION_SCALE);

            add(sessions, player, now, positionSyncOf.invoke(null, handle));
            add(sessions, player, now, rotateHeadPacket.newInstance(handle, headRotation));
            add(sessions, player, now, setMotionPacket.newInstance(handle));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Watchcat: could not sample motion for " + player.getName(), e);
        }
    }

    public static void recordEquipment(Player player, Collection<RecordingSession> sessions) {
        if (!ready(sessions)) {
            return;
        }
        try {
            Object handle = handleOf(player);
            List<Object> slots = new ArrayList<>(equipmentSlots.length);
            for (Object slot : equipmentSlots) {
                slots.add(pairOf.invoke(null, slot, getItemBySlot.invoke(handle, slot)));
            }
            add(sessions, player, System.currentTimeMillis(),
                    setEquipmentPacket.newInstance(player.getEntityId(), slots));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Watchcat: could not sample equipment for " + player.getName(), e);
        }
    }

    public static void recordSwing(Player player, Collection<RecordingSession> sessions, int action) {
        if (!ready(sessions)) {
            return;
        }
        try {
            add(sessions, player, System.currentTimeMillis(),
                    animatePacket.newInstance(handleOf(player), action));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Watchcat: could not record a swing for " + player.getName(), e);
        }
    }

    public static void recordBlockUpdate(Player player, Collection<RecordingSession> sessions,
                                         Location location) {
        if (!ready(sessions)) {
            return;
        }
        try {
            Object level = handleOf(location.getWorld());
            Object position = blockPos.newInstance(
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
            add(sessions, player, System.currentTimeMillis(),
                    blockUpdatePacket.newInstance(level, position));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Watchcat: could not record a block update for "
                    + player.getName(), e);
        }
    }

    private static boolean ready(Collection<RecordingSession> sessions) {
        return resolve() && !sessions.isEmpty();
    }

    private static void add(Collection<RecordingSession> sessions, Player player,
                            long timestamp, Object nmsPacket) {
        if (nmsPacket == null) {
            return;
        }
        try {
            byte[] raw = PacketSerializer.serialize(player, nmsPacket);
            CapturedPacket captured =
                    new CapturedPacket(timestamp, nmsPacket.getClass().getSimpleName(), raw);
            for (RecordingSession session : sessions) {
                session.addPacket(captured);
            }
        } catch (Exception e) {
            LOGGER.fine("Watchcat: skipped self-state packet "
                    + nmsPacket.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Object handleOf(Object craftObject) throws ReflectiveOperationException {
        Method handle = craftObject.getClass().getMethod("getHandle");
        handle.setAccessible(true);
        return handle.invoke(craftObject);
    }

    private static boolean resolve() {
        if (resolved) {
            return !resolutionFailed;
        }
        synchronized (PlayerStateRecorder.class) {
            if (resolved) {
                return !resolutionFailed;
            }
            resolved = true;
            try {
                Class<?> nmsEntity = Class.forName("net.minecraft.world.entity.Entity");
                Class<?> livingEntity = Class.forName("net.minecraft.world.entity.LivingEntity");
                Class<?> equipmentSlot = Class.forName("net.minecraft.world.entity.EquipmentSlot");
                Class<?> blockGetter = Class.forName("net.minecraft.world.level.BlockGetter");
                String game = "net.minecraft.network.protocol.game.";

                positionSyncOf = Class.forName(game + "ClientboundEntityPositionSyncPacket")
                        .getMethod("of", nmsEntity);
                rotateHeadPacket = Class.forName(game + "ClientboundRotateHeadPacket")
                        .getConstructor(nmsEntity, byte.class);
                setMotionPacket = Class.forName(game + "ClientboundSetEntityMotionPacket")
                        .getConstructor(nmsEntity);
                setEquipmentPacket = Class.forName(game + "ClientboundSetEquipmentPacket")
                        .getConstructor(int.class, List.class);
                animatePacket = Class.forName(game + "ClientboundAnimatePacket")
                        .getConstructor(nmsEntity, int.class);

                getItemBySlot = livingEntity.getMethod("getItemBySlot", equipmentSlot);
                equipmentSlots = equipmentSlot.getEnumConstants();
                pairOf = Class.forName("com.mojang.datafixers.util.Pair")
                        .getMethod("of", Object.class, Object.class);

                Class<?> blockPosClass = nmsEntity.getMethod("blockPosition").getReturnType();
                blockPos = blockPosClass.getConstructor(int.class, int.class, int.class);
                blockUpdatePacket = Class.forName(game + "ClientboundBlockUpdatePacket")
                        .getConstructor(blockGetter, blockPosClass);
            } catch (ReflectiveOperationException | RuntimeException e) {
                resolutionFailed = true;
                LOGGER.log(Level.WARNING, "Watchcat: cannot synthesize the recorded player's own "
                        + "state on this server version — they will appear stationary, unarmed "
                        + "and unanimated in replays", e);
            }
            return !resolutionFailed;
        }
    }
}
