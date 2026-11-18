package dev.turbodl.nativebridge;

import java.io.File;
import java.nio.charset.StandardCharsets;

public final class NativeMediaSmoke {
    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        NativeBridge.load(new File(args[0]));
        File output = new File(args[1], "jni-live.mp4");
        long client = NativeBridge.nCreate(utf8(args[1] + "/jni-media"), 3, 8, 16 * 1024 * 1024, 1);
        try {
            long task = NativeBridge.nSubmitMedia(
                client, utf8(args[2]), utf8(output.getAbsolutePath()), utf8("live"),
                null, null, null, 4, 262144, 1500, 4000, new byte[8][], 0, 2, 8, null,
                null, 1, 4, 0, 64 * 1024 * 1024, null, null, null, 0);
            int segments = Integer.parseInt(args[3]);
            boolean finished = false;
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (System.nanoTime() < deadline) {
                NativeSnapshot snapshot = NativeBridge.nQuery(client, task);
                long stage = snapshot.counters[2];
                if (stage == 7 || stage == 8) {
                    throw new AssertionError(snapshot.message);
                }
                long[] details = NativeBridge.nMediaDetails(client, task);
                if (stage == 6) {
                    if (!finished || details[0] != 5 || details[3] != 1 || details[5] != segments) {
                        throw new AssertionError("Unexpected live media completion details");
                    }
                    return;
                }
                if (!finished && details[5] == segments) {
                    NativeBridge.nFinishMedia(client, task);
                    finished = true;
                }
                Thread.sleep(10);
            }
            throw new AssertionError("JNI live recording did not finish");
        } finally {
            NativeBridge.nDestroy(client);
        }
    }
}
