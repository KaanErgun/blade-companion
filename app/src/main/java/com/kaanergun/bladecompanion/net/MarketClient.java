package com.kaanergun.bladecompanion.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.SystemClock;
import com.kaanergun.bladecompanion.core.CompanionProtocol;
import com.kaanergun.bladecompanion.core.MarketProtocol;
import com.kaanergun.bladecompanion.core.PackageTransfer;
import com.kaanergun.bladecompanion.core.PairingRecord;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** One explicitly authenticated management channel. Close never stops an installed app. */
public final class MarketClient implements AutoCloseable {
    public static final class Page {
        public final List<MarketProtocol.App> apps;
        public final Long next;
        Page(List<MarketProtocol.App> apps, Long next) { this.apps=apps; this.next=next; }
    }
    public static final class Prepared {
        public final MarketProtocol.Preview preview;
        public final MarketProtocol.App previous;
        public final long expiresAt;
        private final byte[] upload;
        Prepared(MarketProtocol.Preview preview, MarketProtocol.App previous, byte[] upload, long expiresAt) {
            this.preview=preview; this.previous=previous; this.upload=upload.clone(); this.expiresAt=expiresAt;
        }
    }
    private final Context context;
    private final PairingRecord pairing;
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
    private volatile Socket socket;
    private volatile SSLSocket secureSocket;
    private volatile boolean closed;
    private DataInputStream input;
    private DataOutputStream output;
    private long request;
    public MarketClient(Context context, PairingRecord pairing) { this.context=context.getApplicationContext(); this.pairing=pairing; }
    public void connect() throws Exception {
        InetAddress address=InetAddress.getByName(pairing.host);
        ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network selected=null;
        for (Network network:manager.getAllNetworks()) {
            LinkProperties properties=manager.getLinkProperties(network);
            if (properties==null) { continue; }
            for (LinkAddress link:properties.getLinkAddresses()) {
                if (sameSubnet(address.getAddress(),link.getAddress().getAddress(),link.getPrefixLength())) {
                    if (selected!=null && !selected.equals(network)) { throw new IOException("Ambiguous local network"); }
                    selected=network;
                }
            }
        }
        Socket plain=selected==null?new Socket():selected.getSocketFactory().createSocket(); socket=plain;
        SSLSocket secure=null;
        try {
            active(); plain.connect(new InetSocketAddress(address,pairing.port),5000); plain.setSoTimeout(5000);
            SSLContext tls=SSLContext.getInstance("TLS");
            tls.init(null,new TrustManager[]{new X509TrustManager() {
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                @Override public void checkClientTrusted(X509Certificate[] chain,String auth) throws CertificateException { throw new CertificateException("Client trust not used"); }
                @Override public void checkServerTrusted(X509Certificate[] chain,String auth) throws CertificateException {
                    if (chain==null || chain.length==0 || !pairing.matchesCertificate(chain[0].getEncoded())) { throw new CertificateException("Certificate pin mismatch"); }
                    chain[0].checkValidity();
                }
            }},null);
            secure=(SSLSocket)tls.getSocketFactory().createSocket(plain,pairing.host,pairing.port,true); secureSocket=secure;
            List<String> protocols=new ArrayList<>();
            for (String item:secure.getSupportedProtocols()) { if (item.equals("TLSv1.2") || item.equals("TLSv1.3")) { protocols.add(item); } }
            secure.setEnabledProtocols(protocols.toArray(new String[0])); active(); secure.setSoTimeout(5000); secure.startHandshake();
            input=new DataInputStream(secure.getInputStream()); output=new DataOutputStream(secure.getOutputStream());
            call(MarketProtocol.AUTH,pairing.token());
        } catch (Exception error) { closeSocket(plain); closeSocket(secure); close(); throw error; }
    }
    private static boolean sameSubnet(byte[] target,byte[] local,int prefix) {
        if (target.length!=local.length || prefix<0 || prefix>local.length*8) { return false; }
        for (int bit=0;bit<prefix;bit++) { int mask=1<<(7-bit%8); if ((target[bit/8]&mask)!=(local[bit/8]&mask)) { return false; } }
        return true;
    }
    private void active() throws IOException { if (closed || Thread.currentThread().isInterrupted()) { throw new IOException("Management cancelled"); } }
    private byte[] call(int type,byte[] payload) throws Exception {
        active();
        int seconds=type==MarketProtocol.INSTALL || type==MarketProtocol.FINISH || type==MarketProtocol.ACTION
                || type==MarketProtocol.LIST || type==MarketProtocol.STATUS?35:15;
        secureSocket.setSoTimeout(seconds*1000);
        ScheduledFuture<?> timeout=deadlines.schedule(this::close,seconds,TimeUnit.SECONDS);
        try { long id=++request; MarketProtocol.write(output,type,id,payload); return MarketProtocol.read(input,type,id); }
        finally { timeout.cancel(false); }
    }
    public Page list(long offset) throws Exception { return MarketJson.page(call(MarketProtocol.LIST,MarketProtocol.list(offset)),offset); }
    public MarketProtocol.App status(String id) throws Exception {
        try {
            MarketProtocol.App app=MarketJson.app(MarketJson.object(call(MarketProtocol.STATUS,MarketProtocol.status(id))));
            if (!id.equals(app.id)) { throw new IOException("Status identity"); } return app;
        } catch (CompanionProtocol.RemoteException error) { if (error.status==4) { return null; } throw error; }
    }
    public Prepared upload(InputStream source, PackageTransfer.Result expected, PackageTransfer.Progress progress) throws Exception {
        active(); long deadline=SystemClock.elapsedRealtime()+MarketProtocol.UPLOAD_LIFE_MS;
        byte[] upload=call(MarketProtocol.BEGIN,MarketProtocol.begin(expected.bytes,expected.sha256));
        long sent=0;
        {
            byte[] data=new byte[MarketProtocol.MAX_CHUNK]; int count;
            while ((count=source.read(data))!=-1) {
                active(); if (SystemClock.elapsedRealtime()>=deadline) { throw new IOException("Upload deadline"); }
                if (count==0 || sent+count>expected.bytes) { throw new IOException("Snapshot size changed"); }
                MarketProtocol.nextOffset(call(MarketProtocol.CHUNK,MarketProtocol.chunk(upload,sent,data,count)),sent+count);
                sent+=count; progress.update(sent);
            }
        }
        if (sent!=expected.bytes) { throw new IOException("Snapshot size changed"); }
        // Native verification starts review time; the absolute upload limit remains.
        MarketProtocol.Preview preview=MarketJson.preview(call(MarketProtocol.FINISH,MarketProtocol.finish(upload)));
        long expiresAt=MarketProtocol.previewDeadline(deadline,SystemClock.elapsedRealtime());
        if (!preview.revision.equals(CompanionProtocol.hex(expected.sha256)) || preview.bytes!=expected.bytes) { throw new IOException("Verified preview mismatch"); }
        MarketProtocol.App previous=status(preview.id);
        return new Prepared(preview,previous,upload,expiresAt);
    }
    public MarketProtocol.App install(Prepared prepared) throws Exception {
        if (!prepared.preview.installable || SystemClock.elapsedRealtime()>=prepared.expiresAt) { throw new IOException("Preview expired or unsupported"); }
        String previous=prepared.previous==null?MarketProtocol.EMPTY_REVISION:prepared.previous.revision;
        MarketJson.receipt(call(MarketProtocol.INSTALL,MarketProtocol.install(prepared.upload,prepared.preview.permissions,previous)),
                "install",prepared.preview.id,prepared.preview.version);
        return status(prepared.preview.id);
    }
    public MarketProtocol.App action(int action, MarketProtocol.App app) throws Exception {
        if (!app.manageable() || !app.installed || action==3 && (app.permissions & MarketProtocol.HUD)!=0) { throw new IllegalArgumentException("Application is owner USB only or absent"); }
        String[] names={"","enable","disable","launch","remove-keep","remove-delete"};
        MarketJson.receipt(call(MarketProtocol.ACTION,MarketProtocol.action(action,app.id,app.revision)),names[action],app.id,null);
        return status(app.id);
    }
    @Override public synchronized void close() {
        if (closed) { return; }
        closed=true; closeSocket(socket);
        // Cancellation can originate on the UI thread; only the raw transport closes there.
        deadlines.execute(() -> closeSocket(secureSocket));
        deadlines.shutdown();
    }
    private static void closeSocket(Socket socket) {
        if (socket!=null) { try { socket.close(); } catch (IOException ignored) { } }
    }
}
