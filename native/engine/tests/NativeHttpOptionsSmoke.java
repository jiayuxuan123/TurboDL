package dev.turbodl.nativebridge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class NativeHttpOptionsSmoke {
    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        NativeBridge.load(new File(args[0]));
        File directory = new File(args[1]);
        File target = new File(directory, "slice.bin");
        long engine = NativeBridge.nCreate(utf8(new File(directory, "native-http").getPath()),
            1, 4, 8 * 1024 * 1024, 1);
        try {
            long task = NativeBridge.nSubmitHttp(
                engine, utf8(args[2] + "/expired"), utf8(target.getAbsolutePath()), utf8("slice"),
                utf8(args[3]), new byte[][]{utf8("Authorization")}, new byte[][]{utf8("Bearer primary-fixture")},
                1, 262144, 1500, 2000, new byte[8][], 0, 2, 4, null,
                32123, 12345, 32123, 32123, utf8("fixture-slice"),
                new byte[][]{utf8(args[2] + "/file")},
                new byte[][][]{ {utf8("Authorization")} },
                new byte[][][]{ {utf8("Bearer mirror-fixture")} });
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (System.nanoTime() < deadline) {
                NativeSnapshot snapshot = NativeBridge.nQuery(engine, task);
                long state = snapshot.counters[2];
                if (state == 6) {
                    if (snapshot.counters[7] != 32123 || Files.size(target.toPath()) != 32123
                        || !args[3].equals(snapshot.sha256)) {
                        throw new AssertionError("Unexpected native slice output");
                    }
                    return;
                }
                if (state == 7 || state == 8) {
                    throw new AssertionError(snapshot.message);
                }
                Thread.sleep(10);
            }
            throw new AssertionError("Native slice transfer timed out");
        } finally {
            NativeBridge.nDestroy(engine);
        }
    }
}
