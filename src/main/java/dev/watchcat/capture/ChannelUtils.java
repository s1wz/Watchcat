package dev.watchcat.capture;

import io.netty.channel.Channel;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class ChannelUtils {
    private static final Class<?> PACKET_LISTENER_CLASS = resolve("net.minecraft.network.PacketListener");
    private static final Class<?> CONNECTION_CLASS = resolve("net.minecraft.network.Connection");

    private static volatile Method getHandleMethod;
    private static volatile Field packetListenerField;
    private static volatile Field connectionField;
    private static volatile Field channelField;

    private ChannelUtils() {}

    public static Object getHandle(Player player) throws ReflectiveOperationException {
        Method handle = getHandleMethod;
        if (handle == null || !handle.getDeclaringClass().isInstance(player)) {
            handle = player.getClass().getMethod("getHandle");
            handle.setAccessible(true);
            getHandleMethod = handle;
        }
        return handle.invoke(player);
    }

    public static Object getPacketListener(Player player) throws ReflectiveOperationException {
        Object nmsPlayer = getHandle(player);
        if (nmsPlayer == null || PACKET_LISTENER_CLASS == null) {
            return null;
        }

        Field field = packetListenerField;
        if (field == null || !field.getDeclaringClass().isInstance(nmsPlayer)) {
            field = findFieldOfType(nmsPlayer.getClass(), PACKET_LISTENER_CLASS);
            if (field == null) {
                return null;
            }
            packetListenerField = field;
        }
        return field.get(nmsPlayer);
    }

    public static Object getConnection(Player player) throws ReflectiveOperationException {
        Object listener = getPacketListener(player);
        if (listener == null || CONNECTION_CLASS == null) {
            return null;
        }

        Field field = connectionField;
        if (field == null || !field.getDeclaringClass().isInstance(listener)) {
            field = findFieldOfType(listener.getClass(), CONNECTION_CLASS);
            if (field == null) {
                return null;
            }
            connectionField = field;
        }
        return field.get(listener);
    }

    public static Channel getChannel(Player player) {
        try {
            Object connection = getConnection(player);
            if (connection == null) {
                return null;
            }

            Field field = channelField;
            if (field == null || !field.getDeclaringClass().isInstance(connection)) {
                field = findFieldOfType(connection.getClass(), Channel.class);
                if (field == null) {
                    return null;
                }
                channelField = field;
            }
            return (Channel) field.get(connection);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static Field findFieldOfType(Class<?> owner, Class<?> wanted) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (wanted.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return null;
    }

    static Class<?> resolve(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
