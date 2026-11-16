package com.kaanergun.bladecompanion.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

final class MarketTest {
    private static int checks;
    private static final byte[] UPLOAD=new byte[16], HASH=new byte[32];
    private static final String REV="1111111111111111111111111111111111111111111111111111111111111111";
    private MarketTest() { }
    private interface Work { void run() throws Exception; }
    private static void check(boolean value,String reason) { checks++; if (!value) { throw new AssertionError(reason); } }
    private static void rejects(Work work,String reason) { checks++; try { work.run(); } catch (Exception expected) { return; } throw new AssertionError(reason); }
    private static byte[] response(int type,long request,int status,byte[] body) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(0x424c4431); out.writeShort(1); out.writeShort(type|0x8000); out.writeInt((int)request);
        out.writeInt(4+body.length); out.writeInt(status); out.write(body); return bytes.toByteArray();
    }
    private static byte[] read(byte[] bytes,int type,long request) throws IOException {
        return MarketProtocol.read(new DataInputStream(new ByteArrayInputStream(bytes)),type,request);
    }
    static int run() {
        try {
            ByteArrayOutputStream wire=new ByteArrayOutputStream();
            MarketProtocol.write(new DataOutputStream(wire),20,0x10203040L,HASH);
            check(CompanionProtocol.hex(Arrays.copyOf(wire.toByteArray(),16)).equals("424c4431000100141020304000000020"),"shared management AUTH vector");
            rejects(() -> MarketProtocol.write(new DataOutputStream(wire),3,1,new byte[4]),"camera type rejected");
            rejects(() -> MarketProtocol.write(new DataOutputStream(wire),20,0,HASH),"zero request");
            rejects(() -> MarketProtocol.write(new DataOutputStream(wire),20,0x100000000L,HASH),"request overflow");
            rejects(() -> MarketProtocol.write(new DataOutputStream(wire),33,1,new byte[4097]),"control cap");
            rejects(() -> MarketProtocol.write(new DataOutputStream(wire),34,1,new byte[65561]),"chunk cap");
            check(Arrays.equals(MarketProtocol.begin(67108864,HASH),ByteBuffer.allocate(40).putLong(67108864).put(HASH).array()),"u64 size vector");
            for (long size:new long[]{-1,0,21,67108865,Long.MAX_VALUE}) { rejects(() -> MarketProtocol.begin(size,HASH),"invalid size"); }
            rejects(() -> MarketProtocol.begin(22,new byte[31]),"short hash");
            check(MarketProtocol.chunk(UPLOAD,67108863,new byte[]{42},1).length==25,"last byte");
            for (long offset:new long[]{-1,67108864,Long.MAX_VALUE}) { rejects(() -> MarketProtocol.chunk(UPLOAD,offset,new byte[]{42},1),"invalid range"); }
            rejects(() -> MarketProtocol.chunk(new byte[15],0,new byte[]{42},1),"upload identity");
            rejects(() -> MarketProtocol.chunk(UPLOAD,0,new byte[1],0),"empty chunk");
            for (int grant:new int[]{0,4,8,12,16,20,24,28}) { check(MarketProtocol.install(UPLOAD,grant,REV).length==52,"supported grants"); }
            for (int grant:new int[]{-1,1,2,3,32,25}) { rejects(() -> MarketProtocol.install(UPLOAD,grant,REV),"unsupported grants"); }
            check(MarketProtocol.action(5,"org.example.app",REV)[0]==5,"explicit delete action");
            for (String id:new String[]{"", "a", "../x", "org;reboot", "org..app", "ORG.app", "a\nb"}) { rejects(() -> MarketProtocol.status(id),"invalid app ID"); }
            rejects(() -> MarketProtocol.action(6,"org.example.app",REV),"no arbitrary action");
            rejects(() -> MarketProtocol.action(1,"org.example.app","-"),"CAS exact revision");
            check(read(response(20,1,0,new byte[0]),20,1).length==0,"auth success");
            check(read(response(33,2,0,UPLOAD),33,2).length==16,"upload identity reply");
            rejects(() -> read(response(1,1,0,new byte[0]),20,1),"cross role auth reply");
            rejects(() -> read(response(20,2,0,new byte[0]),20,1),"request mismatch");
            rejects(() -> read(response(20,1,0,new byte[1]),20,1),"unexpected auth body");
            rejects(() -> read(response(33,1,0,new byte[15]),33,1),"short upload reply");
            rejects(() -> read(response(32,1,0,new byte[4093]),32,1),"JSON cap");
            rejects(() -> read(response(32,1,8,new byte[0]),32,1),"unknown status");
            rejects(() -> read(response(32,1,1,new byte[]{1}),32,1),"error body forbidden");
            for (int code=1;code<=7;code++) {
                try { read(response(32,1,code,new byte[0]),32,1); throw new AssertionError("Expected rejection"); }
                catch (CompanionProtocol.RemoteException error) { check(error.status==code,"all error codes"); }
            }
            byte[] oversized=response(32,1,0,new byte[0]); ByteBuffer.wrap(oversized).putInt(12,-1);
            rejects(() -> read(oversized,32,1),"unsigned length before allocation");
            rejects(() -> read(new byte[15],32,1),"truncated header");
            MarketProtocol.nextOffset(ByteBuffer.allocate(8).putLong(65536).array(),65536); checks++;
            rejects(() -> MarketProtocol.nextOffset(ByteBuffer.allocate(8).putLong(65535).array(),65536),"wrong next offset");
            check(new MarketProtocol.App("org.example.app",20000,"1.0.0",REV,REV,24,"app",true,true,false).manageable(),"normal app");
            check(!new MarketProtocol.App("org.example.root",0,"1.0.0",REV,REV,32,"root",true,true,true).manageable(),"root read only");
            rejects(() -> new MarketProtocol.App("org.example.app",20000,null,REV,REV,0,"app",true,true,false),"installed version required");
            rejects(() -> new MarketProtocol.App("org.example.app",20000,null,MarketProtocol.EMPTY_REVISION,REV,0,"app",false,true,false),"removed cannot be enabled");
            rejects(() -> new MarketProtocol.Preview("org.example.app","1.0.0",REV,REV,1,"app",1,22,true),"unsupported not installable");
            check(!new MarketProtocol.Preview("org.example.app","1.0.0",REV,REV,1,"app",1,22,false).installable,"unsupported preview");
            rejects(() -> new MarketProtocol.Preview("org.example.app","1.0.0",REV,REV,0,"app",513,22,true),"file cap");
            byte[] packageBytes=new byte[100000]; Arrays.fill(packageBytes,(byte)37);
            ByteArrayOutputStream copied=new ByteArrayOutputStream();
            PackageTransfer.Result result=PackageTransfer.copy(new ByteArrayInputStream(packageBytes),copied,bytes -> { });
            check(result.bytes==packageBytes.length && Arrays.equals(copied.toByteArray(),packageBytes),"stream exact bytes");
            check(Arrays.equals(result.sha256,CompanionProtocol.sha256(packageBytes)),"snapshot hash");
            rejects(() -> PackageTransfer.copy(new ByteArrayInputStream(new byte[21]),new ByteArrayOutputStream(),bytes -> { }),"short source");
            rejects(() -> PackageTransfer.copy(new ByteArrayInputStream(packageBytes),new ByteArrayOutputStream(),bytes -> { throw new IOException("cancelled"); }),"cancel before read");
            rejects(() -> PackageTransfer.copy(new ByteArrayInputStream(packageBytes),new OutputStream() {
                @Override public void write(int value) throws IOException { throw new IOException("disk full"); }
            },bytes -> { }),"write failure");
            final long[] written={0}; final int[] largest={0};
            InputStream large=new InputStream() {
                long remaining=MarketProtocol.MAX_PACKAGE+1L;
                @Override public int read() { throw new AssertionError("Per-byte copy"); }
                @Override public int read(byte[] buffer) {
                    largest[0]=Math.max(largest[0],buffer.length);
                    if (remaining==0) { return -1; } int count=(int)Math.min(buffer.length,remaining); remaining-=count; return count;
                }
            };
            rejects(() -> PackageTransfer.copy(large,new OutputStream() {
                @Override public void write(int value) { written[0]++; }
                @Override public void write(byte[] data,int offset,int size) { written[0]+=size; }
            },bytes -> { }),"oversize source");
            check(written[0]==MarketProtocol.MAX_PACKAGE && largest[0]<=65536,"bounded RAM and no overflow byte write");
        } catch (Exception error) { throw new AssertionError(error); }
        return checks;
    }
}
