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
        usb = (UsbManager) getSystemService(USB_SERVICE);
        if (savedInstanceState != null) { page = savedInstanceState.getInt("page", 0); }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.canvas));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
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
        if (content != null) { refreshDevices(); render(); }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("page", page);
    }

    @Override protected void onDestroy() {
        unregisterReceiver(usbEvents);
        worker.shutdownNow();
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
        ImageView glasses = new ImageView(this);
        glasses.setImageResource(R.drawable.ic_blade);
        glasses.setContentDescription(getString(R.string.device_illustration));
        content.addView(glasses, new LinearLayout.LayoutParams(-1, dp(150)));
        title(identity == null ? R.string.device_title : R.string.kernel_received);
        paragraph(identity == null ? R.string.device_intro : R.string.kernel_read_note);
        Button scan = button(R.string.scan_usb, true);
        scan.setOnClickListener(view -> { page = 1; refreshDevices(); render(); });
        addWithTopSpace(content, scan, 14);
        LinearLayout state = panel();
        if (identity == null) {
            state.addView(text(getString(R.string.device_status), 18, R.color.ink, true));
            addWithTopSpace(state, text(getString(R.string.device_status_body), 15, R.color.secondary, false), 8);
        } else {
            state.addView(text(getString(R.string.kernel_result, identity.release, identity.architecture), 18, R.color.verified, true));
        }
        addWithTopSpace(content, state, 24);
        addWithTopSpace(content, text(getString(R.string.development_note), 16, R.color.ink, true), 24);
        paragraph(R.string.development_body);
    }

    private void renderConnection() {
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

    private void renderUsbDevice(UsbDevice device) {
        boolean candidate = UsbDiagnostics.profile(device) != null;
        LinearLayout row = panel();
        row.addView(text(getString(candidate ? R.string.usb_candidate : R.string.usb_other), 18, R.color.ink, true));
        addWithTopSpace(row, text(getString(R.string.usb_ids, device.getVendorId(), device.getProductId()), 14, R.color.secondary, false), 8);
        row.addView(text(getResources().getQuantityString(R.plurals.usb_interfaces,
                device.getInterfaceCount(), device.getInterfaceCount()), 14, R.color.secondary, false));
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
        title(R.string.market_title);
        empty(R.string.market_empty_title, R.string.market_empty_body);
        addWithTopSpace(content, text(getString(R.string.market_future), 16, R.color.secondary, false), 24);
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
