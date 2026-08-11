package dev.watchcat.capture;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BundleExpander {
    private static final Logger LOGGER = Logger.getLogger("Watchcat");

    private static volatile boolean resolved;
    private static volatile boolean resolutionFailed;

    private static Class<?> bundlePacketClass;
    private static Method subPackets;

    private BundleExpander() {}

    public static boolean isBundle(Object nmsPacket) {
        if (nmsPacket == null) {
            return false;
        }
        if (resolve() && bundlePacketClass.isInstance(nmsPacket)) {
            return true;
        }

        return nmsPacket.getClass().getSimpleName().endsWith("BundlePacket");
    }

    public static List<Object> expand(Object bundle) {
        if (!resolve() || !bundlePacketClass.isInstance(bundle)) {
            return List.of();
        }

        try {
            Object contents = subPackets.invoke(bundle);
            if (!(contents instanceof Iterable<?> iterable)) {
                return List.of();
            }

            List<Object> out = new ArrayList<>();
            for (Object sub : iterable) {
                if (sub != null) {
                    out.add(sub);
                }
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Watchcat: could not expand a bundle packet", e);
            return List.of();
        }
    }

    private static boolean resolve() {
        if (resolved) {
            return !resolutionFailed;
        }
        synchronized (BundleExpander.class) {
            if (resolved) {
                return !resolutionFailed;
            }
            resolved = true;
            try {
                bundlePacketClass = Class.forName("net.minecraft.network.protocol.BundlePacket");
                subPackets = bundlePacketClass.getMethod("subPackets");
            } catch (ReflectiveOperationException | RuntimeException e) {
                resolutionFailed = true;
                LOGGER.log(Level.WARNING, "Watchcat: bundle packets cannot be expanded on this "
                        + "server version — bundled entity spawns will be missing from replays", e);
            }
            return !resolutionFailed;
        }
    }
}
