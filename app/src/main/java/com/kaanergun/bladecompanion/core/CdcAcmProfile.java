package com.kaanergun.bladecompanion.core;

import java.util.List;

/** Matches only the two-interface local diagnostic gadget, not arbitrary serial devices. */
public final class CdcAcmProfile {
    public static final int VENDOR = 0x0525;
    public static final int PRODUCT = 0xa4a7;
    public final int controlId;
    public final int dataId;
    public final int inputAddress;
    public final int outputAddress;

    private CdcAcmProfile(int controlId, int dataId, int inputAddress, int outputAddress) {
        this.controlId = controlId;
        this.dataId = dataId;
        this.inputAddress = inputAddress;
        this.outputAddress = outputAddress;
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
        public final int deviceClass;
        public final int subclass;
        public final List<Endpoint> endpoints;

        public Interface(int id, int deviceClass, int subclass, List<Endpoint> endpoints) {
            this.id = id;
            this.deviceClass = deviceClass;
            this.subclass = subclass;
            this.endpoints = endpoints;
        }
    }

    public static CdcAcmProfile find(int vendor, int product, List<Interface> interfaces) {
        if (vendor != VENDOR || product != PRODUCT || interfaces == null || interfaces.size() != 2) {
            return null;
        }
        Interface control = null;
        Interface data = null;
        for (Interface item : interfaces) {
            if (item == null || item.id < 0 || item.id > 255 || item.endpoints == null) {
                return null;
            }
            if (item.deviceClass == 2 && item.subclass == 2 && control == null) {
                control = item;
            } else if (item.deviceClass == 10 && data == null) {
                data = item;
            } else {
                return null;
            }
        }
        if (control == null || data == null || control.id == data.id
                || control.endpoints.size() != 1 || data.endpoints.size() != 2) {
            return null;
        }
        Endpoint notification = control.endpoints.get(0);
        if (!validEndpoint(notification) || notification.type != 3 || (notification.address & 128) == 0) {
            return null;
        }
        int input = -1;
        int output = -1;
        for (Endpoint endpoint : data.endpoints) {
            if (!validEndpoint(endpoint) || endpoint.type != 2) {
                return null;
            }
            if ((endpoint.address & 128) != 0 && input == -1) {
                input = endpoint.address;
            } else if ((endpoint.address & 128) == 0 && output == -1) {
                output = endpoint.address;
            } else {
                return null;
            }
        }
        return input == -1 || output == -1 || input == notification.address ? null
                : new CdcAcmProfile(control.id, data.id, input, output);
    }

    private static boolean validEndpoint(Endpoint endpoint) {
        return endpoint != null && endpoint.address >= 1 && endpoint.address <= 255
                && (endpoint.address & 0x70) == 0 && (endpoint.address & 15) != 0;
    }
}
