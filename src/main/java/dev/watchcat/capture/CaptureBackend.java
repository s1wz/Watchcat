package dev.watchcat.capture;

public interface CaptureBackend {

    PrologueSource getPrologueSource();

    int getListenedPacketTypes();

    void shutdown();
}
