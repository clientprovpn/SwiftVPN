package ir.swiftvpn.engine

import android.content.Context
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WireGuard tunnel driver.
 *
 * Wraps [GoBackend] and adapts it to the same shape [VpnEngine] already exposes
 * for OpenVPN. Two mismatches have to be absorbed here, and they are the whole
 * reason this class exists rather than the router calling GoBackend directly:
 *
 * **1. Statistics are POLLED, not pushed.** OpenVPN hands us per-interval deltas
 * through `VpnStatus.ByteCountListener`. WireGuard offers only
 * `Backend.getStatistics(tunnel)`, returning cumulative totals whenever asked.
 * So this class runs a one-second ticker, diffs successive samples itself, and
 * emits the same `(bytesIn, bytesOut, diffIn, diffOut)` tuple the graphs
 * already consume. Downstream code cannot tell the two protocols apart.
 *
 * **2. State is pushed, but through an object we own.** `Tunnel.onStateChange`
 * is a callback on the Tunnel instance, so the tunnel object is the listener.
 *
 * Threading: every Backend call blocks. `setState` in particular does DNS
 * resolution with up to ten one-second retries, so calling it on the main
 * thread is an ANR. Everything here is therefore suspending or dispatched.
 */
class WireGuardEngine(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Created lazily and only ever once.
     *
     * The GoBackend constructor loads libwg-go.so, which is wasted work in a
     * session where the user only touches OpenVPN profiles.
     */
    private val backend: Backend by lazy { GoBackend(appContext) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var pollJob: Job? = null

    /** The tunnel currently handed to the backend, if any. */
    private var active: WgTunnel? = null

    // Remembered so the stall watchdog can re-run connect() on its own when a
    // "connected" tunnel stops moving traffic (5G CGNAT kills the UDP mapping).
    private var lastUuid: String? = null
    private var lastStore: WireGuardStore? = null
    private var stallRestarts = 0

    /**
     * Callbacks into the router. Assigned once by [VpnEngine] at init; kept as
     * plain properties rather than constructor args so the router can construct
     * this lazily without a circular dependency.
     */
    var onState: ((VpnState, String?) -> Unit)? = null
    var onBytes: ((bytesIn: Long, bytesOut: Long, diffIn: Long, diffOut: Long) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var onTunnelInfo: ((TunnelInfo) -> Unit)? = null

    /**
     * A tunnel is identified to the backend by NAME, and wireguard-go inherits
     * Linux interface-name limits: max 15 chars, `[a-zA-Z0-9_=+.-]` only.
     *
     * Our profile ids are UUIDs (36 chars, with hyphens), so they cannot be used
     * directly. A fixed valid name is used instead — this app runs at most one
     * tunnel at a time anyway, which Android enforces regardless.
     */
    private inner class WgTunnel(val uuid: String) : Tunnel {
        override fun getName() = TUNNEL_NAME

        override fun onStateChange(newState: Tunnel.State) {
            val mapped = when (newState) {
                Tunnel.State.UP -> VpnState.CONNECTED
                Tunnel.State.DOWN -> VpnState.DISCONNECTED
                // TOGGLE is a request the caller passes IN, never a state the
                // backend reports out. Returning here makes it a true no-op:
                // mapping it to UNKNOWN would look defensive while actually
                // tearing down a live session, because UNKNOWN is not "active".
                Tunnel.State.TOGGLE -> return
            }
            onLog?.invoke("WireGuard tunnel is now $newState")
            onState?.invoke(mapped, null)

            if (mapped == VpnState.CONNECTED) startPolling() else stopPolling()
        }
    }

    // ------------------------------------------------------------------ control

    /**
     * Brings the tunnel up. Blocking — call from an IO context.
     *
     * Returns an error message on failure, or null on success. The message is
     * shown to the user, so [BackendException.Reason] is translated into
     * something actionable rather than leaking an enum name.
     */
    suspend fun connect(uuid: String, store: WireGuardStore): String? =
        withContext(Dispatchers.IO) {
            val raw = store.rawConfig(uuid)
                ?: return@withContext "This WireGuard profile could not be read"
            lastUuid = uuid
            lastStore = store
            stallRestarts = 0
            val configText = addKeepalive(clampMtu(raw))
            val config = runCatching { com.wireguard.config.Config.parse(configText.byteInputStream()) }
                .getOrNull()
                ?: return@withContext "This WireGuard profile could not be read"

            // Android permits one VPN at a time; tear our own tunnel down first
            // rather than letting the backend fail mid-handshake. Already on IO.
            runCatching { disconnectBlocking() }

            val tunnel = WgTunnel(uuid)
            active = tunnel

            onState?.invoke(VpnState.CONNECTING, null)
            onLog?.invoke("Starting WireGuard tunnel")

            try {
                backend.setState(tunnel, Tunnel.State.UP, config)
                publishTunnelInfo(config)
                onLog?.invoke("WireGuard: userspace backend ${runCatching { backend.version }.getOrDefault("?")}")
                null
            } catch (e: BackendException) {
                active = null
                onState?.invoke(reasonToState(e), describe(e))
                onLog?.invoke("WireGuard failed: ${describe(e)}")
                describe(e)
            } catch (e: Exception) {
                active = null
                Log.w(TAG, "connect failed", e)
                onState?.invoke(VpnState.DISCONNECTED, e.localizedMessage)
                onLog?.invoke("WireGuard failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
                e.localizedMessage ?: "Could not start the WireGuard tunnel"
            }
        }

    /**
     * Brings the tunnel down WITHOUT blocking the caller.
     *
     * This is the entry point for UI and tile taps, which arrive on the main
     * thread. `Backend.setState(DOWN)` talks to wireguard-go over its IPC
     * socket and can take a moment, so blocking here would risk an ANR — the
     * same reason the OpenVPN path hands its stop to a background thread.
     *
     * The state flow is updated optimistically so the button moves on the tap;
     * `Tunnel.onStateChange` confirms it a moment later.
     */
    fun disconnectAsync() {
        stopPolling()
        onState?.invoke(VpnState.DISCONNECTED, null)
        val tunnel = active ?: return
        active = null
        scope.launch {
            runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                .onFailure { Log.w(TAG, "disconnect failed", it) }
        }
    }

    /**
     * Brings the tunnel down and WAITS for it.
     *
     * Used only when another engine is about to claim the single VPN slot, where
     * proceeding before the tunnel is actually down would have Android revoke it
     * from under the new connection. Callers must already be off the main thread.
     */
    fun disconnectBlocking() {
        stopPolling()
        val tunnel = active ?: return
        active = null
        runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            .onFailure { Log.w(TAG, "disconnect failed", it) }
        onState?.invoke(VpnState.DISCONNECTED, null)
    }

    val activeUuid: String?
        get() = active?.uuid

    // --------------------------------------------------------------- polling

    /**
     * Samples cumulative counters once a second and emits deltas.
     *
     * Two subtleties:
     *  * `totalRx`/`totalTx` only ever grow while a tunnel lives, but a
     *    reconnect resets them, which would otherwise produce a huge negative
     *    delta — hence `coerceAtLeast(0)`.
     *  * `getStatistics` blocks on the wireguard-go IPC socket, so this must not
     *    run on the main thread. The scope is IO.
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return

        // Bind the loop to THIS tunnel and give it its own baseline. Holding the
        // reference locally means a loop left over from a previous session can
        // never sample the current one, and a per-session baseline means the
        // first diff is measured against this tunnel's own starting counters
        // rather than a figure inherited from the last connection.
        val tunnel = active ?: return
        var baseRx = -1L
        var baseTx = -1L
        var silentSeconds = 0

        pollJob = scope.launch {
            while (isActive && active === tunnel) {
                val stats = runCatching { backend.getStatistics(tunnel) }.getOrNull()
                if (stats != null) {
                    val rx = stats.totalRx()
                    val tx = stats.totalTx()

                    // Stall watchdog. The dead-path signature (5G CGNAT killed
                    // the UDP mapping) is: apps keep SENDING (tx grows) while
                    // NOTHING comes back (rx frozen) for 16s. A frozen tx+rx
                    // pair is NOT a stall — wireguard-go's byte counters only
                    // count payload bytes, so keepalives move them by 0 and a
                    // healthy idle tunnel looks exactly like that; restarting
                    // on it would tear down Telegram-style long connections
                    // every 16s for no reason. Capped at 3 automatic restarts
                    // so a dead server can't loop forever.
                    if (baseRx >= 0 && rx == baseRx && tx > baseTx) {
                        silentSeconds++
                        if (silentSeconds == 8) {  // 8 polls × 2s = 16s
                            silentSeconds = 0
                            val id = lastUuid
                            val st = lastStore
                            if (stallRestarts < 3 && id != null && st != null) {
                                stallRestarts++
                                onLog?.invoke(
                                    "WireGuard: sending but no reply for 16s — restarting tunnel automatically ($stallRestarts/3)",
                                )
                                val err = connect(id, st)
                                if (err != null) {
                                    onLog?.invoke("WireGuard auto-restart failed: $err")
                                }
                            } else if (stallRestarts >= 3) {
                                onLog?.invoke(
                                    "WireGuard: tunnel kept stalling after 3 restarts — please reconnect",
                                )
                            } else {
                                onLog?.invoke("WireGuard: no reply for 16s — tunnel stalled")
                            }
                        }
                    } else {
                        silentSeconds = 0
                        stallRestarts = 0
                    }

                    if (baseRx < 0) {
                        // First sample establishes the baseline and reports no
                        // traffic, rather than dumping the whole cumulative
                        // total into one second of the graph.
                        baseRx = rx
                        baseTx = tx
                        onBytes?.invoke(rx, tx, 0, 0)
                    } else {
                        onBytes?.invoke(
                            rx,
                            tx,
                            (rx - baseRx).coerceAtLeast(0),
                            (tx - baseTx).coerceAtLeast(0),
                        )
                        baseRx = rx
                        baseTx = tx
                    }
                }

                // Watch for a tunnel that died without telling us.
                //
                // WireGuard is connectionless: wireguard-go holds no session to
                // lose, so on network loss it simply goes quiet and may never
                // deliver onStateChange(DOWN). Without this check the UI and the
                // notification would sit on "Connected" with a frozen
                // speedometer indefinitely.
                //
                // The poll is already here every second and already holds the
                // tunnel, so asking the backend for its state is nearly free and
                // needs no extra timer.
                val backendState =
                    runCatching { backend.getState(tunnel) }.getOrNull()
                if (backendState != null && backendState != Tunnel.State.UP) {
                    onLog?.invoke("WireGuard tunnel went down")
                    active = null
                    onState?.invoke(VpnState.DISCONNECTED, null)
                    break
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ---------------------------------------------------------------- details

    /**
     * Publishes what the config declares.
     *
     * Unlike OpenVPN — where the negotiated tunnel settings have to be scraped
     * out of log lines — a WireGuard config IS the final answer: there is no
     * server push, so addresses, DNS, MTU and routes are all known up front.
     */
    private fun publishTunnelInfo(config: com.wireguard.config.Config) {
        val iface = config.getInterface()

        val v4 = iface.addresses.firstOrNull { it.address is java.net.Inet4Address }
        val v6 = iface.addresses.firstOrNull { it.address is java.net.Inet6Address }

        val endpoint = config.peers.firstNotNullOfOrNull { it.endpoint.orElse(null) }

        onTunnelInfo?.invoke(
            TunnelInfo(
                localIPv4 = v4?.toString(),
                localIPv6 = v6?.toString(),
                remoteServer = endpoint?.let { "${it.host}:${it.port}" },
                mtu = iface.mtu.orElse(null),
                dnsServers = iface.dnsServers.map { it.hostAddress ?: it.toString() },
                routes = config.peers.flatMap { peer ->
                    peer.allowedIps.map { it.toString() }
                },
            )
        )
    }

    // ----------------------------------------------------------------- errors

    private fun reasonToState(e: BackendException): VpnState = when (e.reason) {
        // Not an auth failure in the OpenVPN sense, but it is the state that
        // means "the user must intervene", which is how the UI reads it.
        BackendException.Reason.VPN_NOT_AUTHORIZED -> VpnState.AUTH_FAILED
        BackendException.Reason.DNS_RESOLUTION_FAILURE -> VpnState.NO_NETWORK
        else -> VpnState.DISCONNECTED
    }

    private fun describe(e: BackendException): String = when (e.reason) {
        BackendException.Reason.VPN_NOT_AUTHORIZED ->
            "VPN permission was not granted"
        BackendException.Reason.DNS_RESOLUTION_FAILURE ->
            "Could not resolve the server address"
        BackendException.Reason.TUN_CREATION_ERROR ->
            "Android refused to create the tunnel interface"
        BackendException.Reason.UNABLE_TO_START_VPN ->
            "The VPN service could not be started"
        BackendException.Reason.TUNNEL_MISSING_CONFIG ->
            "This profile has no usable configuration"
        BackendException.Reason.GO_ACTIVATION_ERROR_CODE ->
            "The WireGuard backend rejected this configuration"
        else -> e.reason.name.lowercase().replace('_', ' ')
    }

    /**
     * Caps the interface MTU at [SAFE_MTU] for the live session.
     *
     * The stored file is never touched — this only rewrites the text that is
     * parsed for THIS connection, so the user's config stays as imported.
     *
     * Why: the library defaults to 1280 when the config says nothing, but many
     * provider configs ship an explicit `MTU = 1420`. On cellular/CGNAT paths
     * where PMTUD is broken, 1420-byte inner packets produce oversized outer
     * UDP datagrams that get fragmented and dropped: small requests (DNS,
     * thumbnails) succeed while video streams stall at 0 bytes — exactly the
     * symptom this guards against. 1280 is the largest value that survives
     * every IPv6 path, so it cannot be wrong, only occasionally suboptimal.
     */
    private fun clampMtu(text: String): String {
        val lines = text.lines()
        var inInterface = false
        var sawMtu = false
        var changed = false
        val out = ArrayList<String>(lines.size + 1)

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("[") && trimmed.endsWith("]") -> {
                    if (inInterface && !sawMtu) {
                        out += "MTU = $SAFE_MTU"
                        changed = true
                    }
                    inInterface = trimmed.equals("[Interface]", ignoreCase = true)
                    sawMtu = false
                    out += line
                }
                inInterface && trimmed.startsWith("mtu", ignoreCase = true) &&
                    trimmed.contains('=') -> {
                    sawMtu = true
                    val value = trimmed.substringAfter('=').trim().toIntOrNull()
                    if (value != null && value > SAFE_MTU) {
                        out += "MTU = $SAFE_MTU"
                        changed = true
                        onLog?.invoke("WireGuard: MTU $value lowered to $SAFE_MTU for this session")
                    } else {
                        out += line
                    }
                }
                else -> out += line
            }
        }
        if (inInterface && !sawMtu) {
            out += "MTU = $SAFE_MTU"
            changed = true
        }
        if (changed) onLog?.invoke("WireGuard: MTU set to $SAFE_MTU")
        return if (changed) out.joinToString("\n") else text
    }

    /**
     * Adds `PersistentKeepalive = 10` to every [Peer] that lacks one.
     *
     * WireGuard is silent UDP: on CGNAT/mobile paths the NAT mapping for the
     * tunnel's 5-tuple expires after roughly 30–60s of low activity, and inbound
     * packets then die at the carrier — the tunnel shows "connected" while
     * nothing flows. A 10-second keepalive ping holds the mapping open — the
     * stored config is never touched; this edits only the session text, exactly
     * like [clampMtu].
     */
    private fun addKeepalive(text: String): String {
        val lines = text.lines()
        val out = ArrayList<String>(lines.size + 4)
        var inPeer = false
        var sawKeepalive = false
        var changed = false

        fun flushPeer() {
            if (inPeer && !sawKeepalive) {
                out += "PersistentKeepalive = 10"
                changed = true
            }
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                flushPeer()
                inPeer = trimmed.equals("[Peer]", ignoreCase = true)
                sawKeepalive = false
                out += line
                continue
            }
            if (inPeer && trimmed.startsWith("persistentkeepalive", ignoreCase = true)) {
                sawKeepalive = true
            }
            out += line
        }
        flushPeer()
        if (changed) onLog?.invoke("WireGuard: PersistentKeepalive 10s added for this session")
        return if (changed) out.joinToString("\n") else text
    }

    // No shutdown() here on purpose: VpnEngine is an object, so this driver
    // lives as long as the process and there is no owner to call one. Every stop
    // path already cancels pollJob, which is the only thing that would otherwise
    // keep running.

    private companion object {
        const val TAG = "WireGuardEngine"
        const val POLL_INTERVAL_MS = 2_000L

        /** IPv6-safe inner MTU; see [clampMtu]. */
        const val SAFE_MTU = 1280

        /** Must satisfy Tunnel.NAME_PATTERN: max 15 chars, restricted charset. */
        const val TUNNEL_NAME = "swiftvpn"
    }
}
