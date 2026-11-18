package dev.turbodl.nativebridge;

import java.io.File;
import java.io.IOException;

/** JNI uses copied UTF-8 input and batched snapshots; file bytes never cross JNI. */
public final class NativeBridge {
    private static boolean loaded;
    private NativeBridge() {}

    public static synchronized void load(File library) throws IOException {
        if (loaded) return;
        File canonical = library.getCanonicalFile();
        File curl = new File(canonical.getParentFile(), "libcurl-x64.dll");
        if (System.getProperty("os.name", "").startsWith("Windows") && curl.isFile()) {
            System.load(curl.getAbsolutePath());
        }
        File torrent = new File(canonical.getParentFile(), "libtorrent-rasterbar.dll");
        if (System.getProperty("os.name", "").startsWith("Windows") && torrent.isFile()) {
            System.load(torrent.getAbsolutePath());
        }
        System.load(canonical.getAbsolutePath());
        loaded = true;
    }

    public static synchronized void loadPackaged() {
        if (loaded) return;
        System.loadLibrary("turbodl_engine");
        loaded = true;
    }

    public static native long nCreate(byte[] directory, int tasks, int connections, long bufferBytes, int flags);
    public static native void nDestroy(long handle);
    public static native void nSetSpeedLimit(long handle, long bytesPerSecond);
    public static native long nSubmit(long handle, byte[] url, byte[] destination, byte[] stableKey,
        byte[] expectedSha256, byte[][] headerNames, byte[][] headerValues, int connections,
        long windowBytes, int connectTimeoutMs, int readTimeoutMs, byte[][] network,
        int httpVersion, int retries, int hostLimit, byte[] cooldownGroup);
    static native long nSubmitHttp(long handle, byte[] url, byte[] destination, byte[] stableKey,
        byte[] expectedSha256, byte[][] headerNames, byte[][] headerValues, int connections,
        long windowBytes, int connectTimeoutMs, int readTimeoutMs, byte[][] network,
        int httpVersion, int retries, int hostLimit, byte[] cooldownGroup, long knownSize,
        long sliceOffset, long sliceLength, long maximumBytes, byte[] representation,
        byte[][] mirrorUrls, byte[][][] mirrorHeaderNames, byte[][][] mirrorHeaderValues);
    static native long nSubmitMedia(long handle, byte[] url, byte[] destination, byte[] stableKey,
        byte[] expectedSha256, byte[][] headerNames, byte[][] headerValues, int connections,
        long windowBytes, int connectTimeoutMs, int readTimeoutMs, byte[][] network,
        int httpVersion, int retries, int hostLimit, byte[] cooldownGroup, byte[] representation,
        int protocol, int parallelSegments, long maximumBandwidth, long maximumSegmentBytes,
        byte[] language, byte[] videoRepresentation, byte[] audioRepresentation, int recordingSeconds);
    static native void nFinishMedia(long handle, long task);
    static native long[] nMediaDetails(long handle, long task);
    public static native NativeSnapshot nQuery(long handle, long task);
    public static native NativeSnapshot[] nPoll(long handle, int capacity);
    public static native void nControl(long handle, long task, int command);
    public static native void nReplaceSource(long handle, long task, byte[] url, byte[][] headerNames, byte[][] headerValues);
    public static native void nForget(long handle, long task);
    public static native String nCapabilities();
    public static native long nSubmitTorrent(long handle, byte[] torrentFileOrMagnet,
        byte[] destinationDirectory, byte[] stableKey, int peerLimit, int seedSeconds,
        int[] selectedFiles, long downloadBytesPerSecond, long uploadBytesPerSecond);
    public static native void nConnectPeer(long handle, long task, byte[] address, int port);
    public static native long[] nHttpMetrics(long handle, long task);
    public static native long nSubmitMetalink(long handle, byte[] document, byte[] baseUrl,
        byte[] fileName, byte[] destination, byte[] stableKey, int connections,
        long windowBytes, int connectTimeoutMs, int readTimeoutMs, int retries,
        int hostLimit, byte[] cooldownGroup);
}
