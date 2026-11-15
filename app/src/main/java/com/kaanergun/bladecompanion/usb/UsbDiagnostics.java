package com.kaanergun.bladecompanion.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;

import com.kaanergun.bladecompanion.core.CdcAcmProfile;
import com.kaanergun.bladecompanion.core.KernelProbe;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class UsbDiagnostics {
    private UsbDiagnostics() { }

    public enum Failure { DISCONNECTED, PERMISSION, PROFILE, CLAIM, CONTROL, SEND, TIMEOUT, REPLY, CANCELLED }

    public static final class ProbeException extends Exception {
        private static final long serialVersionUID = 1L;
        public final Failure reason;

        ProbeException(Failure reason) {
            super(reason.name());
            this.reason = reason;
        }
    }

    public static CdcAcmProfile profile(UsbDevice device) {
        List<CdcAcmProfile.Interface> interfaces = new ArrayList<>();
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface item = device.getInterface(i);
            List<CdcAcmProfile.Endpoint> endpoints = new ArrayList<>();
            for (int j = 0; j < item.getEndpointCount(); j++) {
                UsbEndpoint endpoint = item.getEndpoint(j);
                endpoints.add(new CdcAcmProfile.Endpoint(endpoint.getAddress(), endpoint.getType()));
            }
            interfaces.add(new CdcAcmProfile.Interface(item.getId(), item.getInterfaceClass(),
                    item.getInterfaceSubclass(), endpoints));
        }
        return CdcAcmProfile.find(device.getVendorId(), device.getProductId(), interfaces);
    }

    /** Call on a worker thread. USB permission must already have been granted by Android. */
    public static KernelProbe.Identity readKernel(UsbManager manager, UsbDevice device) throws ProbeException {
        CdcAcmProfile profile = profile(device);
        if (profile == null) { throw new ProbeException(Failure.PROFILE); }
        if (!manager.hasPermission(device)) { throw new ProbeException(Failure.PERMISSION); }
        UsbInterface control = null;
        UsbInterface data = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface item = device.getInterface(i);
            if (item.getId() == profile.controlId) { control = item; }
            if (item.getId() == profile.dataId) { data = item; }
        }
        if (control == null || data == null) { throw new ProbeException(Failure.PROFILE); }
        UsbEndpoint input = null;
        UsbEndpoint output = null;
        for (int i = 0; i < data.getEndpointCount(); i++) {
            UsbEndpoint endpoint = data.getEndpoint(i);
            if (endpoint.getAddress() == profile.inputAddress) { input = endpoint; }
            if (endpoint.getAddress() == profile.outputAddress) { output = endpoint; }
        }
        if (input == null || output == null) { throw new ProbeException(Failure.PROFILE); }
        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) { throw new ProbeException(Failure.DISCONNECTED); }
        boolean controlClaimed = false;
        boolean dataClaimed = false;
        boolean dtr = false;
        try {
            controlClaimed = connection.claimInterface(control, true);
            dataClaimed = controlClaimed && connection.claimInterface(data, true);
            if (!dataClaimed) { throw new ProbeException(Failure.CLAIM); }
            byte[] lineCoding = { 0, (byte) 0xc2, 1, 0, 0, 0, 8 };
            if (connection.controlTransfer(0x21, 0x20, 0, control.getId(), lineCoding, 7, 1000) != 7
                    || connection.controlTransfer(0x21, 0x22, 3, control.getId(), null, 0, 1000) != 0) {
                throw new ProbeException(Failure.CONTROL);
            }
            dtr = true;
            KernelProbe probe = new KernelProbe(UUID.randomUUID().toString().replace("-", ""));
            byte[] request = probe.command().getBytes(StandardCharsets.US_ASCII);
            if (connection.bulkTransfer(output, request, request.length, 1000) != request.length) {
                throw new ProbeException(Failure.SEND);
            }
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (Thread.currentThread().isInterrupted()) { throw new ProbeException(Failure.CANCELLED); }
                int length = connection.bulkTransfer(input, buffer, buffer.length, 250);
                if (length <= 0) { continue; }
                if (received.size() + length > KernelProbe.MAX_REPLY_BYTES) { throw new ProbeException(Failure.REPLY); }
                received.write(buffer, 0, length);
                try {
                    KernelProbe.Identity identity = probe.parse(new String(received.toByteArray(), StandardCharsets.US_ASCII));
                    if (identity != null) { return identity; }
                } catch (IllegalArgumentException error) {
                    throw new ProbeException(Failure.REPLY);
                }
            }
            throw new ProbeException(Failure.TIMEOUT);
        } finally {
            try {
                if (dtr) { connection.controlTransfer(0x21, 0x22, 0, control.getId(), null, 0, 250); }
                if (dataClaimed) { connection.releaseInterface(data); }
                if (controlClaimed) { connection.releaseInterface(control); }
            } finally {
                connection.close();
            }
        }
    }
}
