package com.kaanergun.bladecompanion.core;

import java.util.ArrayList;
import java.util.List;

/** Discovery of the local diagnostic layouts; descriptors do not authenticate a device. */
public final class CdcAcmProfile {
    public static final int VENDOR = 0x0525;
    public static final int PRODUCT = 0xa4a7;
    public static final int COMPOSITE_VENDOR = 0x1d6b;
    public static final int COMPOSITE_PRODUCT = 0x0104;
    public final int controlId;
    public final int dataId;
    public final int inputAddress;
    public final int outputAddress;
    private final boolean composite;
    private final int networkSubclass;

    private CdcAcmProfile(int networkSubclass, Interface control, Interface data) {
        this.networkSubclass = networkSubclass;
        composite = networkSubclass != 0;
        controlId = control.id;
        dataId = data.id;
        Endpoint first = data.endpoints.get(0);
        Endpoint second = data.endpoints.get(1);
        inputAddress = (first.address & 128) != 0 ? first.address : second.address;
        outputAddress = (first.address & 128) == 0 ? first.address : second.address;
    }

    public static final class Endpoint {
        public final int address;
        public final int type;

        public Endpoint(int address, int type) {
            this.address = address;
            this.type = type;
        }
    }

    public static final class Interface {
        public final int id;
        public final int alternate;
        public final int deviceClass;
        public final int subclass;
        public final int protocol;
        public final List<Endpoint> endpoints;

        public Interface(int id, int alternate, int deviceClass, int subclass, int protocol,
                List<Endpoint> endpoints) {
            this.id = id;
            this.alternate = alternate;
            this.deviceClass = deviceClass;
            this.subclass = subclass;
            this.protocol = protocol;
            this.endpoints = endpoints;
        }
    }

    public static CdcAcmProfile find(int vendor, int product, int deviceClass, int subclass,
            int protocol, int configurations, List<Interface> interfaces) {
        boolean composite = vendor == COMPOSITE_VENDOR && product == COMPOSITE_PRODUCT
                && deviceClass == 0xef && subclass == 2 && protocol == 1;
        boolean standalone = vendor == VENDOR && product == PRODUCT
                && deviceClass == 2 && subclass == 0 && protocol == 0;
        if ((!composite && !standalone) || configurations != 1 || interfaces == null
                || interfaces.size() != (composite ? 5 : 2)) { return null; }
        boolean[] endpoints = new boolean[256];
        for (int i = 0; i < interfaces.size(); i++) {
            Interface item = interfaces.get(i);
            if (item == null || item.id < 0 || item.id > 255 || item.alternate < 0
                    || item.alternate > 1 || item.endpoints == null) { return null; }
            for (int j = 0; j < i; j++) {
                Interface previous = interfaces.get(j);
                if (item.id == previous.id && item.alternate == previous.alternate) { return null; }
            }
            for (Endpoint endpoint : item.endpoints) {
                if (!validEndpoint(endpoint) || endpoints[endpoint.address]) { return null; }
                endpoints[endpoint.address] = true;
            }
        }
        Interface control;
        Interface data;
        int networkSubclass = 0;
        if (composite) {
            Interface ethernet = get(interfaces, 0, 0);
            Interface idle = get(interfaces, 1, 0);
            Interface active = get(interfaces, 1, 1);
            control = get(interfaces, 2, 0);
            data = get(interfaces, 3, 0);
            if ((!matches(ethernet, 2, 6, 0) && !matches(ethernet, 2, 13, 0))
                    || !notification(ethernet)) { return null; }
            networkSubclass = ethernet.subclass;
            int dataProtocol = networkSubclass == 13 ? 1 : 0;
            if (!matches(idle, 10, 0, dataProtocol) || !idle.endpoints.isEmpty()
                    || !matches(active, 10, 0, dataProtocol) || !bulkPair(active)) { return null; }
        } else {
            control = interfaces.get(0);
            data = interfaces.get(1);
            if (control.deviceClass == 10) { control = interfaces.get(1); data = interfaces.get(0); }
            if (control.id == data.id || control.alternate != 0 || data.alternate != 0) { return null; }
        }
        return matches(control, 2, 2, 1) && notification(control)
                && matches(data, 10, 0, 0) && bulkPair(data)
                ? new CdcAcmProfile(networkSubclass, control, data) : null;
    }

