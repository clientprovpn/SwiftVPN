package ir.swiftvpn.engine

/**
 * The IPC contract between the UI process and the :xray process.
 *
 * WHY this exists — the root cause of the "Xray always crashes" bug:
 *
 * Both WireGuard (libwg-go.so) and Xray (libgojni.so) are gomobile products,
 * and each carries a FULL Go runtime. A Go runtime never unloads and uses
 * signals (SIGURG async preemption) internally. Two Go runtimes living in one
 * process corrupt each other's signal handling, and the process dies with a
 * native SIGSEGV — exactly what the captured tombstones showed (crash inside
 * libwg-go.so while libgojni.so threads were parked, and vice versa). Branch A
 * was stable precisely because it only ever loaded ONE Go runtime.
 *
 * The fix is process isolation, mirroring how OpenVPN already lives in
 * :openvpn: everything that touches libgojni (the tunnel service AND the
 * server tester) runs in :xray, while libwg-go stays alone in the UI process.
 * One Go runtime per process, no signal warfare.
 *
 * Communication between the two processes is Messenger-based:
 *
 *   UI -> :xray   requests (connect via startIntent + EXTRA_MESSENGER,
 *                 tests via XrayTestService binder, force-stop via control
 *                 binder on XrayVpnService)
 *   :xray -> UI   events (EV_*) delivered to the Messenger the UI hands over.
 */
object XrayIpc {

    /** Extra carrying the UI's event Messenger in the START intent. */
    const val EXTRA_MESSENGER = "ir.swiftvpn.xray.messenger"

    /** Bind action for the control channel (force-stop) on XrayVpnService. */
    const val ACTION_CONTROL = "ir.swiftvpn.xray.CONTROL"

    // ------------------------------------------------ events, :xray -> UI

    const val EV_CONNECTED = 1
    const val EV_ERROR = 2
    const val EV_STOPPED = 3
    const val EV_BYTES = 4
    const val EV_LOG = 5
    const val EV_TUNNEL_INFO = 6
    const val EV_FORCE_STOPPED = 7 // ack for REQ_FORCE_STOP

    // ------------------------------------------------ requests, UI -> :xray

    const val REQ_FORCE_STOP = 10
    const val REQ_LATENCY = 11
    const val REQ_PROBE = 12
    const val REQ_SPEED = 13

    // ------------------------------------------------ replies, :xray -> UI

    const val RES_LATENCY = 21
    const val RES_PROBE = 22
    const val RES_SPEED = 23

    // ------------------------------------------------ bundle keys

    const val K_UUID = "uuid"
    const val K_TEXT = "text"
    const val K_MS = "ms"
    const val K_ERROR = "error"
    const val K_COUNTRY = "country"
    const val K_IP = "ip"
    const val K_BYTES_PER_SEC = "bps"
    const val K_TOTAL_IN = "totalIn"
    const val K_TOTAL_OUT = "totalOut"
    const val K_DIFF_IN = "diffIn"
    const val K_DIFF_OUT = "diffOut"
    const val K_REMOTE = "remote"
    const val K_PROXY = "proxy"
    const val K_DNS = "dns"
    const val K_ROUTES = "routes"
    const val K_MTU = "mtu"

    /**
     * True when this process IS the :xray process. Used by XrayTester to decide
     * between doing Go work locally (in :xray) and forwarding it over IPC
     * (anywhere else). /proc/self/cmdline holds "ir.swiftvpn:xray" there — no
     * API-level dependency, unlike Context.getProcessName (API 28+).
     */
    fun inXrayProcess(): Boolean = runCatching {
        java.io.File("/proc/self/cmdline").readText()
            .trim('\u0000', ' ', '\n')
            .endsWith(":xray")
    }.getOrDefault(false)
}
