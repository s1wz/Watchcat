package dev.watchcat.capture;

import java.lang.reflect.Constructor;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PrologueBuilder {
    private static final Logger LOGGER = Logger.getLogger("Watchcat");

    private static final String LOGIN_PROTOCOLS = "net.minecraft.network.protocol.login.LoginProtocols";
    private static final String LOGIN_FINISHED_PACKET =
            "net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket";
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
    private static volatile Constructor<?> gameProfileConstructor;
    private static volatile Constructor<?> loginFinishedConstructor;
    private static volatile boolean resolved;
    private static volatile boolean resolutionFailed;

    private PrologueBuilder() {}

    public static CapturedPacket loginSuccess(UUID playerUuid, String playerName, long timestamp) {
        if (!resolve()) {
            return null;
        }

        try {
            Object gameProfile = gameProfileConstructor.newInstance(playerUuid, playerName);
            Object packet = loginFinishedConstructor.newInstance(gameProfile, NIL_UUID);
            byte[] raw = PacketSerializer.serializeWithProtocol(loginProtocol, packet);
            return new CapturedPacket(timestamp, "LOGIN_SUCCESS", raw);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Watchcat: could not build the login prologue for "
                    + playerName + " — the replay will not open in ReplayMod", e);
            return null;
        }
    }

    private static boolean resolve() {
        if (resolved) {
            return !resolutionFailed;
        }
        synchronized (PrologueBuilder.class) {
            if (resolved) {
                return !resolutionFailed;
            }
            resolved = true;
            try {
                Class<?> profileClass = Class.forName(GAME_PROFILE);
                Class<?> packetClass = Class.forName(LOGIN_FINISHED_PACKET);

                gameProfileConstructor = profileClass.getConstructor(UUID.class, String.class);
                loginFinishedConstructor = packetClass.getConstructor(profileClass, UUID.class);
                loginProtocol = PacketSerializer.staticProtocolInfo(LOGIN_PROTOCOLS, "CLIENTBOUND");

                if (loginProtocol == null) {
                    resolutionFailed = true;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                resolutionFailed = true;
                LOGGER.log(Level.WARNING, "Watchcat: login prologue unavailable on this server "
                        + "version — replays will not open in ReplayMod", e);
            }
            return !resolutionFailed;
        }
    }
}
