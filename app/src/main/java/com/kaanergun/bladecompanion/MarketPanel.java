package com.kaanergun.bladecompanion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.kaanergun.bladecompanion.core.CompanionProtocol;
import com.kaanergun.bladecompanion.core.MarketProtocol;
import com.kaanergun.bladecompanion.core.PackageTransfer;
import com.kaanergun.bladecompanion.core.PairingRecord;
import com.kaanergun.bladecompanion.core.SignedCatalog;
import com.kaanergun.bladecompanion.net.CatalogHttp;
import com.kaanergun.bladecompanion.net.ManagementPairingStore;
import com.kaanergun.bladecompanion.net.MarketClient;
import com.kaanergun.bladecompanion.net.PairingStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Activity-owned market UI. Every mutation starts from an explicit user action. */
final class MarketPanel {
    static final int IMPORT_MANAGEMENT=60, IMPORT_PACKAGE=61, IMPORT_CATALOG_PACKAGE=64;
    private static final Object JOURNAL_LOCK=new Object();
    private final Activity activity;
    private final Runnable redraw;
    private final ManagementPairingStore store;
    private final SharedPreferences operations;
    private final CatalogPanel catalog;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile long generation;
    private volatile MarketClient client;
    private volatile CatalogHttp download;
    private volatile ParcelFileDescriptor input;
    private volatile CancellationSignal cancellation;
    private boolean alive=true, visible, busy, ready;
    private PairingRecord pairing;
    private List<MarketProtocol.App> apps=Collections.emptyList();
    private Long next;
    private int notice=R.string.market_intro, progress=-1;
    private String pendingId, pendingToken;
    private MarketClient.Prepared prepared;
    private TextView countdown;
    private Uri selectedDocument;
    private String selectedCatalog,selectedId,selectedPackage;
    private boolean selectedFromCatalog;

