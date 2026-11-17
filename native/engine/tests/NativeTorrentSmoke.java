import dev.turbodl.nativebridge.NativeBridge;
import dev.turbodl.nativebridge.NativeSnapshot;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class NativeTorrentSmoke {
    static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    public static void main(String[] args) throws Exception {
        NativeBridge.load(new File(args[0]));
        long engine=NativeBridge.nCreate(utf8(args[1]+"/java-journal"),2,8,2*1024*1024,1);
        File target=new File(args[1],"java-torrent");
        try {
            long task=NativeBridge.nSubmitTorrent(engine,utf8(args[2]),utf8(target.getAbsolutePath()),
                utf8("java-magnet"),2,0,new int[0],0,0);
            NativeBridge.nConnectPeer(engine,task,utf8("127.0.0.1"),Integer.parseInt(args[3]));
            long deadline=System.nanoTime()+20_000_000_000L;
            while(true) {
                NativeSnapshot snapshot=NativeBridge.nQuery(engine,task);
                NativeBridge.nPoll(engine,64);
                if(snapshot.counters[2]==6)break;
                if(snapshot.counters[2]==7 || snapshot.counters[2]==8)throw new AssertionError(snapshot.message);
                if(System.nanoTime()>deadline)throw new AssertionError("JNI magnet timeout");
                Thread.sleep(10);
            }
            byte[] bytes=Files.readAllBytes(new File(target,"fixture.bin").toPath());
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if(!hash.equals(args[4]))throw new AssertionError("JNI magnet hash mismatch");
            NativeBridge.nForget(engine,task);
            System.out.println("JNI_MAGNET_SHA_OK");
        } finally { NativeBridge.nDestroy(engine); }
    }
}
