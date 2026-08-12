package dev.watchcat.capture;

import java.lang.reflect.Constructor;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PrologueBuilder {
    private static final Logger LOGGER = Logger.getLogger("Watchcat");

    private static final String LOGIN_PROTOCOLS = "net.minecraft.network.protocol.login.LoginProtocols";

    private static final String[] LOGIN_FINISHED_PACKETS = {
            "net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket",
            "net.minecraft.network.protocol.login.ClientboundGameProfilePacket"
    };

    private static final String GAME_PROFILE = "com.mojang.authlib.GameProfile";

    private static final UUID NIL_UUID = new UUID(0L, 0L);

    public static final int PHANTOM_SELF_ENTITY_ID = -2;

    public static byte[] withPhantomSelf(byte[] raw) {
        if (raw == null) {
            return null;
        }

        int offset = 0;
        while (offset < raw.length && (raw[offset] & 0x80) != 0) {
            offset++;
        }
        offset++;

        if (offset + Integer.BYTES > raw.length) {
            return raw;
        }

        byte[] out = raw.clone();
        out[offset]     = (byte) (PHANTOM_SELF_ENTITY_ID >>> 24);
        out[offset + 1] = (byte) (PHANTOM_SELF_ENTITY_ID >>> 16);
        out[offset + 2] = (byte) (PHANTOM_SELF_ENTITY_ID >>> 8);
        out[offset + 3] = (byte) PHANTOM_SELF_ENTITY_ID;
        return out;
    }

    private static volatile Object loginProtocol;
    private static volatile Class<?> gameProfileClass;
    private static volatile Constructor<?> gameProfileConstructor;
    private static volatile Constructor<?> loginFinishedConstructor;
    private static volatile boolean resolved;
    private static volatile String failure;

    private PrologueBuilder() {}

    public static CapturedPacket loginSuccess(UUID playerUuid, String playerName, long timestamp) {
        if (!resolve()) {
            return null;
        }

        try {
            return build(playerUuid, playerName, timestamp);
        } catch (ReflectiveOperationException | RuntimeException e) {
            failure = describe(e);
            LOGGER.log(Level.WARNING, "Watchcat: could not build the login prologue for "
                    + playerName + " — the replay will not open in ReplayMod", e);
            return null;
        }
    }

    public static boolean isAvailable() {
        return failure == null && resolve();
    }

    public static String getFailure() {
        String reason = failure;
        return reason != null ? reason : "unknown";
    }

    public static String selfTest() {
        if (!resolve()) {
            return getFailure();
        }
        try {
            CapturedPacket probe = build(NIL_UUID, "Watchcat", System.currentTimeMillis());
            if (probe.getRawBytes() == null || probe.getRawBytes().length == 0) {
                failure = "the login packet encoded to zero bytes";
                return failure;
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            failure = describe(e);
            return failure;
        }
    }

    private static CapturedPacket build(UUID playerUuid, String playerName, long timestamp)
            throws ReflectiveOperationException {
        Object gameProfile = gameProfileConstructor.newInstance(playerUuid, playerName);
        Object packet = loginFinishedConstructor.newInstance(
                argumentsFor(loginFinishedConstructor, gameProfileClass, gameProfile));
        byte[] raw = PacketSerializer.serializeWithProtocol(loginProtocol, packet);
        return new CapturedPacket(timestamp, "LOGIN_SUCCESS", raw);
    }

    private static boolean resolve() {
        if (resolved) {
            return failure == null;
        }
        synchronized (PrologueBuilder.class) {
            if (resolved) {
                return failure == null;
            }
            resolved = true;
            try {
                gameProfileClass = Class.forName(GAME_PROFILE);
                gameProfileConstructor = profileConstructor(gameProfileClass);
                if (gameProfileConstructor == null) {
                    return fail(GAME_PROFILE + " has no (UUID, String) constructor");
                }

                Class<?> packetClass = null;
                for (String candidate : LOGIN_FINISHED_PACKETS) {
                    packetClass = ChannelUtils.resolve(candidate);
                    if (packetClass != null) {
                        break;
                    }
                }
                if (packetClass == null) {
                    return fail("no login-success packet class found (looked for "
                            + String.join(", ", LOGIN_FINISHED_PACKETS) + ")");
                }

                loginFinishedConstructor = packetConstructor(packetClass, gameProfileClass);
                if (loginFinishedConstructor == null) {
                    return fail("no usable constructor on " + packetClass.getName());
                }

                loginProtocol = PacketSerializer.staticProtocolInfo(LOGIN_PROTOCOLS, "CLIENTBOUND");
                if (loginProtocol == null) {
                    return fail(LOGIN_PROTOCOLS + ".CLIENTBOUND is unreadable");
                }
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Watchcat: login prologue unavailable on this server version", e);
                return fail(describe(e));
            }
        }
    }

    private static boolean fail(String reason) {
        failure = reason;
        LOGGER.warning("Watchcat: the login prologue cannot be built on this server version — "
                + reason + ". Replays recorded here will not open in ReplayMod.");
        return false;
    }

    private static Constructor<?> profileConstructor(Class<?> profileClass) {
        for (Constructor<?> candidate : profileClass.getDeclaredConstructors()) {
            Class<?>[] params = candidate.getParameterTypes();
            if (params.length == 2 && params[0] == UUID.class && params[1] == String.class) {
                candidate.setAccessible(true);
                return candidate;
            }
        }
        return null;
    }

    static Constructor<?> packetConstructor(Class<?> packetClass, Class<?> profileClass) {
        Constructor<?> best = null;
        for (Constructor<?> candidate : packetClass.getDeclaredConstructors()) {
            if (!isSatisfiable(candidate, profileClass)) {
                continue;
            }
            if (best == null || candidate.getParameterCount() < best.getParameterCount()) {
                best = candidate;
            }
        }
        if (best != null) {
            best.setAccessible(true);
        }
        return best;
    }

    private static boolean isSatisfiable(Constructor<?> candidate, Class<?> profileClass) {
        boolean carriesProfile = false;
        for (Class<?> param : candidate.getParameterTypes()) {
            if (isProfileParameter(param, profileClass)) {
                carriesProfile = true;
            } else if (param != UUID.class && param != boolean.class) {
                return false;
            }
        }
        return carriesProfile;
    }

    static Object[] argumentsFor(Constructor<?> candidate, Class<?> profileClass, Object gameProfile) {
        Class<?>[] params = candidate.getParameterTypes();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            if (isProfileParameter(params[i], profileClass)) {
                args[i] = gameProfile;
            } else if (params[i] == UUID.class) {
                args[i] = NIL_UUID;
            } else {
                args[i] = Boolean.FALSE;
            }
        }
        return args;
    }

    private static boolean isProfileParameter(Class<?> param, Class<?> profileClass) {
        return param != Object.class && param.isAssignableFrom(profileClass);
    }

    private static String describe(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }
}
