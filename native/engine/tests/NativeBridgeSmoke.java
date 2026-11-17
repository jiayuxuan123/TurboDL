import dev.turbodl.nativebridge.NativeBridge;
import dev.turbodl.nativebridge.NativeSnapshot;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

public final class NativeBridgeSmoke {
    private static byte[] utf8(String s){return s.getBytes(StandardCharsets.UTF_8);}
    public static void main(String[] args) throws Exception {
        NativeBridge.load(new File(args[0]));
        long engine=NativeBridge.nCreate(utf8(args[1]+"/jni-journal"),2,4,1024*1024,1);
        File target=new File(args[1],"JNI-\u4e2d\u6587-\ud83c\udf24.bin");
        byte[][] network=new byte[8][];for(int i=0;i<network.length;i++)network[i]=new byte[0];
        try {
            long task=NativeBridge.nSubmit(engine,utf8(args[2]),utf8(target.getAbsolutePath()),utf8("jvm-task"),utf8(args[3]),new byte[0][],new byte[0][],4,262144,1500,2000,network,0,3,4,new byte[0]);
            long deadline=System.nanoTime()+10_000_000_000L;NativeSnapshot s;
            do {
                s=NativeBridge.nQuery(engine,task);
                NativeBridge.nPoll(engine,64);
                if(s.counters[2]==7 || s.counters[2]==8)throw new AssertionError(s.message);
                if(s.counters[2]==6)break;
                if(System.nanoTime()>deadline)throw new AssertionError("JNI download timed out");
                Thread.sleep(10);
            } while(true);
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target.toPath()));
            StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format("%02x",b&255));
            if(!hex.toString().equals(args[3]) || !s.sha256.equals(args[3]))throw new AssertionError("JNI output digest differs");
            if(s.counters[10]<2)throw new AssertionError("JNI did not exercise parallel requests");
            long[] metrics=NativeBridge.nHttpMetrics(engine,task);
            if(metrics.length!=11 || metrics[0]<1 || metrics[7]!=1)throw new AssertionError("JNI metrics did not reflect HTTP/1 transfer");
            NativeBridge.nForget(engine,task);
            System.out.println("JNI_DOWNLOAD_SHA_OK");
        } finally {NativeBridge.nDestroy(engine);}
        try {NativeBridge.nQuery(engine,1);throw new AssertionError("closed JNI handle remained usable");}
        catch(IllegalStateException expected){System.out.println("JNI_CLOSED_HANDLE_OK");}
    }
}
