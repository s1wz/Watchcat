package dev.watchcat.capture;

public final class CapturedPacket {
    private final long timestampMillis;
    private final String packetType;
    private final byte[] rawBytes;

    public CapturedPacket(long timestampMillis, String packetType, byte[] rawBytes) {
        this.timestampMillis = timestampMillis;
        this.packetType = packetType;
        this.rawBytes = rawBytes.clone();
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public String getPacketType() {
        return packetType;
    }

    public byte[] getRawBytes() {
        return rawBytes.clone();
    }

    @Override
    public String toString() {
        return "CapturedPacket{type=" + packetType + ", ts=" + timestampMillis + "}";
    }
}
