package com.kaanergun.bladecompanion.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Properties;

/** Shared public SDK fixture; a future Swift client consumes the same bytes. */
final class GoldenTest {
    private GoldenTest() { }
    private static void wire(Properties actual,String name,int type,long request,byte[] body,boolean management) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream output=new DataOutputStream(bytes);
        if (management) { MarketProtocol.write(output,type,request,body); }
        else { CompanionProtocol.write(output,type,request,body); }
        actual.setProperty("wire."+name,CompanionProtocol.hex(bytes.toByteArray()));
        actual.setProperty("protocol.VERSION",Integer.toString(ByteBuffer.wrap(bytes.toByteArray()).getShort(4)));
        actual.setProperty("protocol.HEADER",Integer.toString(bytes.size()-body.length));
    }
    static int run() {
        try {
            Properties expected=new Properties(),actual=new Properties();
            try (InputStream input=GoldenTest.class.getResourceAsStream("/bld1-v1.properties")) {
                if (input==null) { throw new AssertionError("Shared golden fixture missing"); }
                expected.load(input);
            }
            for (String key:expected.stringPropertyNames()) {
                if (key.startsWith("camera.") || key.startsWith("market.")) {
                    Class<?> type=key.startsWith("camera.")?CompanionProtocol.class:MarketProtocol.class;
                    actual.setProperty(key,Integer.toString(type.getField(key.substring(key.indexOf('.')+1)).getInt(null)));
                }
            }
            String[] status={"OK","UNAUTHORIZED","INVALID","BUSY","NO_SESSION","STALE","UNAVAILABLE","IO"};
            for (int i=0;i<status.length;i++) {
                byte[] body=ByteBuffer.allocate(4).putInt(i).array();
                CompanionProtocol.Packet packet=new CompanionProtocol.Packet(CompanionProtocol.STATUS|0x8000,1,body);
                try {
                    byte[] reply=CompanionProtocol.response(packet,CompanionProtocol.STATUS,1);
                    if (i!=0 || reply.length!=0) { throw new AssertionError("Status response mismatch"); }
                } catch (CompanionProtocol.RemoteException error) {
                    if (i==0 || error.status!=i) { throw new AssertionError("Status error mismatch"); }
                }
                actual.setProperty("status."+status[i],Integer.toString(i));
            }
            byte[] session=new byte[16],token=new byte[32],hash=new byte[32];
            for (int i=0;i<16;i++) { session[i]=(byte)i; }
            for (int i=0;i<32;i++) { token[i]=(byte)(32+i);hash[i]=(byte)(64+i); }
            long request=0x10203040L,frame=0x0102030405060708L;
            wire(actual,"START",CompanionProtocol.START,request++,CompanionProtocol.start(60,CompanionProtocol.PROFILE_VGA),false);
            wire(actual,"TEXT",CompanionProtocol.TEXT,request++,CompanionProtocol.text(session,frame,"Yön"),false);
            wire(actual,"ARROW",CompanionProtocol.ARROW,request++,CompanionProtocol.arrow(session,frame,
                    new FrameCoordinates.Point(0,0),new FrameCoordinates.Point(1,1)),false);
            wire(actual,"AUTH_MANAGEMENT",MarketProtocol.AUTH,request++,token,true);
            wire(actual,"UPLOAD_CHUNK",MarketProtocol.CHUNK,request++,MarketProtocol.chunk(session,65536,new byte[]{65,66,67},3),true);
            wire(actual,"INSTALL",MarketProtocol.INSTALL,request++,MarketProtocol.install(session,
                    MarketProtocol.HUD|MarketProtocol.NETWORK|MarketProtocol.STORAGE,CompanionProtocol.hex(hash)),true);
            wire(actual,"ACTION",MarketProtocol.ACTION,request++,MarketProtocol.action(2,"org.blade.hello",CompanionProtocol.hex(hash)),true);
            wire(actual,"START_HD",CompanionProtocol.START,request,CompanionProtocol.start(60,CompanionProtocol.PROFILE_HD),false);
            if (!expected.equals(actual)) {
                for (String key:expected.stringPropertyNames()) {
                    if (!expected.getProperty(key).equals(actual.getProperty(key))) { throw new AssertionError("Golden mismatch: "+key); }
                }
                throw new AssertionError("Unexpected golden key");
            }
            return expected.size();
        } catch (Exception error) { throw new AssertionError(error); }
    }
}
