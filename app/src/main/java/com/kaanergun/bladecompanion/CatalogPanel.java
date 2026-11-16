package com.kaanergun.bladecompanion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.kaanergun.bladecompanion.core.SignedCatalog;
import com.kaanergun.bladecompanion.net.CatalogHttp;
import com.kaanergun.bladecompanion.net.CatalogStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;

/** Owner-selected catalog UI. Package installation remains in the management panel. */
final class CatalogPanel {
    static final int IMPORT_KEY=62,IMPORT_CATALOG=63;
    interface Selection { void select(SignedCatalog.Entry entry,String catalogHash,boolean local); }
    private final Activity activity;
    private final Runnable redraw;
    private final ExecutorService worker;
    private final Selection selection;
    private final CatalogStore store;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile long generation;
    private volatile ParcelFileDescriptor input;
    private volatile CancellationSignal cancellation;
    private volatile CatalogHttp http;
    private boolean alive=true,visible,busy,ready,supported;
    private byte[] key;
    private SignedCatalog catalog;
    private int notice=R.string.catalog_intro,page;
    private Uri selectedDocument;
    private int selectedRequest;
    CatalogPanel(Activity activity,Runnable redraw,ExecutorService worker,Selection selection) {
        this.activity=activity;this.redraw=redraw;this.worker=worker;this.selection=selection;store=new CatalogStore(activity);
        worker.execute(() -> {
            boolean available=SignedCatalog.available();byte[] saved=null;SignedCatalog value=null;boolean failed=false;
            try { if (available) { saved=store.key();value=store.load(); } }catch (Exception error) { failed=true; }
            final byte[] loadedKey=saved;final SignedCatalog loaded=value;final boolean error=failed;
            main.post(() -> { if (alive) { supported=available;key=loadedKey;catalog=loaded;ready=true;
                if (error) { notice=R.string.catalog_invalid; }consumeDocument();redraw.run(); } });
        });
    }
    void restore(android.os.Bundle state) {
        if (state==null) { return; }
        int request=state.getInt("catalog_import_request");String uri=state.getString("catalog_import_uri");
        if ((request==IMPORT_KEY || request==IMPORT_CATALOG) && uri!=null && uri.length()<=8192) {
            selectedRequest=request;selectedDocument=Uri.parse(uri);
        }
    }
    void save(android.os.Bundle state) {
        state.putInt("catalog_import_request",selectedRequest);
        state.putString("catalog_import_uri",selectedDocument==null?null:selectedDocument.toString());
    }
    void resume() { visible=true;consumeDocument(); }
    void stop() { visible=false;selectedDocument=null;cancel(); }
    void destroy() { alive=false;cancel(); }
    private void cancel() {
        generation++;busy=false;CatalogHttp request=http;http=null;if (request!=null) { request.close(); }
        CancellationSignal signal=cancellation;cancellation=null;if (signal!=null) { signal.cancel(); }
        ParcelFileDescriptor descriptor=input;input=null;if (descriptor!=null) { try { descriptor.close(); }catch (IOException ignored) { } }
    }
    private void check(long ticket) throws IOException { if (!alive || !visible || ticket!=generation || Thread.currentThread().isInterrupted()) { throw new IOException("Catalog cancelled"); } }
    private long begin() { cancel();busy=true;notice=R.string.catalog_loading;redraw.run();return generation; }
    private void post(long ticket,Runnable change) { main.post(() -> { if (alive && generation==ticket) { change.run();redraw.run(); } }); }
    private void failure(long ticket) { post(ticket,() -> { busy=false;notice=R.string.catalog_invalid; }); }
    private byte[] read(Uri uri,int maximum,long ticket) throws Exception {
        CancellationSignal signal=new CancellationSignal();cancellation=signal;check(ticket);
        ParcelFileDescriptor descriptor=activity.getContentResolver().openFileDescriptor(uri,"r",signal);
        if (descriptor==null) { throw new IOException("No document"); }input=descriptor;
        try (InputStream source=new ParcelFileDescriptor.AutoCloseInputStream(descriptor);ByteArrayOutputStream destination=new ByteArrayOutputStream()) {
            check(ticket);byte[] block=new byte[4096];int count;
            while ((count=source.read(block))!=-1) { check(ticket);if (count<=0 || destination.size()>maximum-count) { throw new IOException("Catalog document size"); }destination.write(block,0,count); }
            check(ticket);return destination.toByteArray();
        }
    }
    void activityResult(int request,int result,Intent data) {
        if (result!=Activity.RESULT_OK || data==null || data.getData()==null) { return; }
        if (request!=IMPORT_KEY && request!=IMPORT_CATALOG) { return; }
        selectedRequest=request;selectedDocument=data.getData();consumeDocument();
    }
    private void consumeDocument() {
        // SAF results may arrive before onResume or before the saved key has loaded.
        if (!alive || !visible || !ready || !supported || selectedDocument==null) { return; }
        Uri uri=selectedDocument;int request=selectedRequest;selectedDocument=null;
        long ticket=begin();
        worker.execute(() -> {
            try {
                if (request==IMPORT_KEY) {
                    byte[] candidate=SignedCatalog.publicKey(read(uri,512,ticket));String signer=SignedCatalog.fingerprint(candidate);
                    post(ticket,() -> { busy=false;
                        new AlertDialog.Builder(activity).setTitle(R.string.catalog_trust_title)
                                .setMessage(activity.getString(R.string.catalog_trust_confirm,signer))
                                .setNegativeButton(R.string.market_cancel,null)
                                .setPositiveButton(R.string.catalog_trust_accept,(dialog,which) -> trust(candidate,ticket)).show();
                    });
                } else {
                    byte[] trusted=key;if (trusted==null) { throw new IOException("Catalog key missing"); }
                    accept(read(uri,SignedCatalog.MAX_BYTES,ticket),trusted,null,ticket);
                }
            }catch (Exception error) { failure(ticket); }
        });
    }
    private void trust(byte[] candidate,long expected) {
        if (!alive || !visible || busy || generation!=expected) { return; }long ticket=begin();
        worker.execute(() -> {
            try { check(ticket);store.trust(candidate);post(ticket,() -> { key=candidate;catalog=null;page=0;busy=false;notice=R.string.catalog_key_saved; }); }
            catch (Exception error) { failure(ticket); }
        });
    }
    private void accept(byte[] data,byte[] trusted,String url,long ticket) throws Exception {
        SignedCatalog value=SignedCatalog.verify(data,trusted);check(ticket);store.accept(value,url);
        post(ticket,() -> { catalog=value;page=0;busy=false;notice=R.string.catalog_verified; });
    }
    private void fetch(String url) {
        final byte[] trusted=key;if (trusted==null || !visible || busy) { return; }
        try { SignedCatalog.url(url,true); }catch (IOException error) { notice=R.string.catalog_https_required;redraw.run();return; }
        long ticket=begin();worker.execute(() -> {
            try (CatalogHttp request=new CatalogHttp()) { http=request;check(ticket);accept(request.catalog(url),trusted,url,ticket); }
            catch (Exception error) { failure(ticket); }
        });
    }
    private void sourceDialog() {
        EditText address=new EditText(activity);address.setSingleLine(true);address.setText(store.source());
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(activity).setTitle(R.string.catalog_source_title).setMessage(R.string.catalog_source_note).setView(address)
                .setNegativeButton(R.string.market_cancel,null).setPositiveButton(R.string.catalog_fetch,(dialog,which) -> fetch(address.getText().toString().trim())).show();
    }
    private void picker(int request) { activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),request); }
    void render(LinearLayout content,boolean managementReady,boolean parentBusy) {
        add(content,label(activity.getString(R.string.catalog_title),22));
        if (!ready) { add(content,label(activity.getString(R.string.catalog_loading),15));return; }
        if (!supported) { add(content,label(activity.getString(R.string.catalog_unavailable),15));return; }
        add(content,label(activity.getString(notice),15));
        add(content,button(R.string.catalog_select_key,() -> picker(IMPORT_KEY),!busy&&!parentBusy));
        if (key==null) { return; }
        try { add(content,label(activity.getString(R.string.catalog_signer,SignedCatalog.fingerprint(key)),13)); }catch (IOException ignored) { return; }
        add(content,button(R.string.catalog_import,() -> picker(IMPORT_CATALOG),!busy&&!parentBusy));
        add(content,button(R.string.catalog_fetch,this::sourceDialog,!busy&&!parentBusy));
        if (busy) { add(content,button(R.string.market_cancel,() -> { cancel();notice=R.string.market_cancelled;redraw.run(); },true)); }
        if (catalog==null) { return; }
        add(content,label(activity.getString(R.string.catalog_revision,catalog.revision,catalog.entries.size()),15));
        for (int i=page;i<Math.min(page+8,catalog.entries.size());i++) {
            SignedCatalog.Entry entry=catalog.entries.get(i);
            add(content,label(activity.getString(R.string.catalog_entry,entry.id,entry.version,entry.bytes),18));
            add(content,label(activity.getString(R.string.market_signer,entry.signer),13));
            if (!entry.supported()) { add(content,label(activity.getString(R.string.catalog_entry_unsupported),14)); }
            else {
                add(content,button(R.string.catalog_select_package,() -> selection.select(entry,catalog.sha256,true),managementReady&&!busy&&!parentBusy));
                if (entry.downloadable()) { add(content,button(R.string.catalog_download,() -> selection.select(entry,catalog.sha256,false),managementReady&&!busy&&!parentBusy)); }
            }
        }
        if (!managementReady) { add(content,label(activity.getString(R.string.catalog_management_required),14)); }
        if (page>0) { add(content,button(R.string.catalog_previous,() -> { page=Math.max(0,page-8);redraw.run(); },!busy&&!parentBusy)); }
        if (page+8<catalog.entries.size()) { add(content,button(R.string.market_next,() -> { page+=8;redraw.run(); },!busy&&!parentBusy)); }
    }
    private void add(LinearLayout parent,android.view.View child) { LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=Math.round(12*activity.getResources().getDisplayMetrics().density);parent.addView(child,params); }
    private TextView label(String value,int size) { TextView view=new TextView(activity);view.setText(value);view.setTextSize(size);view.setTextColor(activity.getColor(R.color.ink));return view; }
    private Button button(int text,Runnable action,boolean enabled) { Button button=new Button(activity);button.setText(text);button.setAllCaps(false);button.setEnabled(enabled);button.setOnClickListener(view -> { if (alive&&visible&&button.isEnabled()) { action.run(); } });return button; }
}
