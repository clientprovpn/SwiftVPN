package ir.swiftvpn.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import ir.swiftvpn.xray.XrayVpnService

/**
 * Driver for Xray, adapting it to the same shape [VpnEngine] uses for the other
 * two engines.
 *
 * Unlike OpenVPN (separate process, AIDL) and WireGuard (library-owned
 * VpnService), Xray needs a VpnService we write ourselves — [XrayVpnService] —
 * because it is a PROXY, not a TUN VPN: we must create the TUN, exclude our own
 * package so Xray's uplink doesn't loop, and hand the fd to xray-core's gvisor
 * stack. That service owns the fd and the CoreController for their whole
 * lifetime, so it does the work and reports back here through the report*
 * methods; this object just forwards to the router's callbacks and starts/stops
 * the service.
 *
 * An `object`, not a class: there is only ever one Xray tunnel, and the
 * system-instantiated service needs a stable, dependency-free way to reach it.
 */
object XrayEngine {

    /** Wired once by [VpnEngine.init]; identical contract to WireGuardEngine. */
    var onState: ((VpnState, String?) -> Unit)? = null
    var onBytes: ((bytesIn: Long, bytesOut: Long, diffIn: Long, diffOut: Long) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var onTunnelInfo: ((TunnelInfo) -> Unit)? = null

    @Volatile
    var activeUuid: String? = null
        private set

    // ------------------------------------------------------------------ control

    /**
     * Starts [uuid]. The heavy lifting (TUN, config, core) happens in the
     * service; here we only record intent and launch it. Optimistic CONNECTING
     * is published so the row moves on the tap, exactly as the other engines do.
     */
    fun connect(context: Context, uuid: String) {
        activeUuid = uuid
        appContext = context.applicationContext
        onState?.invoke(VpnState.CONNECTING, null)
        XrayVpnService.start(context, uuid, XrayRemote.events)
    }

    /** Asks the service to stop; it reports DISCONNECTED from onDestroy. */
    fun disconnect(context: Context) {
        XrayVpnService.stop(context)
    }

    /** True when the Xray service currently holds (or is bringing up) a tunnel. */
    val isRunning: Boolean
        get() = activeUuid != null

    // ------------------------------------------------- build config from a link

    /**
     * The Xray JSON for [uuid], or null when the stored link cannot be parsed.
     * Called by the service once it has established the TUN.
     */
    fun buildConfig(context: Context, uuid: String): String? {
        val outbound = XrayStore(context).outbound(uuid) ?: return null
        return ir.swiftvpn.engine.xray.XrayConfig.build(outbound)
    }

    // ------------------------------------------------ callbacks from the service

    fun reportConnected() {
        onState?.invoke(VpnState.CONNECTED, null)
    }

    fun reportError(message: String) {
        activeUuid = null
        // Surface the reason, then settle on DISCONNECTED so nothing sticks.
        // Going straight to a terminal error state would leave the row and the
        // tile frozen, because Xray — unlike OpenVPN — emits no follow-up
        // "not connected" of its own. The reason lives on in the log tab.
        onLog?.invoke("Xray failed: $message")
        onState?.invoke(VpnState.DISCONNECTED, message)
    }

    fun reportStopped() {
        activeUuid = null
        onState?.invoke(VpnState.DISCONNECTED, null)
    }

    fun reportBytes(bytesIn: Long, bytesOut: Long, diffIn: Long, diffOut: Long) {
        onBytes?.invoke(bytesIn, bytesOut, diffIn, diffOut)
    }

    fun reportLog(message: String) {
        onLog?.invoke(message)
    }

    fun reportTunnelInfo(info: TunnelInfo) {
        onTunnelInfo?.invoke(info)
    }

    // ---------------------------------------------------------------- internal

    /**
     * Tears the tunnel down SYNCHRONOUSLY, used by [VpnEngine] when another
     * engine is taking over the single VPN slot. Blocking (releases the fd
     * before returning) so the incoming engine does not race Android's revoke;
     * the router calls this on an IO thread. No-op when nothing is running.
     *
     * Since the service now lives in :xray (see [XrayIpc]), the stop goes over
     * the control binder with an acknowledgement instead of reaching a static
     * instance field.
     */
    internal fun forceStop() {
        if (activeUuid == null) return
        activeUuid = null
        val ctx = appContext
        if (ctx != null) {
            XrayRemote.forceStopBlocking(ctx)
        } else {
            // No context recorded yet (paranoia path): async intent fallback.
            XrayVpnService.stopBlocking()
        }
    }

    /** Application context remembered at connect time, for the IPC force-stop. */
    @Volatile
    private var appContext: Context? = null
}
