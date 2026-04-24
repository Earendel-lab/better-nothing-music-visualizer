package com.better.nothing.music.vizualizer;

import com.nothing.ketchum.Common;

/**
 * Device detection and display helpers used by the visualizer runtime.
 * Legacy hardcoded frequency maps live in zones.config now and no longer belong here.
 */
public final class DeviceProfile {

    public static final int DEVICE_UNKNOWN = 0;
    public static final int DEVICE_NP1 = 1;
    public static final int DEVICE_NP2 = 2;
    public static final int DEVICE_NP2A = 3;
    public static final int DEVICE_NP3A = 4;
    public static final int DEVICE_NP4A = 5;

    private DeviceProfile() {
    }

    public static int detectDevice() {
        if (Common.is20111()) {
            return DEVICE_NP1;
        }
        if (Common.is22111()) {
            return DEVICE_NP2;
        }
        if (Common.is23111() || Common.is23113()) {
            return DEVICE_NP2A;
        }
        if (Common.is24111()) {
            return DEVICE_NP3A;
        }
        if (Common.is25111()) {
            return DEVICE_NP4A;
        }
        return DEVICE_UNKNOWN;
    }

    public static String deviceName(int device) {
        return switch (device) {
            case DEVICE_NP1 -> "Phone (1)";
            case DEVICE_NP2 -> "Phone (2)";
            case DEVICE_NP2A -> "Phone (2a) / 2a+";
            case DEVICE_NP3A -> "Phone (3a) / 3a Pro";
            case DEVICE_NP4A -> "Phone (4a)";
            default -> "Unknown";
        };
    }
}