    /** Validate the single configuration and CDC unions before claiming any interface. */
    public boolean matchesDescriptors(byte[] raw) {
        if (raw == null || raw.length < 27 || raw.length > 65553 || u(raw, 0) != 18
                || u(raw, 1) != 1 || u(raw, 17) != 1 || u(raw, 18) != 9
                || u(raw, 19) != 2 || word(raw, 20) != raw.length - 18
                || u(raw, 22) != (composite ? 4 : 2) || u(raw, 23) != (composite ? 1 : 2)) { return false; }
        List<Interface> interfaces = new ArrayList<>();
        Interface current = null;
        int declaredEndpoints = 0;
        boolean acmUnion = false;
        boolean networkUnion = false;
        boolean acmAssociation = false;
        boolean networkAssociation = false;
        boolean ncmFunctional = false;
        for (int offset = 27; offset < raw.length;) {
            if (offset + 2 > raw.length) { return false; }
            int length = u(raw, offset);
            int type = u(raw, offset + 1);
            if (length < 2 || length > raw.length - offset) { return false; }
            if (type == 4) {
                if (length != 9 || (current != null && current.endpoints.size() != declaredEndpoints)) {
                    return false;
                }
                current = new Interface(u(raw, offset + 2), u(raw, offset + 3), u(raw, offset + 5),
                        u(raw, offset + 6), u(raw, offset + 7), new ArrayList<>());
                declaredEndpoints = u(raw, offset + 4);
                interfaces.add(current);
                if (interfaces.size() > (composite ? 5 : 2)) { return false; }
            } else if (type == 5) {
                if (length != 7 || current == null || current.endpoints.size() >= declaredEndpoints
                        || (u(raw, offset + 3) & 0xfc) != 0 || word(raw, offset + 4) == 0) { return false; }
                current.endpoints.add(new Endpoint(u(raw, offset + 2), u(raw, offset + 3)));
            } else if (type == 0x24) {
                if (length < 3 || current == null || current.deviceClass != 2 || current.alternate != 0) {
                    return false;
                }
                if (u(raw, offset + 2) == 6) {
                    if (length != 5 || u(raw, offset + 3) != current.id) { return false; }
                    if (current.id == controlId && u(raw, offset + 4) == dataId && !acmUnion) {
                        acmUnion = true;
                    } else if (composite && current.id == 0 && u(raw, offset + 4) == 1 && !networkUnion) {
                        networkUnion = true;
                    } else { return false; }
                } else if (u(raw, offset + 2) == 0x1a) {
                    // Linux f_ncm: NCM 1.00 with packet-filter and CRC-mode capabilities.
                    if (networkSubclass != 13 || current.id != 0 || length != 6 || ncmFunctional
                            || word(raw, offset + 3) != 0x0100 || u(raw, offset + 5) != 0x11) { return false; }
                    ncmFunctional = true;
                }
            } else if (type == 11) {
                if (length != 8 || u(raw, offset + 3) != 2 || u(raw, offset + 4) != 2) { return false; }
                if (u(raw, offset + 2) == controlId && u(raw, offset + 5) == 2
                        && u(raw, offset + 6) == 1 && !acmAssociation) { acmAssociation = true; }
                else if (composite && u(raw, offset + 2) == 0 && u(raw, offset + 5) == networkSubclass
                        && u(raw, offset + 6) == 0 && !networkAssociation) { networkAssociation = true; }
                else { return false; }
            } else { return false; }
            offset += length;
        }
        if (current == null || current.endpoints.size() != declaredEndpoints || !acmUnion || !acmAssociation
                || (composite && (!networkUnion || !networkAssociation))
                || (networkSubclass == 13 && !ncmFunctional)) { return false; }
        CdcAcmProfile parsed = find(word(raw, 8), word(raw, 10), u(raw, 4), u(raw, 5), u(raw, 6),
                u(raw, 17), interfaces);
        return parsed != null && parsed.networkSubclass == networkSubclass && parsed.controlId == controlId
                && parsed.dataId == dataId && parsed.inputAddress == inputAddress
                && parsed.outputAddress == outputAddress;
    }

    private static int u(byte[] bytes, int offset) { return bytes[offset] & 255; }
    private static int word(byte[] bytes, int offset) { return u(bytes, offset) | (u(bytes, offset + 1) << 8); }
    private static Interface get(List<Interface> interfaces, int id, int alternate) {
        for (Interface item : interfaces) {
            if (item.id == id && item.alternate == alternate) { return item; }
        }
        return null;
    }
    private static boolean matches(Interface item, int deviceClass, int subclass, int protocol) {
        return item != null && item.deviceClass == deviceClass && item.subclass == subclass && item.protocol == protocol;
    }
    private static boolean notification(Interface item) {
        return item.endpoints.size() == 1 && item.endpoints.get(0).type == 3
                && (item.endpoints.get(0).address & 128) != 0;
    }
    private static boolean bulkPair(Interface item) {
        return item.endpoints.size() == 2 && item.endpoints.get(0).type == 2 && item.endpoints.get(1).type == 2
                && ((item.endpoints.get(0).address ^ item.endpoints.get(1).address) & 128) != 0;
    }
    private static boolean validEndpoint(Endpoint endpoint) {
        return endpoint != null && endpoint.address >= 1 && endpoint.address <= 255
                && (endpoint.address & 0x70) == 0 && (endpoint.address & 15) != 0;
    }
}
