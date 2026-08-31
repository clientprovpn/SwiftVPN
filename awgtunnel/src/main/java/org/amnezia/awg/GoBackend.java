package org.amnezia.awg;

import androidx.annotation.Nullable;

public class GoBackend {
    @Nullable
    public static native String awgGetConfig(int handle);

    public static native int awgGetSocketV4(int handle);

    /**
     * SwiftVPN: the tun batch size the device was started with. 1 means the
     * legacy single-packet path (no kernel GRO/GSO offload); anything larger
     * means the kernel advertised IFF_VNET_HDR and batching is live.
     */
    public static native int awgGetTunBatchSize(int handle);

    public static native int awgGetSocketV6(int handle);

    public static native void awgTurnOff(int handle);

    public static native int awgTurnOn(String ifName, int tunFd, String settings);

    public static native String awgVersion();
}
