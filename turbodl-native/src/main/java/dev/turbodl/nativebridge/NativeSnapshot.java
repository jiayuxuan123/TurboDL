package dev.turbodl.nativebridge;

/** One immutable transfer observation; the returned arrays are caller-owned. */
public final class NativeSnapshot {
    public final long[] counters;
    public final String sha256;
    public final String message;
    public NativeSnapshot(long[] counters, String sha256, String message) {
        this.counters = counters;
        this.sha256 = sha256;
        this.message = message;
    }
}
