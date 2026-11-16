package com.kaanergun.bladecompanion;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.net.Uri;
import android.widget.EditText;
import com.kaanergun.bladecompanion.core.AssistanceState;
import com.kaanergun.bladecompanion.core.CompanionProtocol;
import com.kaanergun.bladecompanion.core.PairingRecord;
import com.kaanergun.bladecompanion.core.StreamStats;
import com.kaanergun.bladecompanion.net.StreamReportStore;
import com.kaanergun.bladecompanion.net.PhotoExportStore;
import com.kaanergun.bladecompanion.core.PhotoTransfer;
import com.kaanergun.bladecompanion.net.BladeConnection;
import com.kaanergun.bladecompanion.net.PairingStore;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.kaanergun.bladecompanion.core.KernelProbe;
import com.kaanergun.bladecompanion.usb.UsbDiagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String USB_PERMISSION = "com.kaanergun.bladecompanion.USB_PERMISSION";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<UsbDevice> devices = new ArrayList<>();
    private UsbManager usb;
    private LinearLayout content;
    private LinearLayout navigation;
    private int page;
    private boolean reading;
    private String permissionPending;
    private String resultDevice;
    private KernelProbe.Identity identity;
    private int notice;
    private static final int IMPORT_PAIRING = 50, EXPORT_PHOTO = 51, EXPORT_REPORT = 52;
    private PairingStore pairingStore;
    private MarketPanel market;
    private PairingRecord pairing;
    private BladeConnection connection;
    private FramePreview preview;
    private TextView frameLabel, statisticsLabel;
    private StreamReportStore reportStore;
    private PhotoExportStore photoStore;
    private String reportJson, pendingReport;
    private int sessionSeconds=300;
    private EditText hostInput, annotationInput;
    private String annotationText = "";
    private int networkNotice;
    private boolean visible, photoExportPending, photoPreparing;
    private String pendingPhotoName;
    private final Runnable photoChanges=() -> runOnUiThread(() -> { if (!isDestroyed()) { reloadPendingPhoto(); } });
    private com.kaanergun.bladecompanion.core.FrameCoordinates.Point arrowFrom, arrowTo;
    private String arrowSession;
    private long arrowFrame;


    private final BroadcastReceiver usbEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice device = deviceExtra(intent);
                if (permissionPending != null && (device == null || device.getDeviceName().equals(permissionPending))) {
                    UsbDevice current = usb.getDeviceList().get(permissionPending);
                    notice = current != null && usb.hasPermission(current)
                            ? R.string.usb_permission_granted : R.string.usb_permission_denied;
                    permissionPending = null;
                }
            }
            refreshDevices();
            render();
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pairingStore = new PairingStore(this);
        reportStore = new StreamReportStore(this);
        photoStore = new PhotoExportStore(this);
        PhotoExportStore.observe(photoChanges);reloadPendingPhoto();
        final long reportRevision=StreamReportStore.revision();
        worker.execute(() -> {
            try { String report=reportStore.load();runOnUiThread(() -> {
                if (!isDestroyed() && reportRevision==StreamReportStore.revision()) { reportJson=report;render(); }
            }); } catch (Exception ignored) { /* A damaged optional report does not prevent pairing. */ }
        });
        market = new MarketPanel(this, () -> { if (!isDestroyed() && content != null && page == 2) { render(); } });
        market.restore(savedInstanceState);
        final long loadRevision = PairingStore.revision();
        worker.execute(() -> {
            try {
                PairingRecord stored = pairingStore.load();
                runOnUiThread(() -> { if (!isDestroyed() && PairingStore.revision() == loadRevision) { pairing = stored; render(); } });
            } catch (Exception error) {
                runOnUiThread(() -> { if (!isDestroyed() && PairingStore.revision() == loadRevision) { networkNotice = R.string.pairing_read_failed; render(); } });
            }
        });
        usb = (UsbManager) getSystemService(USB_SERVICE);
        if (savedInstanceState != null) {
            page = savedInstanceState.getInt("page", 0);
            sessionSeconds=savedInstanceState.getInt("session_seconds",300)==1800?1800:300;
            pendingReport=savedInstanceState.getString("pending_report");
            if (pendingReport!=null && pendingReport.length()>4096) { pendingReport=null; }
            annotationText = savedInstanceState.getString("annotation", "");
            photoExportPending = savedInstanceState.getBoolean("photo_export", false);
            pendingPhotoName = savedInstanceState.getString("photo_name");
            if (pendingPhotoName == null || !pendingPhotoName.matches("photo-[0-9a-f]{32}\\.jpg")) { photoExportPending = false; pendingPhotoName = null; }
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.canvas));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        LinearLayout heading = column();
        heading.setPadding(dp(24), dp(18), dp(24), dp(12));
        heading.addView(text(getString(R.string.app_name), 20, R.color.ink, true));
        heading.addView(text(getString(R.string.byline), 12, R.color.secondary, false));
        root.addView(heading);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = column();
        content.setPadding(dp(24), dp(16), dp(24), dp(28));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        navigation = new LinearLayout(this);
        navigation.setBackgroundColor(getColor(R.color.surface));
        navigation.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        IntentFilter events = new IntentFilter(USB_PERMISSION);
        events.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        events.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbEvents, events, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerLegacyUsbReceiver(events);
        }
        refreshDevices();
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        if (market != null) { market.resume(); }
        if (content != null) { refreshDevices(); render(); }
    }

    @Override protected void onStop() {
        visible = false;
        if (market != null) { market.stop(); }
        if (connection != null) { connection.close(StreamStats.End.BACKGROUND);retainStatistics();connection = null; }
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preview = null;
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        market.save(state);
        state.putInt("page", page);
        state.putInt("session_seconds",sessionSeconds);
        state.putString("pending_report",pendingReport);
        state.putString("annotation", annotationInput == null ? annotationText : annotationInput.getText().toString());
        state.putBoolean("photo_export", photoExportPending);
        state.putString("photo_name", photoExportPending ? pendingPhotoName : null);
    }

    @Override protected void onDestroy() {
        PhotoExportStore.unobserve(photoChanges);
        if (market != null) { market.destroy(); }
        unregisterReceiver(usbEvents);
        // Drain the bounded private report write queued by onStop.
        worker.shutdown();
        super.onDestroy();
    }

    private void refreshDevices() {
        devices.clear();
        if (usb != null && getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            devices.addAll(usb.getDeviceList().values());
            Collections.sort(devices, (left, right) -> Integer.compare(left.getDeviceId(), right.getDeviceId()));
        }
        boolean present = false;
        boolean pendingPresent = false;
        for (UsbDevice device : devices) {
            if (device.getDeviceName().equals(resultDevice)) { present = true; }
            if (device.getDeviceName().equals(permissionPending)) {
                pendingPresent = true;
                if (usb.hasPermission(device)) {
                    permissionPending = null;
                    notice = R.string.usb_permission_granted;
                }
            }
        }
        if (!present) {
            if (resultDevice != null) { notice = R.string.error_disconnected; }
            identity = null;
            resultDevice = null;
        }
        if (!pendingPresent) { permissionPending = null; }
    }

    private void render() {
        if (content == null) { return; }
        if (annotationInput != null) { annotationText = annotationInput.getText().toString(); }
        annotationInput = null; hostInput = null; preview = null; frameLabel = null; statisticsLabel=null;
        AssistanceState.State current=connection==null?AssistanceState.State.DISCONNECTED:connection.state();
        boolean awake=visible && (current==AssistanceState.State.STARTING || current==AssistanceState.State.ACTIVE);
        if (awake) { getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
        else { getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
        content.removeAllViews();
        navigation.removeAllViews();
        int[] labels = { R.string.nav_device, R.string.nav_connection, R.string.nav_market, R.string.nav_about };
        for (int i = 0; i < labels.length; i++) {
            final int target = i;
            Button item = button(labels[i], false);
            item.setTextSize(13);
            item.setMinWidth(0);
            item.setPadding(dp(2), dp(8), dp(2), dp(8));
            item.setSelected(page == i);
            if (page == i) { item.setBackground(background(R.color.tint, 12)); }
            item.setOnClickListener(view -> { page = target; render(); });
            navigation.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        switch (page) {
            case 1: renderConnection(); break;
            case 2: renderMarket(); break;
            case 3: renderAbout(); break;
            default: renderDevice(); break;
        }
    }

    private void renderDevice() {
        title(R.string.camera_title);
        if (networkNotice != 0) { paragraph(networkNotice); }
        AssistanceState.State state = connection == null ? AssistanceState.State.DISCONNECTED : connection.state();
        int label = state == AssistanceState.State.ACTIVE ? connection.frozen() ? R.string.camera_frozen : R.string.camera_live
                : state == AssistanceState.State.STARTING ? R.string.camera_starting
                : state == AssistanceState.State.READY ? R.string.camera_ready
                : state == AssistanceState.State.CONNECTING ? R.string.camera_connecting : R.string.camera_disconnected;
        paragraph(label);
        preview = new FramePreview(this, (from, to) -> {
            try {
                if (connection != null && connection.displayed() != null) {
                    CompanionProtocol.Frame selected = connection.displayed();
                    connection.arrow(from, to);
                    arrowFrom = from; arrowTo = to; arrowFrame = selected.id;
                    arrowSession = CompanionProtocol.hex(selected.session);
                }
            }
            catch (IllegalArgumentException | IllegalStateException error) { networkNotice = R.string.annotation_invalid; render(); }
        });
        preview.setContentDescription(getString(R.string.preview_description));
        preview.display(connection == null ? null : connection.displayedBitmap(), connection != null && connection.frozen() && !connection.busy());
        CompanionProtocol.Frame selected = connection == null ? null : connection.displayed();
        if (selected!=null && preview.hasImage()) { connection.frameDisplayed(selected); }
        if (selected != null && connection.frozen() && selected.id == arrowFrame
                && CompanionProtocol.hex(selected.session).equals(arrowSession)) { preview.showArrow(arrowFrom, arrowTo); }
        content.addView(preview, new LinearLayout.LayoutParams(-1, dp(260)));
        frameLabel = text("", 13, R.color.secondary, false);
        content.addView(frameLabel);
        statisticsLabel=text("",13,R.color.secondary,false);content.addView(statisticsLabel);
        updateFrameLabel();
        Button duration=button(R.string.camera_duration,false);duration.setText(getString(R.string.camera_duration,sessionSeconds/60));
        duration.setEnabled(state!=AssistanceState.State.ACTIVE && state!=AssistanceState.State.STARTING);
        duration.setOnClickListener(view -> new android.app.AlertDialog.Builder(this).setTitle(R.string.camera_duration_title)
                .setSingleChoiceItems(new String[]{getString(R.string.camera_duration_short),getString(R.string.camera_duration_long)},
                        sessionSeconds==1800?1:0,(dialog,which) -> { sessionSeconds=which==1?1800:300;dialog.dismiss();render(); }).show());
        addWithTopSpace(content,duration,8);
        Button start = button(state == AssistanceState.State.DISCONNECTED ? R.string.open_pairing : R.string.camera_start, true);
        start.setEnabled(state == AssistanceState.State.DISCONNECTED || (state == AssistanceState.State.READY && connection.cameraAvailable() && !connection.busy()));
        start.setOnClickListener(view -> {
            if (state == AssistanceState.State.DISCONNECTED) { page = 1; render(); }
            else { networkNotice = 0; connection.start(sessionSeconds); render(); }
        });
        addWithTopSpace(content, start, 12);
        Button stop = button(R.string.camera_stop, false);
        stop.setEnabled(state == AssistanceState.State.STARTING || state == AssistanceState.State.ACTIVE);
        stop.setOnClickListener(view -> { connection.stop(); retainStatistics(); render(); });
        addWithTopSpace(content, stop, 4);
        Button freeze = button(connection != null && connection.frozen() ? R.string.camera_resume : R.string.camera_freeze, false);
        freeze.setEnabled(state == AssistanceState.State.ACTIVE && connection.displayed() != null && !connection.busy());
        freeze.setOnClickListener(view -> { if (connection.frozen()) { connection.resume(); } else { connection.freeze(); } });
        addWithTopSpace(content, freeze, 4);
        if (connection != null && connection.frozen()) {
            paragraph(R.string.annotation_instruction);
            annotationInput = new EditText(this);
            annotationInput.setHint(R.string.annotation_hint);
            annotationInput.setText(annotationText);
            annotationInput.setSingleLine(true);
            annotationInput.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(160) });
            addWithTopSpace(content, annotationInput, 8);
            Button send = button(R.string.annotation_send, true); send.setEnabled(!connection.busy());
            send.setOnClickListener(view -> {
                try { connection.text(annotationInput.getText().toString()); }
                catch (IllegalArgumentException | IllegalStateException error) { networkNotice = R.string.annotation_invalid; render(); }
            });
            addWithTopSpace(content, send, 4);
        }
        Button photo = button(R.string.photo_save, false);
        photo.setEnabled(state == AssistanceState.State.ACTIVE && connection.displayed() != null && !connection.busy() && !photoExportPending && !photoPreparing && !PhotoExportStore.busy() && pendingPhotoName==null);
        photo.setOnClickListener(view -> connection.photo());
        addWithTopSpace(content, photo, 4);
        if (pendingPhotoName!=null) {
            paragraph(R.string.photo_pending);
            Button retry=button(R.string.photo_retry,true);retry.setEnabled(!photoExportPending && !photoPreparing && !PhotoExportStore.busy());
            retry.setOnClickListener(view -> openPhotoPicker());addWithTopSpace(content,retry,4);
            Button discard=button(R.string.photo_discard,false);discard.setEnabled(!photoExportPending && !photoPreparing && !PhotoExportStore.busy());
            discard.setOnClickListener(view -> discardPhoto());addWithTopSpace(content,discard,4);
        }
        paragraph(R.string.camera_lifecycle_note);
        Button report=button(R.string.stream_report_export,false);
        report.setEnabled(reportJson!=null && pendingReport==null && state!=AssistanceState.State.ACTIVE && state!=AssistanceState.State.STARTING);
        report.setOnClickListener(view -> {
            pendingReport=reportJson;
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE,"Blade-stream-report.json"),EXPORT_REPORT);
        });
        addWithTopSpace(content,report,8);paragraph(R.string.stream_report_note);
    }

    private void retainStatistics() {
        StreamStats.Snapshot snapshot=connection==null?null:connection.statistics();
        if (snapshot==null || "ACTIVE".equals(snapshot.endReason)) { return; }
        String report=snapshot.json();
        if (report.equals(reportJson)) { return; }
        reportJson=report;long revision=StreamReportStore.change(report);
        worker.execute(() -> {
            try { reportStore.save(report,revision); }
            catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) { networkNotice=R.string.stream_report_failed;render(); } }); }
        });
    }

    private void updateFrameLabel() {
        if (frameLabel == null) { return; }
        StreamStats.Snapshot stats=connection==null?null:connection.statistics();
        if (statisticsLabel!=null) { statisticsLabel.setText(stats==null?"":getString(R.string.stream_statistics,stats.received,stats.decoded,stats.displayed,stats.gaps,stats.decodedFps,stats.displayedFps)); }
        CompanionProtocol.Frame current = connection == null ? null : connection.displayed();
        frameLabel.setText(current == null ? getString(R.string.frame_waiting)
                : getString(R.string.frame_identity, Long.toHexString(current.id), current.width, current.height));
    }

    private void renderConnection() {
        title(R.string.pairing_title);
        if (networkNotice != 0) { paragraph(networkNotice); }
        paragraph(pairing == null ? R.string.pairing_missing : R.string.pairing_present);
        Button load = button(R.string.pairing_import, true);
        load.setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(intent, IMPORT_PAIRING);
        });
        addWithTopSpace(content, load, 8);
        if (pairing != null) {
            hostInput = new EditText(this); hostInput.setSingleLine(true); hostInput.setHint(R.string.host_hint);
            hostInput.setInputType(android.text.InputType.TYPE_CLASS_PHONE); hostInput.setText(pairing.host);
            addWithTopSpace(content, hostInput, 12);
            Button connect = button(R.string.network_connect, true);
            connect.setEnabled(connection == null || connection.state() == AssistanceState.State.DISCONNECTED);
            connect.setOnClickListener(view -> connectNetwork());
            addWithTopSpace(content, connect, 4);
            Button disconnect = button(R.string.network_disconnect, false);
            disconnect.setEnabled(connection != null && connection.state() != AssistanceState.State.DISCONNECTED);
            disconnect.setOnClickListener(view -> { connection.close(); retainStatistics(); connection = null; render(); });
            addWithTopSpace(content, disconnect, 4);
            Button forget = button(R.string.pairing_forget, false);
            forget.setOnClickListener(view -> forgetPairing());
            addWithTopSpace(content, forget, 4);
        }
        title(R.string.connection_title);
        paragraph(R.string.connection_intro);
        Button refresh = button(R.string.refresh, false);
        refresh.setEnabled(!reading);
        refresh.setOnClickListener(view -> { notice = 0; refreshDevices(); render(); });
        addWithTopSpace(content, refresh, 8);
        if (notice != 0) { addWithTopSpace(content, text(getString(notice), 15, R.color.secondary, false), 12); }
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            empty(R.string.usb_host_missing, R.string.usb_host_missing_body);
        } else if (devices.isEmpty()) {
            empty(R.string.usb_empty_title, R.string.usb_empty_body);
        } else {
            for (UsbDevice device : devices) { renderUsbDevice(device); }
        }
        addWithTopSpace(content, text(getString(R.string.kernel_read_note), 14, R.color.secondary, false), 24);
    }

    private void connectNetwork() {
        try { pairing = pairing.withHost(hostInput.getText().toString().trim()); }
        catch (IllegalArgumentException error) { networkNotice = R.string.host_invalid; render(); return; }
        if (connection != null) { connection.close();retainStatistics(); }
        final PairingRecord selected = pairing;
        final long saveRevision = PairingStore.change();
        worker.execute(() -> {
            try { pairingStore.save(selected, saveRevision); }
            catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) { networkNotice = R.string.pairing_read_failed; render(); } }); }
        });
        networkNotice = 0;
        connection = new BladeConnection(this, pairing, new BladeConnection.Listener() {
            @Override public void changed() { if (visible && !isDestroyed()) { render(); } }
            @Override public void frame(CompanionProtocol.Frame frame, Bitmap image) {
                if (!visible || isDestroyed() || connection == null || connection.displayed() != frame) { return; }
                if (preview != null && !preview.hasImage()) { render(); }
                else {
                    if (preview != null) { preview.display(image, connection.frozen() && !connection.busy());connection.frameDisplayed(frame); }
                    updateFrameLabel();
                }
            }
            @Override public void photo(CompanionProtocol.Photo photo) { if (visible && !isDestroyed()) { preparePhoto(photo); } }
            @Override public void failed(int status) {
                if (!visible || isDestroyed()) { return; }
                retainStatistics();
                networkNotice = status == 1 ? R.string.network_auth_failed : status == 3 ? R.string.network_busy
                        : status == 4 ? R.string.network_ended : status == 5 ? R.string.network_stale
                        : status == 6 ? R.string.network_camera_missing : R.string.network_failed;
                render();
            }
        });
        page = 0; connection.connect(); render();
    }

    private void forgetPairing() {
        if (connection != null) { connection.close();retainStatistics(); connection = null; }
        pairing = null; annotationText = "";
        final long forgetRevision = PairingStore.change();
        worker.execute(() -> {
            try {
                pairingStore.forget(forgetRevision);
                runOnUiThread(() -> {
                    if (!isDestroyed() && PairingStore.revision() == forgetRevision) { networkNotice = R.string.pairing_removed; render(); }
                });
            }
            catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) { networkNotice = R.string.pairing_read_failed; render(); } }); }
        });
        networkNotice = R.string.pairing_removing; render();
    }

    private void reloadPendingPhoto() {
        if (worker.isShutdown()) { return; }
        long revision=PhotoExportStore.revision();
        worker.execute(() -> {
            try {
                PhotoExportStore.Pending pending=photoStore.load();
                runOnUiThread(() -> {
                    if (!isDestroyed() && revision==PhotoExportStore.revision()) { pendingPhotoName=pending==null?null:pending.name;render(); }
                });
            } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) { networkNotice=R.string.photo_failed;render(); } }); }
        });
    }
    private void openPhotoPicker() {
        if (pendingPhotoName==null || photoPreparing || photoExportPending || PhotoExportStore.busy()) { return; }
        photoExportPending=true;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/jpeg").putExtra(Intent.EXTRA_TITLE,"Blade-photo.jpg"),EXPORT_PHOTO);
    }
    private void discardPhoto() {
        final String name=pendingPhotoName;if (name==null || photoPreparing || !PhotoExportStore.beginOperation()) { return; }
        photoPreparing=true;render();
        worker.execute(() -> {
            try {
                PhotoExportStore.Pending pending=photoStore.load();
                if (pending!=null) {
                    if (!name.equals(pending.name)) { throw new java.io.IOException("Photo changed"); }
                    photoStore.discard(pending);
                }
                runOnUiThread(() -> { if (!isDestroyed()) { pendingPhotoName=null;photoPreparing=false;networkNotice=R.string.photo_discarded;render(); } });
            } catch (Exception error) { photoFailed(); }
            finally { PhotoExportStore.endOperation(); }
        });
    }
    private void photoFailed() {
        runOnUiThread(() -> { if (!isDestroyed()) { photoPreparing=false;photoExportPending=false;networkNotice=R.string.photo_failed;reloadPendingPhoto();render(); } });
    }
    private void preparePhoto(CompanionProtocol.Photo photo) {
        if (!PhotoExportStore.beginOperation()) { networkNotice=R.string.photo_pending;render();return; }
        photoPreparing=true;
        worker.execute(() -> {
            PhotoExportStore.Pending prepared=null;
            try {
                prepared=photoStore.prepare(photo.jpeg);
            } catch (Exception error) {
                try {
                    PhotoExportStore.Pending retained=photoStore.load();
                    runOnUiThread(() -> { if (!isDestroyed()) { pendingPhotoName=retained==null?null:retained.name; } });
                } catch (Exception ignored) { /* Preserve the record on every failure. */ }
                photoFailed();
            } finally { PhotoExportStore.endOperation(); }
            if (prepared!=null) {
                final PhotoExportStore.Pending pending=prepared;
                runOnUiThread(() -> {
                    if (isDestroyed()) { return; }
                    pendingPhotoName=pending.name;photoPreparing=false;
                    if (visible) { openPhotoPicker(); } else { render(); }
                });
            }
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == MarketPanel.IMPORT_MANAGEMENT || request == MarketPanel.IMPORT_PACKAGE || request==MarketPanel.IMPORT_CATALOG_PACKAGE
                || request == CatalogPanel.IMPORT_KEY || request == CatalogPanel.IMPORT_CATALOG) {
            market.activityResult(request, result, data); return;
        }
        Uri uri = data == null ? null : data.getData();
        if (request==EXPORT_REPORT) {
            final String report=pendingReport;pendingReport=null;
            if (result!=RESULT_OK || uri==null || report==null) { return; }
            worker.execute(() -> {
                try (OutputStream output=getContentResolver().openOutputStream(uri,"wt")) {
                    if (output==null) { throw new java.io.IOException("No report output"); }
                    output.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));output.flush();
                    runOnUiThread(() -> { if (!isDestroyed()) { networkNotice=R.string.stream_report_saved;render(); } });
                } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) { networkNotice=R.string.stream_report_failed;render(); } }); }
            });return;
        }
        if (request == IMPORT_PAIRING && result == RESULT_OK && uri != null) {
            final long importRevision = PairingStore.change();
            worker.execute(() -> {
                try {
                    byte[] bytes;
                    try (InputStream input = getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        if (input == null) { throw new java.io.IOException("No input"); }
                        byte[] buffer = new byte[1024]; int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (output.size() + count > 4096) { throw new java.io.IOException("File too large"); }
                            output.write(buffer, 0, count);
                        }
                        bytes = output.toByteArray();
                    }
                    PairingRecord imported = PairingStore.parse(bytes); pairingStore.save(imported, importRevision);
                    runOnUiThread(() -> { if (!isDestroyed() && PairingStore.revision() == importRevision) { pairing = imported; networkNotice = R.string.pairing_imported; render(); } });
                } catch (Exception error) {
                    runOnUiThread(() -> { if (!isDestroyed() && PairingStore.revision() == importRevision) { networkNotice = R.string.pairing_invalid; render(); } });
                }
            });
        } else if (request == EXPORT_PHOTO) {
            photoExportPending=false;
            final String name=pendingPhotoName;
            if (name==null) { networkNotice=R.string.photo_failed;render();return; }
            if (result!=RESULT_OK || uri==null) { discardPhoto();return; }
            if (!PhotoExportStore.beginOperation()) { networkNotice=R.string.photo_pending;reloadPendingPhoto();render();return; }
            photoPreparing=true;
            worker.execute(() -> {
                try {
                    PhotoExportStore.Pending pending=photoStore.load();
                    if (pending==null || !name.equals(pending.name)) { throw new java.io.IOException("Photo changed"); }
                    PhotoTransfer.Result copied;
                    try (InputStream input=new FileInputStream(photoStore.file(pending));OutputStream output=getContentResolver().openOutputStream(uri,"wt")) {
                        if (output==null) { throw new java.io.IOException("No photo output"); }
                        copied=PhotoTransfer.copy(input,output,pending.bytes,pending.hash(),bytes -> { });
                    }
                    try (InputStream readback=getContentResolver().openInputStream(uri)) {
                        if (readback==null) { throw new java.io.IOException("No photo readback"); }
                        PhotoTransfer.verify(readback,copied,bytes -> { });
                    }
                    photoStore.discard(pending);
                    runOnUiThread(() -> { if (!isDestroyed()) { pendingPhotoName=null;photoPreparing=false;networkNotice=R.string.photo_saved;render(); } });
                } catch (Exception error) { photoFailed(); }
                finally { PhotoExportStore.endOperation(); }
            });
        }
    }

    private void renderUsbDevice(UsbDevice device) {
        boolean candidate = UsbDiagnostics.profile(device) != null;
        LinearLayout row = panel();
        row.addView(text(getString(candidate ? R.string.usb_candidate : R.string.usb_other), 18, R.color.ink, true));
        addWithTopSpace(row, text(getString(R.string.usb_ids, device.getVendorId(), device.getProductId()), 14, R.color.secondary, false), 8);
        int interfaceCount = interfaceCount(device);
        row.addView(text(getResources().getQuantityString(R.plurals.usb_interfaces,
                interfaceCount, interfaceCount), 14, R.color.secondary, false));
        addWithTopSpace(row, text(getString(candidate ? R.string.usb_candidate_body : R.string.usb_other_body), 15, R.color.secondary, false), 12);
        if (candidate) {
            boolean granted = usb.hasPermission(device);
            Button action = button(reading ? R.string.reading_kernel : granted ? R.string.read_kernel : R.string.usb_permission, true);
            action.setEnabled(!reading && permissionPending == null);
            action.setOnClickListener(view -> { if (granted) { readKernel(device); } else { requestUsbPermission(device); } });
            addWithTopSpace(row, action, 14);
        }
        if (identity != null && device.getDeviceName().equals(resultDevice)) {
            addWithTopSpace(row, text(getString(R.string.kernel_result, identity.release, identity.architecture), 17, R.color.verified, true), 16);
        }
        addWithTopSpace(content, row, 16);
    }

    private static int interfaceCount(UsbDevice device) {
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (int i = 0; i < device.getInterfaceCount(); i++) { ids.add(device.getInterface(i).getId()); }
        return ids.size();
    }

    private void requestUsbPermission(UsbDevice device) {
        permissionPending = device.getDeviceName();
        notice = R.string.usb_permission_wait;
        try {
            PendingIntent response = PendingIntent.getBroadcast(this, device.getDeviceId(),
                    new Intent(USB_PERMISSION).setPackage(getPackageName()),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            usb.requestPermission(device, response);
        } catch (RuntimeException error) {
            permissionPending = null;
            notice = R.string.usb_permission_error;
        }
        render();
    }

    private void readKernel(UsbDevice device) {
        reading = true;
        identity = null;
        notice = R.string.reading_kernel;
        render();
        worker.execute(() -> {
            KernelProbe.Identity value = null;
            int message = R.string.kernel_received;
            try {
                value = UsbDiagnostics.readKernel(usb, device);
            } catch (UsbDiagnostics.ProbeException error) {
                message = failureMessage(error.reason);
            } catch (SecurityException error) {
                message = R.string.error_permission;
            } catch (RuntimeException error) {
                message = R.string.error_disconnected;
            }
            final KernelProbe.Identity result = value;
            final int resultMessage = message;
            runOnUiThread(() -> {
                if (isDestroyed()) { return; }
                reading = false;
                identity = result;
                resultDevice = result == null ? null : device.getDeviceName();
                notice = resultMessage;
                refreshDevices();
                render();
            });
        });
    }

    private int failureMessage(UsbDiagnostics.Failure reason) {
        switch (reason) {
            case PERMISSION: return R.string.error_permission;
            case PROFILE: return R.string.error_profile;
            case CLAIM: return R.string.error_claim;
            case CONTROL: return R.string.error_control;
            case SEND: return R.string.error_send;
            case TIMEOUT: return R.string.error_timeout;
            case REPLY: return R.string.error_reply;
            case CANCELLED: return R.string.error_cancelled;
            default: return R.string.error_disconnected;
        }
    }

    private void renderMarket() {
        market.render(content);
    }

    private void renderAbout() {
        title(R.string.about_title);
        paragraph(R.string.about_body);
        LinearLayout about = panel();
        about.addView(text(getString(R.string.app_name), 22, R.color.ink, true));
        addWithTopSpace(about, text(getString(R.string.byline), 16, R.color.secondary, false), 8);
        addWithTopSpace(about, text(getString(R.string.about_version, BuildConfig.VERSION_NAME), 14, R.color.secondary, false), 16);
        addWithTopSpace(about, text(getString(R.string.about_license), 16, R.color.action, true), 8);
        addWithTopSpace(content, about, 20);
        paragraph(R.string.about_unofficial);
        paragraph(R.string.about_development);
        paragraph(R.string.about_privacy);
    }

    private void title(int resource) { content.addView(text(getString(resource), 30, R.color.ink, true)); }
    private void paragraph(int resource) { addWithTopSpace(content, text(getString(resource), 16, R.color.secondary, false), 12); }
    private void empty(int title, int body) {
        LinearLayout panel = panel();
        panel.addView(text(getString(title), 20, R.color.ink, true));
        addWithTopSpace(panel, text(getString(body), 16, R.color.secondary, false), 12);
        addWithTopSpace(content, panel, 24);
    }
    private LinearLayout column() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }
    private LinearLayout panel() {
        LinearLayout result = column();
        result.setPadding(dp(20), dp(20), dp(20), dp(20));
        result.setBackground(background(R.color.surface, 20));
        return result;
    }
    private TextView text(String value, int size, int color, boolean emphasis) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(getColor(color));
        result.setLineSpacing(dp(3), 1);
        if (emphasis) { result.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); }
        return result;
    }
    private Button button(int label, boolean primary) {
        Button result = new Button(this);
        result.setText(label);
        result.setAllCaps(false);
        result.setTextSize(16);
        result.setMinimumHeight(dp(48));
        result.setGravity(Gravity.CENTER);
        result.setTextColor(getColor(primary ? R.color.surface : R.color.action));
        if (primary) { result.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.action))); }
        return result;
    }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(getColor(color));
        result.setCornerRadius(dp(radius));
        return result;
    }
    private void addWithTopSpace(LinearLayout parent, View child, int space) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(space);
        parent.addView(child, layout);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyUsbReceiver(IntentFilter filter) {
        // Before API 33 there is no NOT_EXPORTED flag; a signature permission limits senders.
        registerReceiver(usbEvents, filter, getPackageName() + ".permission.USB_EVENTS", null);
    }
    @SuppressWarnings("deprecation")
    private static UsbDevice deviceExtra(Intent intent) {
        return Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }
}
