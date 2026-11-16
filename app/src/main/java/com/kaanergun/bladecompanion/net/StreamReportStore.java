package com.kaanergun.bladecompanion.net;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Latest aggregate report only, in app-private storage. No frame, endpoint, or credential. */
public final class StreamReportStore {
    private static final Object LOCK=new Object();
    private static long revision;
    private static String latest;
    private final AtomicFile file;
    public StreamReportStore(Context context) { file=new AtomicFile(new File(context.getFilesDir(),"stream-report.json")); }
    public static long revision() { synchronized (LOCK) { return revision; } }
    public static long change(String report) {
        if (report==null || report.getBytes(StandardCharsets.UTF_8).length>4096) { throw new IllegalArgumentException("Report size"); }
        synchronized (LOCK) { latest=report;return ++revision; }
    }
    public String load() throws IOException {
        synchronized (LOCK) {
            if (latest!=null) { return latest; }
            if (!file.getBaseFile().exists() && !new File(file.getBaseFile()+".bak").exists()) { return null; }
            try (java.io.InputStream input=file.openRead();java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()) {
                byte[] block=new byte[512];int count;
                while ((count=input.read(block))!=-1) {
                    if (count<=0 || output.size()+count>4096) { throw new IOException("Report size"); }
                    output.write(block,0,count);
                }
                return new String(output.toByteArray(),StandardCharsets.UTF_8);
            }
        }
    }
    public void save(String report,long requested) throws IOException {
        byte[] bytes=report.getBytes(StandardCharsets.UTF_8);
        if (bytes.length>4096) { throw new IOException("Report size"); }
        synchronized (LOCK) {
            if (requested!=revision) { return; }
            FileOutputStream output=null;
            try { output=file.startWrite();output.write(bytes);file.finishWrite(output); }
            catch (IOException error) { if (output!=null) { file.failWrite(output); }throw error; }
        }
    }
}
