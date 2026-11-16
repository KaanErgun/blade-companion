package com.kaanergun.bladecompanion.net;

import android.content.Context;
import android.util.AtomicFile;
import com.kaanergun.bladecompanion.core.CompanionProtocol;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** One verified photo retained until verified export or explicit discard. */
public final class PhotoExportStore {
    private static final Object LOCK=new Object();
    private static long revision;
    private static final java.util.concurrent.atomic.AtomicBoolean busy=new java.util.concurrent.atomic.AtomicBoolean();
    public static boolean busy() { return busy.get(); }
    public static boolean beginOperation() {
        if (!busy.compareAndSet(false,true)) { return false; }
        synchronized (LOCK) { changed(); }return true;
    }
    public static void endOperation() { busy.set(false);synchronized (LOCK) { changed(); } }
    private static final java.util.Set<Runnable> listeners=new java.util.HashSet<>();
    public static long revision() { synchronized (LOCK) { return revision; } }
    public static void observe(Runnable listener) { synchronized (LOCK) { listeners.add(listener); } }
    public static void unobserve(Runnable listener) { synchronized (LOCK) { listeners.remove(listener); } }
    private static void changed() {
        revision++;
        for (Runnable listener:listeners.toArray(new Runnable[0])) { listener.run(); }
    }
    private final File directory;
    private final AtomicFile record;
    public static final class Pending {
        public final String name;
        public final long bytes;
        private final byte[] hash;
        private Pending(String name,long bytes,byte[] hash) { this.name=name;this.bytes=bytes;this.hash=hash.clone(); }
        public byte[] hash() { return hash.clone(); }
    }
    public PhotoExportStore(Context context) {
        directory=new File(context.getFilesDir(),"photo-export");
        record=new AtomicFile(new File(directory,"pending.json"));
    }
    public Pending load() throws IOException {
        synchronized (LOCK) {
            if (!record.getBaseFile().exists() && !new File(record.getBaseFile()+".bak").exists()) { return null; }
            byte[] bytes;
            try (java.io.InputStream input=record.openRead();java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()) {
                byte[] block=new byte[256];int count;
                while ((count=input.read(block))!=-1) {
                    if (count<=0 || output.size()+count>1024) { throw new IOException("Photo record size"); }
                    output.write(block,0,count);
                }
                bytes=output.toByteArray();
            }
            try {
                JSONObject value=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
                String name=value.getString("name"),hash=value.getString("sha256");
                long size=value.getLong("bytes");
                if (value.length()!=3 || !name.matches("photo-[0-9a-f]{32}\\.jpg")
                        || !hash.matches("[0-9a-f]{64}") || size<1 || size>CompanionProtocol.MAX_JPEG) {
                    throw new IOException("Invalid photo record");
                }
                return new Pending(name,size,CompanionProtocol.unhex(hash,32));
            } catch (org.json.JSONException|IllegalArgumentException error) { throw new IOException("Invalid photo record",error); }
        }
    }
    public Pending prepare(byte[] jpeg) throws IOException {
        if (jpeg==null || jpeg.length<1 || jpeg.length>CompanionProtocol.MAX_JPEG) { throw new IOException("Photo size"); }
        synchronized (LOCK) {
            if (!directory.isDirectory() && !directory.mkdir()) { throw new IOException("Photo directory"); }
            if (load()!=null) { throw new IOException("Photo export already pending"); }
            Pending value=new Pending("photo-"+java.util.UUID.randomUUID().toString().replace("-","")+".jpg",jpeg.length,CompanionProtocol.sha256(jpeg));
            String json="{\"name\":\""+value.name+"\",\"bytes\":"+value.bytes+",\"sha256\":\""+CompanionProtocol.hex(value.hash)+"\"}";
            // Publish the recovery pointer first; a failed cache write stays explicitly discardable.
            write(record,json.getBytes(StandardCharsets.UTF_8));
            try { write(new AtomicFile(file(value)),jpeg);return value; }
            finally { changed(); }
        }
    }
    public File file(Pending value) { return new File(directory,value.name); }
    public void discard(Pending expected) throws IOException {
        synchronized (LOCK) {
            Pending current=load();
            if (current==null || !current.name.equals(expected.name)) { throw new IOException("Photo changed"); }
            File source=file(current);
            if (source.exists() && !source.delete()) { throw new IOException("Photo cleanup"); }
            new AtomicFile(source).delete();record.delete();
            if (record.getBaseFile().exists()) { throw new IOException("Photo record cleanup"); }
            changed();
        }
    }
    private static void write(AtomicFile file,byte[] bytes) throws IOException {
        FileOutputStream output=null;
        try { output=file.startWrite();output.write(bytes);file.finishWrite(output); }
        catch (IOException error) { if (output!=null) { file.failWrite(output); }throw error; }
    }
}