    MarketPanel(Activity activity,Runnable redraw) {
        this.activity=activity; this.redraw=redraw;
        store=new ManagementPairingStore(activity);
        operations=activity.getSharedPreferences("market_operations",Context.MODE_PRIVATE);
        catalog=new CatalogPanel(activity,redraw,worker,(entry,hash,local) -> {
            if (!local) { upload(null,entry);return; }
            selectedCatalog=hash;selectedId=entry.id;selectedPackage=entry.sha256;
            activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),IMPORT_CATALOG_PACKAGE);
        });
        pendingId=operations.getString("pending_app",null);
        pendingToken=operations.getString("pending_token",null);
        if (pendingId!=null && !MarketProtocol.validId(pendingId)) { pendingId=null; }
        long revision=ManagementPairingStore.revision();
        worker.execute(() -> {
            try {
                PairingRecord saved=store.load();
                main.post(() -> { if (alive && ManagementPairingStore.revision()==revision) { pairing=saved; ready=true; consumeDocument();redraw.run(); } });
            } catch (Exception error) {
                main.post(() -> { if (alive) { ready=true; notice=R.string.market_pairing_failed; redraw.run(); } });
            }
        });
    }
    void restore(android.os.Bundle state) {
        if (state==null) { return; }
        selectedCatalog=state.getString("catalog_selection_hash");selectedId=state.getString("catalog_selection_id");
        selectedPackage=state.getString("catalog_selection_package");selectedFromCatalog=state.getBoolean("catalog_selection_local");
        String uri=state.getString("market_selected_uri");if (uri!=null && uri.length()<=8192) { selectedDocument=Uri.parse(uri); }
    }
    void save(android.os.Bundle state) {
        state.putString("catalog_selection_hash",selectedCatalog);state.putString("catalog_selection_id",selectedId);
        state.putString("catalog_selection_package",selectedPackage);state.putBoolean("catalog_selection_local",selectedFromCatalog);
        state.putString("market_selected_uri",selectedDocument==null?null:selectedDocument.toString());
    }
    void resume() { visible=true; catalog.resume();consumeDocument(); }
    void stop() {
        visible=false; selectedDocument=null;catalog.stop(); cancel();
    }
    void destroy() { alive=false; catalog.destroy(); cancel(); worker.shutdownNow(); }
    private void cancel() {
        generation++; closeTransport(); prepared=null; countdown=null; busy=false; progress=-1;
    }
    private void closeTransport() {
        CatalogHttp request=download; download=null; if (request!=null) { request.close(); }
        MarketClient current=client; client=null; if (current!=null) { current.close(); }
        CancellationSignal signal=cancellation; if (signal!=null) { signal.cancel(); }
        ParcelFileDescriptor descriptor=input; input=null;
        if (descriptor!=null) { try { descriptor.close(); } catch (IOException ignored) { } }
    }
    private boolean current(long ticket) { return alive && generation==ticket; }
    private void check(long ticket) throws IOException {
        if (!current(ticket) || Thread.currentThread().isInterrupted()) { throw new IOException("Market operation cancelled"); }
    }
    private void post(long ticket,Runnable task) { main.post(() -> { if (current(ticket)) { task.run(); redraw.run(); } }); }
    private long begin(int message) {
        cancel(); busy=true; notice=message; redraw.run(); return generation;
    }
    private PairingRecord credentials() throws Exception {
        PairingRecord management=store.load();
        if (management==null) { throw new IOException("Missing management pairing"); }
        PairingRecord camera=new PairingStore(activity).load();
        if (camera!=null && (!MessageDigest.isEqual(camera.certificate(),management.certificate())
                || MessageDigest.isEqual(camera.token(),management.token()))) { throw new IOException("Pairing authority mismatch"); }
        return management;
    }
    private MarketClient connect(long ticket) throws Exception {
        check(ticket); MarketClient selected=new MarketClient(activity,credentials()); client=selected;
        try { check(ticket); selected.connect(); check(ticket); return selected; }
        catch (Exception error) { selected.close(); throw error; }
    }
    private void failed(long ticket,Exception error) {
        int message=R.string.market_error_io;
        if (error instanceof CompanionProtocol.RemoteException) {
            switch (((CompanionProtocol.RemoteException)error).status) {
                case 1: message=R.string.market_error_auth; break;
                case 2: message=R.string.market_error_invalid; break;
                case 3: message=R.string.market_error_busy; break;
                case 4: message=R.string.market_error_missing; break;
                case 5: message=R.string.market_error_stale; break;
                case 6: message=R.string.market_error_unavailable; break;
                default: break;
            }
        }
        final int selected=message;
        post(ticket,() -> {
            closeTransport(); prepared=null; busy=false; progress=-1; notice=selected;
            String recorded=operations.getString("pending_app",null);
            if (recorded!=null && MarketProtocol.validId(recorded)) {
                pendingId=recorded; pendingToken=operations.getString("pending_token",null);
            }
        });
    }
    void activityResult(int request,int result,Intent data) {
        if (request==CatalogPanel.IMPORT_KEY || request==CatalogPanel.IMPORT_CATALOG) { catalog.activityResult(request,result,data); return; }
        Uri uri=data==null?null:data.getData();
        if (result!=Activity.RESULT_OK || uri==null) {
            if (request==IMPORT_CATALOG_PACKAGE) { selectedCatalog=null;selectedId=null;selectedPackage=null; }
            return;
        }
        if (request==IMPORT_MANAGEMENT) { importPairing(uri); }
        else if (request==IMPORT_PACKAGE || request==IMPORT_CATALOG_PACKAGE) {
            selectedDocument=uri;selectedFromCatalog=request==IMPORT_CATALOG_PACKAGE;consumeDocument();
        }
    }
    private void consumeDocument() {
        if (!alive || !visible || !ready || selectedDocument==null) { return; }
        final Uri uri=selectedDocument;selectedDocument=null;
        final String hash=selectedCatalog,id=selectedId,packageHash=selectedPackage;
        boolean local=selectedFromCatalog;selectedCatalog=null;selectedId=null;selectedPackage=null;selectedFromCatalog=false;
        if (pairing==null || busy || pendingId!=null) { notice=R.string.market_error_io;redraw.run();return; }
        if (!local) { upload(uri,null);return; }
        long ticket=begin(R.string.market_preparing);
        worker.execute(() -> {
            try {
                SignedCatalog saved=new com.kaanergun.bladecompanion.net.CatalogStore(activity).load();
                if (saved==null) { throw new IOException("Catalog absent"); }
                SignedCatalog.Entry entry=saved.selected(hash,id,packageHash);check(ticket);
                post(ticket,() -> { busy=false;upload(uri,entry); });
            } catch (Exception error) { failed(ticket,error); }
        });
    }
    private InputStream open(Uri uri,long ticket) throws Exception {
        CancellationSignal signal=new CancellationSignal(); cancellation=signal;
        check(ticket); ParcelFileDescriptor descriptor=activity.getContentResolver().openFileDescriptor(uri,"r",signal);
        if (descriptor==null) { throw new IOException("No document"); }
        input=descriptor;
        try { check(ticket); return new ParcelFileDescriptor.AutoCloseInputStream(descriptor); }
        catch (Exception error) { descriptor.close(); throw error; }
    }
    private void importPairing(Uri uri) {
        long ticket=begin(R.string.market_pairing_loading), revision=ManagementPairingStore.change();
        worker.execute(() -> {
            try {
                byte[] bytes;
                try (InputStream source=open(uri,ticket); ByteArrayOutputStream data=new ByteArrayOutputStream()) {
                    byte[] block=new byte[1024]; int count;
                    while ((count=source.read(block))!=-1) {
                        check(ticket); if (count<=0 || data.size()+count>4096) { throw new IOException("Pairing size"); }
                        data.write(block,0,count);
                    }
                    bytes=data.toByteArray();
                }
                check(ticket); PairingRecord imported=ManagementPairingStore.parse(bytes);
                PairingRecord camera=new PairingStore(activity).load();
                if (camera!=null && (!MessageDigest.isEqual(camera.certificate(),imported.certificate())
                        || MessageDigest.isEqual(camera.token(),imported.token()))) { throw new IOException("Different Blade or same authority"); }
                store.save(imported,revision);
                post(ticket,() -> { pairing=imported; busy=false; notice=R.string.market_pairing_ready; });
            } catch (Exception error) { post(ticket,() -> { busy=false; notice=R.string.market_pairing_failed; }); }
        });
    }
    private void forget() {
        long ticket=begin(R.string.market_pairing_loading), revision=ManagementPairingStore.change(); pairing=null;
        worker.execute(() -> {
            try { store.forget(revision); post(ticket,() -> { busy=false; apps=Collections.emptyList(); next=null; notice=R.string.market_pairing_removed; }); }
            catch (Exception error) { post(ticket,() -> { busy=false; notice=R.string.market_pairing_failed; }); }
        });
    }
    private void refresh(long offset) {
        long ticket=begin(R.string.market_loading);
        worker.execute(() -> {
            MarketClient selected=null;
            try {
                selected=connect(ticket); MarketClient.Page page=selected.list(offset);
                post(ticket,() -> { apps=page.apps; next=page.next; busy=false; notice=R.string.market_loaded; });
            } catch (Exception error) { failed(ticket,error); }
            finally { if (selected!=null) { selected.close(); } }
        });
    }
    private void upload(Uri uri,SignedCatalog.Entry entry) {
        if (!alive || !visible || busy || pairing==null || pendingId!=null) { return; }
        long ticket=begin(R.string.market_preparing);
        worker.execute(() -> {
            File temporary=null;
            CatalogHttp request=uri==null?new CatalogHttp():null;
            download=request;
            try {
                check(ticket); temporary=File.createTempFile("blade-package-",".tmp",activity.getCacheDir());
                // Unlink once both descriptors are open: process death also releases the private snapshot.
                try (FileOutputStream destination=new FileOutputStream(temporary); FileInputStream snapshot=new FileInputStream(temporary);
                     InputStream source=uri!=null?open(uri,ticket):request.open(entry.url,entry.bytes)) {
                    if (!temporary.delete()) { throw new IOException("Cannot unlink private snapshot"); }
                    long deadline=SystemClock.elapsedRealtime()+120000;
                    PackageTransfer.Result stored=PackageTransfer.copy(source,destination,bytes -> {
                        check(ticket); if (SystemClock.elapsedRealtime()>=deadline) { throw new IOException("Document read timeout"); }
                    });
                    destination.getFD().sync();
                    if (entry!=null) { entry.checkDownload(stored); }
                    check(ticket); MarketClient selected=connect(ticket);
                    post(ticket,() -> { notice=R.string.market_uploading; progress=0; });
                    final int[] last={-1};
                    MarketClient.Prepared value=selected.upload(snapshot,stored,bytes -> {
                        check(ticket); int percent=(int)(bytes*100/stored.bytes);
                        if (percent!=last[0]) { last[0]=percent; post(ticket,() -> { progress=percent; }); }
                    });
                    check(ticket);
                    if (entry!=null) { entry.checkPreview(value.preview); }
                    post(ticket,() -> {
                        prepared=value; busy=false; progress=-1;
                        notice=value.preview.installable?R.string.market_verified:R.string.market_unsupported;
                        tick(ticket);
                    });
                }
            } catch (Exception error) { failed(ticket,error); }
            finally { if (request!=null) { request.close(); } if (temporary!=null) { temporary.delete(); } }
        });
    }
    private void tick(long ticket) {
        if (!current(ticket) || prepared==null) { return; }
        long remaining=prepared.expiresAt-SystemClock.elapsedRealtime();
        if (remaining<=0 && !busy) {
            cancel(); notice=R.string.market_expired; redraw.run(); return;
        }
        if (countdown!=null) { countdown.setText(activity.getString(R.string.market_expires,Math.max(0,(remaining+999)/1000))); }
        main.postDelayed(() -> tick(ticket),1000);
    }
    private void markPending(String id,String token) throws IOException {
        synchronized (JOURNAL_LOCK) {
            if (operations.contains("pending_app")) { throw new IOException("Previous operation unresolved"); }
            if (!operations.edit().putString("pending_app",id).putString("pending_token",token).commit()) { throw new IOException("Operation journal failed"); }
        }
    }
    private void clearPending(String token) throws IOException {
        synchronized (JOURNAL_LOCK) {
            String recorded=operations.getString("pending_token",null);
            if (recorded!=null && !recorded.equals(token)) { throw new IOException("Operation journal replaced"); }
            if (!operations.edit().remove("pending_app").remove("pending_token").commit()) { throw new IOException("Operation journal clear failed"); }
        }
    }
    private void install() {
        final MarketClient.Prepared value=prepared; final MarketClient selected=client;
        if (!alive || !visible || busy || value==null || selected==null || SystemClock.elapsedRealtime()>=value.expiresAt) { return; }
        final long ticket=generation; final String token=java.util.UUID.randomUUID().toString();
        pendingToken=token; busy=true; pendingId=value.preview.id; notice=R.string.market_installing; redraw.run();
        worker.execute(() -> {
            try {
                check(ticket); markPending(value.preview.id,token); check(ticket);
                MarketProtocol.App state=selected.install(value); check(ticket); clearPending(token);
                post(ticket,() -> { pendingId=null; prepared=null; busy=false; notice=R.string.market_installed; update(value.preview.id,state); });
            } catch (Exception error) { failed(ticket,error); }
            finally { selected.close(); }
        });
    }
    private void action(int action,MarketProtocol.App app) {
        if (!alive || !visible || busy || pendingId!=null) { return; }
        long ticket=begin(R.string.market_working); final String token=java.util.UUID.randomUUID().toString();
        pendingToken=token; pendingId=app.id;
        worker.execute(() -> {
            MarketClient selected=null;
            try {
                selected=connect(ticket); check(ticket); markPending(app.id,token); check(ticket);
                MarketProtocol.App state=selected.action(action,app); check(ticket); clearPending(token);
                post(ticket,() -> { pendingId=null; busy=false; notice=R.string.market_action_done; update(app.id,state); });
            } catch (Exception error) { failed(ticket,error); }
            finally { if (selected!=null) { selected.close(); } }
        });
    }
    private void queryPending() {
        final String id=pendingId, token=pendingToken; if (id==null) { return; }
        long ticket=begin(R.string.market_loading);
        worker.execute(() -> {
            MarketClient selected=null;
            try {
                selected=connect(ticket); MarketProtocol.App state=selected.status(id); check(ticket); clearPending(token);
                post(ticket,() -> { pendingId=null; busy=false; notice=state==null?R.string.market_not_installed:R.string.market_status_received; update(id,state); });
            } catch (Exception error) { failed(ticket,error); }
            finally { if (selected!=null) { selected.close(); } }
        });
    }
    private void update(String id,MarketProtocol.App app) {
        java.util.ArrayList<MarketProtocol.App> values=new java.util.ArrayList<>(apps);
        for (java.util.Iterator<MarketProtocol.App> items=values.iterator();items.hasNext();) { if (items.next().id.equals(id)) { items.remove(); } }
        if (app!=null) { values.add(app); } Collections.sort(values,(a,b) -> a.id.compareTo(b.id)); apps=values;
    }
    private void picker(int request) {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        activity.startActivityForResult(intent,request);
    }
    void render(LinearLayout content) {
        add(content,label(activity.getString(R.string.market_title),28,true));
        add(content,label(activity.getString(notice),16,false));
        if (!ready) { return; }
        add(content,button(R.string.market_import_pairing,() -> picker(IMPORT_MANAGEMENT),!busy));
        catalog.render(content,pairing!=null && pendingId==null,busy);
        if (pairing==null) { return; }
        add(content,label(activity.getString(R.string.market_endpoint,pairing.host,pairing.port),14,false));
        add(content,button(R.string.market_forget,this::forget,!busy));
        add(content,button(R.string.market_refresh,() -> refresh(0),!busy));
        add(content,button(R.string.market_choose_package,() -> picker(IMPORT_PACKAGE),!busy && pendingId==null));
        if (busy) {
            ProgressBar bar=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(progress<0); if (progress>=0) { bar.setProgress(progress); }
            add(content,bar); add(content,button(R.string.market_cancel,() -> { cancel(); notice=R.string.market_cancelled; redraw.run(); },true));
        }
        if (pendingId!=null) {
            add(content,label(activity.getString(R.string.market_uncertain,pendingId),16,true));
            add(content,button(R.string.market_query,this::queryPending,!busy));
        }
        if (prepared!=null) {
            MarketProtocol.Preview preview=prepared.preview;
            LinearLayout card=card(); add(card,label(activity.getString(R.string.market_preview,preview.id,preview.version),20,true));
            add(card,label(activity.getString(R.string.market_signer,preview.signer),13,false));
            add(card,label(permissions(preview.permissions),15,false));
            countdown=label("",14,false); add(card,countdown);
            if (preview.installable && MarketProtocol.supported(preview.permissions,preview.runAs)) {
                add(card,label(activity.getString(R.string.market_install_note),14,false));
                int title=prepared.previous!=null && prepared.previous.installed?R.string.market_update:R.string.market_install;
                add(card,button(title,() -> new AlertDialog.Builder(activity).setTitle(title)
                        .setMessage(activity.getString(R.string.market_confirm_install,preview.id,preview.version,permissions(preview.permissions)))
                        .setNegativeButton(R.string.market_cancel,null).setPositiveButton(title,(dialog,which) -> install()).show(),!busy));
            } else { add(card,label(activity.getString(R.string.market_unsupported),16,true)); }
            add(content,card);
        }
        if (apps.isEmpty()) { add(content,label(activity.getString(R.string.market_no_apps),16,false)); }
        for (MarketProtocol.App app:apps) {
            LinearLayout card=card(); add(card,label(app.id,18,true));
            add(card,label(activity.getString(R.string.market_app_state,app.version==null?activity.getString(R.string.market_removed):app.version,
                    activity.getString(app.enabled?R.string.market_enabled:R.string.market_disabled),
                    activity.getString(app.running?R.string.market_running:R.string.market_stopped)),15,false));
            add(card,label(permissions(app.permissions),14,false));
            if (app.installed && app.manageable()) {
                add(card,button(app.enabled?R.string.market_disable:R.string.market_enable,() -> action(app.enabled?2:1,app),!busy && pendingId==null));
                if ((app.permissions & MarketProtocol.HUD)!=0) { add(card,label(activity.getString(R.string.market_open_on_glasses),15,true)); }
                else { add(card,button(R.string.market_launch,() -> action(3,app),!busy && pendingId==null && app.enabled && !app.running)); }
                add(card,button(R.string.market_remove,() -> new AlertDialog.Builder(activity).setTitle(R.string.market_remove)
                        .setMessage(activity.getString(R.string.market_remove_confirm,app.id))
                        .setNegativeButton(R.string.market_cancel,null)
                        .setNeutralButton(R.string.market_keep_data,(dialog,which) -> action(4,app))
                        .setPositiveButton(R.string.market_delete_data,(dialog,which) -> action(5,app)).show(),!busy && pendingId==null));
            } else if (!app.manageable()) { add(card,label(activity.getString(R.string.market_owner_only),15,false)); }
            add(content,card);
        }
        if (next!=null) { final long offset=next; add(content,button(R.string.market_next,() -> refresh(offset),!busy)); }
    }
    private String permissions(int permissions) {
        if ((permissions & ~28)!=0) { return activity.getString(R.string.market_unsupported_permissions); }
        int normal=permissions & 24;
        String text=activity.getString(normal==0?R.string.market_permissions_none:normal==24?R.string.market_permissions_both:
                normal==8?R.string.market_permissions_network:R.string.market_permissions_storage);
        return (permissions & MarketProtocol.HUD)!=0?activity.getString(R.string.market_permissions_hud,text):text;
    }
    private int dp(int value) { return Math.round(value*activity.getResources().getDisplayMetrics().density); }
    private void add(LinearLayout parent,View view) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(12); parent.addView(view,p); }
    private TextView label(String value,int size,boolean bold) {
        TextView view=new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(activity.getColor(R.color.ink));
        if (bold) { view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); } return view;
    }
    private Button button(int text,Runnable action,boolean enabled) {
        Button view=new Button(activity); view.setText(text); view.setAllCaps(false); view.setMinimumHeight(dp(48)); view.setEnabled(enabled);
        view.setOnClickListener(ignored -> { if (alive && visible && view.isEnabled()) { action.run(); } }); return view;
    }
    private LinearLayout card() {
        LinearLayout view=new LinearLayout(activity); view.setOrientation(LinearLayout.VERTICAL); view.setPadding(dp(16),dp(4),dp(16),dp(16));
        GradientDrawable background=new GradientDrawable(); background.setColor(activity.getColor(R.color.surface)); background.setCornerRadius(dp(16)); view.setBackground(background); return view;
    }
}
