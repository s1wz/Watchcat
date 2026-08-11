package dev.watchcat.capture;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.util.AttributeKey;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PacketSerializer {
    private static final Logger LOGGER = Logger.getLogger("Watchcat");

    private static final Class<?> PROTOCOL_INFO_CLASS = ChannelUtils.resolve("net.minecraft.network.ProtocolInfo");
    private static final Class<?> STREAM_CODEC_CLASS = ChannelUtils.resolve("net.minecraft.network.codec.StreamCodec");

    private static final Object ABSENT = new Object();

    private static final Map<Class<?>, Object> PROTOCOL_INFO_FIELDS = new ConcurrentHashMap<>();

    private static final Map<Class<?>, Object> HANDLER_ENCODE_METHODS = new ConcurrentHashMap<>();

    private static volatile Method codecAccessor;
    private static volatile Method streamCodecEncode;

    private static final AtomicBoolean diagnosticsDumped = new AtomicBoolean();

    private PacketSerializer() {}

    public static byte[] serialize(Player player, Object nmsPacket) {
        Channel channel = ChannelUtils.getChannel(player);
        if (channel == null) {
            dumpDiagnosticsOnce(player, null);
            throw new PacketSerializationException(
                    "Netty channel could not be resolved for " + player.getName(), null);
        }

        ChannelHandlerContext encoderCtx = findEncoderContext(channel);
        if (encoderCtx == null) {
            dumpDiagnosticsOnce(player, channel);
            throw new PacketSerializationException(
                    "No packet encoder found in the Netty pipeline for " + player.getName(), null);
        }

        ChannelHandler handler = encoderCtx.handler();
        List<String> failures = new ArrayList<>(2);
        Throwable cause = null;

        try {
            byte[] encoded = encodeWithProtocolCodec(channel, handler, nmsPacket);
            if (encoded != null) {
                return encoded;
            }
            failures.add("protocol codec: not exposed by " + handler.getClass().getName());
        } catch (Throwable t) {
            cause = unwrap(t);
            failures.add("protocol codec: " + describe(cause));
        }

        try {
            byte[] encoded = encodeWithHandler(encoderCtx, nmsPacket);
            if (encoded != null) {
                return encoded;
            }
            failures.add("pipeline encoder: no encode(ctx, packet, buf) on " + handler.getClass().getName());
        } catch (Throwable t) {
            Throwable unwrapped = unwrap(t);
            if (cause == null) {
                cause = unwrapped;
            }
            failures.add("pipeline encoder: " + describe(unwrapped));
        }

        dumpDiagnosticsOnce(player, channel);
        throw new PacketSerializationException("Could not serialize "
                + nmsPacket.getClass().getName() + " for " + player.getName()
                + " — " + String.join("; ", failures), cause);
    }

    private static byte[] encodeWithProtocolCodec(Channel channel, ChannelHandler handler, Object nmsPacket)
            throws ReflectiveOperationException {
        Field protocolInfoField = protocolInfoField(handler.getClass());
        if (protocolInfoField == null) {
            return null;
        }

        Object protocolInfo = protocolInfoField.get(handler);
        if (protocolInfo == null) {
            return null;
        }

        return encodeWithProtocolInfo(protocolInfo, nmsPacket, channel);
    }

    public static byte[] serializeWithProtocol(Object protocolInfo, Object nmsPacket) {
        try {
            byte[] encoded = encodeWithProtocolInfo(protocolInfo, nmsPacket, null);
            if (encoded != null) {
                return encoded;
            }
            throw new PacketSerializationException("Protocol codec unavailable for "
                    + nmsPacket.getClass().getName(), null);
        } catch (ReflectiveOperationException e) {
            Throwable cause = unwrap(e);
            throw new PacketSerializationException("Could not serialize "
                    + nmsPacket.getClass().getName() + " — " + describe(cause), cause);
        }
    }

    public static Object staticProtocolInfo(String className, String fieldName) {
        Class<?> owner = ChannelUtils.resolve(className);
        if (owner == null) {
            LOGGER.warning("Watchcat: " + className + " not found — replays will be missing "
                    + "their " + fieldName + " prologue");
            return null;
        }
        try {
            Field field = owner.getField(fieldName);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException e) {
            LOGGER.log(Level.WARNING, "Watchcat: could not read " + className + "." + fieldName, e);
            return null;
        }
    }

    private static byte[] encodeWithProtocolInfo(Object protocolInfo, Object nmsPacket, Channel channel)
            throws ReflectiveOperationException {
        if (protocolInfo == null) {
            return null;
        }

        Method accessor = codecAccessor(protocolInfo);
        if (accessor == null) {
            return null;
        }

        Object codec = accessor.invoke(protocolInfo);
        if (codec == null) {
            return null;
        }

        Method encode = streamCodecEncode(codec);
        if (encode == null) {
            return null;
        }

        ByteBuf buf = Unpooled.buffer();
        Object previousLocale = AdventureLocale.swapIn(channel);
        try {
            encode.invoke(codec, buf, nmsPacket);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            AdventureLocale.restore(previousLocale);
            buf.release();
        }
    }

    private static Field protocolInfoField(Class<?> handlerClass) {
        if (PROTOCOL_INFO_CLASS == null) {
            return null;
        }
        Object cached = PROTOCOL_INFO_FIELDS.computeIfAbsent(handlerClass, c -> {
            Field f = ChannelUtils.findFieldOfType(c, PROTOCOL_INFO_CLASS);
            return f == null ? ABSENT : f;
        });
        return cached == ABSENT ? null : (Field) cached;
    }

    private static Method codecAccessor(Object protocolInfo) {
        Method cached = codecAccessor;
        if (cached != null && cached.getDeclaringClass().isInstance(protocolInfo)) {
            return cached;
        }

        Class<?> type = PROTOCOL_INFO_CLASS != null ? PROTOCOL_INFO_CLASS : protocolInfo.getClass();
        try {
            Method m = type.getMethod("codec");
            m.setAccessible(true);
            codecAccessor = m;
            return m;
        } catch (NoSuchMethodException ignored) {
        }

        if (STREAM_CODEC_CLASS != null) {
            for (Method m : type.getMethods()) {
                if (m.getParameterCount() == 0 && STREAM_CODEC_CLASS.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    codecAccessor = m;
                    LOGGER.info("Watchcat: resolved protocol codec accessor " + type.getSimpleName()
                            + "." + m.getName() + "()");
                    return m;
                }
            }
        }
        return null;
    }

    private static Method streamCodecEncode(Object codec) {
        Method cached = streamCodecEncode;
        if (cached != null && cached.getDeclaringClass().isInstance(codec)) {
            return cached;
        }

        if (STREAM_CODEC_CLASS != null) {
            try {
                Method m = STREAM_CODEC_CLASS.getMethod("encode", Object.class, Object.class);
                m.setAccessible(true);
                streamCodecEncode = m;
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }

        for (Class<?> c = codec.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("encode") && m.getParameterCount() == 2) {
                    m.setAccessible(true);
                    streamCodecEncode = m;
                    return m;
                }
            }
        }
        return null;
    }

    private static byte[] encodeWithHandler(ChannelHandlerContext ctx, Object nmsPacket)
            throws ReflectiveOperationException {
        ChannelHandler handler = ctx.handler();
        Object cached = HANDLER_ENCODE_METHODS.computeIfAbsent(handler.getClass(), c -> {
            Method m = findEncodeMethod(c);
            return m == null ? ABSENT : m;
        });
        if (cached == ABSENT) {
            return null;
        }

        ByteBuf buf = Unpooled.buffer();
        try {
            ((Method) cached).invoke(handler, ctx, nmsPacket, buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static Method findEncodeMethod(Class<?> handlerClass) {
        Method bridge = null;
        for (Class<?> c = handlerClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("encode") || m.getParameterCount() != 3) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params[0] != ChannelHandlerContext.class || params[2] != ByteBuf.class) {
                    continue;
                }
                m.setAccessible(true);
                if (params[1] == Object.class) {
                    bridge = m;
                } else {
                    return m;
                }
            }
        }
        return bridge;
    }

    private static ChannelHandlerContext findEncoderContext(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        ChannelHandlerContext named = pipeline.context("encoder");
        if (named != null && protocolInfoField(named.handler().getClass()) != null) {
            return named;
        }

        for (Map.Entry<String, ChannelHandler> entry : pipeline) {
            ChannelHandler handler = entry.getValue();
            if (protocolInfoField(handler.getClass()) != null) {
                ChannelHandlerContext ctx = pipeline.context(handler);
                if (ctx != null) {
                    return ctx;
                }
            }
        }

        return named;
    }

    private static void dumpDiagnosticsOnce(Player player, Channel channel) {
        if (!diagnosticsDumped.compareAndSet(false, true)) {
            return;
        }

        StringBuilder sb = new StringBuilder(1024);
        sb.append("Watchcat packet-serialization diagnostics (logged once)\n");
        sb.append("  player           = ").append(player.getName()).append('\n');
        sb.append("  server           = ").append(player.getServer().getVersion()).append('\n');

        try {
            sb.append("  handle           = ").append(className(ChannelUtils.getHandle(player))).append('\n');
            sb.append("  packetListener   = ").append(className(ChannelUtils.getPacketListener(player))).append('\n');
            sb.append("  connection       = ").append(className(ChannelUtils.getConnection(player))).append('\n');
        } catch (ReflectiveOperationException | RuntimeException e) {
            sb.append("  (reflection walk failed: ").append(describe(e)).append(")\n");
        }

        sb.append("  ProtocolInfo     = ").append(PROTOCOL_INFO_CLASS == null
                ? "NOT FOUND (net.minecraft.network.ProtocolInfo)" : PROTOCOL_INFO_CLASS.getName()).append('\n');
        sb.append("  StreamCodec      = ").append(STREAM_CODEC_CLASS == null
                ? "NOT FOUND (net.minecraft.network.codec.StreamCodec)" : STREAM_CODEC_CLASS.getName()).append('\n');

        if (channel == null) {
            sb.append("  channel          = NOT FOUND\n");
        } else {
            sb.append("  channel          = ").append(channel.getClass().getName())
                    .append(" (active=").append(channel.isActive()).append(")\n");
            sb.append("  pipeline:\n");
            for (Map.Entry<String, ChannelHandler> entry : channel.pipeline()) {
                Class<?> handlerClass = entry.getValue().getClass();
                sb.append("    - ").append(entry.getKey()).append(" : ").append(handlerClass.getName());
                if (protocolInfoField(handlerClass) != null) {
                    sb.append("   [carries ProtocolInfo]");
                }
                sb.append('\n');
            }
        }

        sb.append("  Please include this block when reporting a Watchcat recording failure.");
        LOGGER.warning(sb.toString());
    }

    private static String className(Object o) {
        return o == null ? "NOT FOUND" : o.getClass().getName();
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null ? t.getClass().getName() : t.getClass().getSimpleName() + ": " + message;
    }

    private static final class AdventureLocale {
        private static final Object UNAVAILABLE = new Object();

        private static volatile ThreadLocal<Object> holder;
        private static volatile AttributeKey<?> attributeKey;
        private static volatile boolean resolved;

        private AdventureLocale() {}

        @SuppressWarnings("unchecked")
        static Object swapIn(Channel channel) {
            if (channel == null) {
                return UNAVAILABLE;
            }
            try {
                resolve();
                ThreadLocal<Object> local = holder;
                AttributeKey<?> key = attributeKey;
                if (local == null || key == null) {
                    return UNAVAILABLE;
                }
                Object previous = local.get();
                local.set(channel.attr((AttributeKey<Object>) key).get());
                return previous;
            } catch (RuntimeException e) {
                return UNAVAILABLE;
            }
        }

        static void restore(Object previous) {
            if (previous == UNAVAILABLE) {
                return;
            }
            try {
                ThreadLocal<Object> local = holder;
                if (local != null) {
                    local.set(previous);
                }
            } catch (RuntimeException ignored) {
            }
        }

        @SuppressWarnings("unchecked")
        private static void resolve() {
            if (resolved) {
                return;
            }
            synchronized (AdventureLocale.class) {
                if (resolved) {
                    return;
                }
                resolved = true;
                try {
                    Class<?> encoder = ChannelUtils.resolve("net.minecraft.network.PacketEncoder");
                    Class<?> adventure = ChannelUtils.resolve("io.papermc.paper.adventure.PaperAdventure");
                    if (encoder == null || adventure == null) {
                        return;
                    }
                    Field localeField = encoder.getDeclaredField("ADVENTURE_LOCALE");
                    localeField.setAccessible(true);
                    Field keyField = adventure.getDeclaredField("LOCALE_ATTRIBUTE");
                    keyField.setAccessible(true);
                    holder = (ThreadLocal<Object>) localeField.get(null);
                    attributeKey = (AttributeKey<?>) keyField.get(null);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    LOGGER.fine("Watchcat: adventure locale hook unavailable: " + e);
                }
            }
        }
    }

    public static final class PacketSerializationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PacketSerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
