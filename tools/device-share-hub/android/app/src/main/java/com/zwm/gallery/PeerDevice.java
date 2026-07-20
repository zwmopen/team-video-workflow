package com.zwm.gallery;

public final class PeerDevice {
    public final String id;
    public final String name;
    public final String model;
    public final String ip;
    public final int port;
    public final String state;
    public final int workCount;
    public final long lastSeenMs;

    PeerDevice(String id, String name, String model, String ip, int port, String state,
               int workCount, long lastSeenMs) {
        this.id = id;
        this.name = name == null || name.trim().isEmpty() ? model : name;
        this.model = model == null ? "" : model;
        this.ip = ip;
        this.port = port;
        this.state = state == null ? "online" : state;
        this.workCount = workCount;
        this.lastSeenMs = lastSeenMs;
    }

    static int parseWorkCount(String value) {
        try { return Math.max(-1, Integer.parseInt(value)); }
        catch (Exception ignored) { return -1; }
    }

    boolean equalsForDisplay(PeerDevice other) {
        return other != null && name.equals(other.name) && model.equals(other.model)
                && ip.equals(other.ip) && port == other.port && state.equals(other.state)
                && workCount == other.workCount;
    }
}
