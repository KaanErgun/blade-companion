package com.kaanergun.bladecompanion.net;

import com.kaanergun.bladecompanion.core.SignedCatalog;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import javax.net.ssl.HttpsURLConnection;

/** One explicit HTTPS request with platform certificate/hostname verification. */
public final class CatalogHttp implements AutoCloseable {
    private static final java.util.concurrent.ExecutorService CLEANUP=java.util.concurrent.Executors.newSingleThreadExecutor(
            task -> new Thread(task,"blade-http-cleanup"));
    private volatile HttpsURLConnection connection;
    private volatile InputStream input;
    private volatile boolean closed;
    public InputStream open(String url,long maximum) throws IOException {
        if (closed) { throw new IOException("Download cancelled"); }
        HttpsURLConnection selected=(HttpsURLConnection)SignedCatalog.url(url,true).toURL().openConnection(Proxy.NO_PROXY);
        connection=selected;
        if (closed) { selected.disconnect();throw new IOException("Download cancelled"); }
        selected.setConnectTimeout(10000);selected.setReadTimeout(10000);selected.setInstanceFollowRedirects(false);
        selected.setUseCaches(false);selected.setRequestProperty("Accept-Encoding","identity");
        if (selected.getResponseCode()!=200) { throw new IOException("Download response"); }
        String encoding=selected.getContentEncoding();
        if (encoding!=null && !"identity".equalsIgnoreCase(encoding)) { throw new IOException("Compressed HTTP body unsupported"); }
        String length=selected.getHeaderField("Content-Length");
        if (length!=null) {
            try { long bytes=Long.parseLong(length);if (bytes<0 || bytes>maximum) { throw new IOException("Download size"); } }
            catch (NumberFormatException error) { throw new IOException("Download length"); }
        }
        input=selected.getInputStream();if (closed) { input.close();throw new IOException("Download cancelled"); }return input;
    }
    public byte[] catalog(String url) throws IOException {
        long deadline=System.nanoTime()+30000000000L;
        try (InputStream source=open(url,SignedCatalog.MAX_BYTES);ByteArrayOutputStream destination=new ByteArrayOutputStream()) {
            byte[] block=new byte[4096];int count;
            while ((count=source.read(block))!=-1) {
                if (closed || System.nanoTime()>=deadline || count<=0 || destination.size()>SignedCatalog.MAX_BYTES-count) { throw new IOException("Catalog download bound"); }
                destination.write(block,0,count);
            }
            if (closed || System.nanoTime()>=deadline) { throw new IOException("Catalog download cancelled"); }
            return destination.toByteArray();
        }
    }
    @Override public synchronized void close() {
        if (closed) { return; }
        closed=true;InputStream source=input;input=null;
        HttpsURLConnection selected=connection;connection=null;
        Runnable cleanup=() -> {
            if (selected!=null) { selected.disconnect(); }
            if (source!=null) { try { source.close(); } catch (IOException ignored) { } }
        };
        // HTTPS teardown may write TLS records; lifecycle cancellation must never do that on UI.
        if (android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) { CLEANUP.execute(cleanup); }
        else { cleanup.run(); }
    }
}
