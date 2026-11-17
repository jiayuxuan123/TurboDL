import dev.turbodl.nativebridge.NativeBridge;
import dev.turbodl.nativebridge.NativeSnapshot;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class NativeMetalinkSmoke {
    static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    public static void main(String[] args) throws Exception {
        NativeBridge.load(new File(args[0]));
        long engine=NativeBridge.nCreate(utf8(args[1]+"/metal-journal"),2,4,1024*1024,1);
        File output=new File(args[1],"metal-jni.bin");
        String xml="<metalink xmlns=\"urn:ietf:params:xml:ns:metalink\"><file name=\"fixture.bin\"><hash type=\"sha-256\">"+args[3]+"</hash><url>"+args[2]+"</url></file></metalink>";
        try {
            long task=NativeBridge.nSubmitMetalink(engine,utf8(xml),utf8(args[2]),null,
                utf8(output.getAbsolutePath()),utf8("metal-jni"),4,262144,1500,2000,0,1,utf8("fixture-scope"));
            long deadline=System.nanoTime()+10_000_000_000L;NativeSnapshot current;
            while(true) {
                current=NativeBridge.nQuery(engine,task);NativeBridge.nPoll(engine,64);
                if(current.counters[2]==6)break;
                if(current.counters[2]==7 || current.counters[2]==8)throw new AssertionError(current.message);
                if(System.nanoTime()>deadline)throw new AssertionError("JNI Metalink timeout");
                Thread.sleep(10);
            }
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(output.toPath())));
            if(!hash.equals(args[3]))throw new AssertionError("JNI Metalink file hash differs");
            if(current.counters[10]!=1)throw new AssertionError("JNI dropped Metalink host cap");
            if(NativeBridge.nHttpMetrics(engine,task)[7]!=1)throw new AssertionError("JNI Metalink metrics missing");
            System.out.println("JNI_METALINK_SHA_AND_POLICY_OK");
        } finally {NativeBridge.nDestroy(engine);}
    }
}
