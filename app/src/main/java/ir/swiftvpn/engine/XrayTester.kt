package ir.swiftvpn.engine

import android.content.Context
import android.util.Log
import ir.swiftvpn.engine.xray.XrayConfig
import ir.swiftvpn.engine.xray.XrayOutbound
import ir.swiftvpn.engine.xray.XrayProbeConfig
import ir.swiftvpn.engine.xray.XrayShareLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * Probes a server: latency, egress country/IP, and download throughput.
 *
 * Mirrors what v2rayNG shows — a per-row `728ms` badge and a result line like
 * `Success: Connection took 153ms (DE) 89.58.40.177`.
 *
 * Three separate capabilities, because they cost very different amounts:
 *
 *  * [latency] — cheap and safe to run across every profile at once. For Xray it
 *    uses the wrapper's own `measureOutboundDelay`, which spins a private
 *    instance and dials through the proxy without touching the TUN. For OpenVPN
 *    and WireGuard there is no such API, so it times a plain TCP connect to
 *    `server:port` — honest, but note it measures reaching the server, not a
 *    working tunnel.
 *  * [probe] — starts a temporary SOCKS-only Xray instance and asks an ip-info
 *    service who it thinks we are. This is the only way to learn the REAL egress
 *    country: see [XrayProbeConfig] for why an ordinary HTTP call cannot work.
 *  * [downloadSpeed] — same temporary instance, but pulls a fixed-size payload
 *    and times it. Deliberately separate and never run in bulk: it costs real
 *    data and takes seconds per server.
 *
 * Everything here blocks; every entry point is a suspend on [Dispatchers.IO].
 */
object XrayTester {

    private const val TAG = "XrayTester"

    /** Small, cheap, CORS-free endpoint that echoes the caller's IP + country. */
    private const val IP_INFO_URL = "http://ip-api.com/json/?fields=status,countryCode,query"

    /** Payload for the throughput test. 10 MB is enough to be meaningful. */
    private const val SPEED_URL = "https://speed.cloudflare.com/__down?bytes=10000000"

    private const val LATENCY_URL = "https://www.gstatic.com/generate_204"

    // ------------------------------------------------------------------ latency

    /**
     * Round-trip latency in ms, or null when the server is unreachable.
     *
     * Does NOT require connecting, and does not disturb a live tunnel.
     */
    suspend fun latency(
        context: Context,
        profile: Profile,
        store: XrayStore?,
    ): Long? = withContext(Dispatchers.IO) {
        when (profile.protocol) {
            Protocol.XRAY -> {
                // Outside the :xray process this must be forwarded over IPC:
                // starting a core here would load libgojni next to libwg-go,
                // and two Go runtimes in one process is the crash this whole
                // split exists to prevent (see XrayIpc).
                if (!XrayIpc.inXrayProcess()) {
                    return@withContext XrayRemote.latency(context, profile.uuid)
                }
                val raw = store?.link(profile.uuid) ?: run {
                    DiagnosticLog.write(DiagnosticLog.TEST, "xray latency: outbound unreadable for ${profile.name}")
                    return@withContext null
                }
                // MUST come before any Go call, or the core dies in native code.
                if (!XrayRuntime.ensure(context)) {
                    DiagnosticLog.write(DiagnosticLog.TEST, "xray latency: runtime init failed for ${profile.name}")
                    return@withContext null
                }

                // Serialised with every other core start, and spaced out after —
                // see xrayLatency for why the spacing is not optional.
                mutex.withLock {
                    // Breadcrumb: a native fault here kills the process without
                    // any Java exception, so this file is the only evidence left.
                    CrashReporter.breadcrumb(context, "latency test ${profile.name}")
                    val ms = if (XrayConfig.isCustomJson(raw)) {
                        xrayLatency(XrayProbeConfig.buildOutboundOnlyCustom(raw), profile.name)
                    } else {
                        val outbound = XrayShareLink.parse(raw) ?: run {
                            DiagnosticLog.write(DiagnosticLog.TEST, "xray latency: link unparseable for ${profile.name}")
                            return@withLock null
                        }
                        xrayLatency(XrayProbeConfig.buildOutboundOnly(outbound), "${outbound.address}:${outbound.port}")
                    }
                    CrashReporter.breadcrumb(context, null)
                    delay(CORE_COOLDOWN_MS)
                    ms
                }
            }
            // Real per-protocol probes: IKE_SA_INIT for IKEv2, a genuine
            // Noise_IK handshake for WireGuard, and a (tls-auth/tls-crypt
            // aware) HARD_RESET for OpenVPN — see DelayProbe.
            Protocol.OPENVPN, Protocol.WIREGUARD, Protocol.IKEV2 ->
                DelayProbe.measure(context, profile)
        }
    }

    /**
     * One latency measurement. `measureOutboundDelay` builds a whole xray-core
     * instance internally, dials once, and closes it.
     *
     * THE CRASH THIS GUARDS AGAINST: "test all" calls this once per server. Left
     * unsynchronised and back to back, a sweep creates and destroys a Go core
     * every few hundred milliseconds, and the app died with a native fault inside
     * libgojni.so — no Java exception, so not even the in-app crash reporter saw
     * it; the process simply vanished. Holding [mutex] means only one core is ever
     * being built or torn down at a time, and [CORE_COOLDOWN_MS] gives the
     * previous instance's goroutines a moment to finish unwinding before the next
     * one starts.
     *
     * The cost is that a sweep of N servers takes N × (measurement + 300 ms).
     * That is a fair trade for not killing the app.
     */
    private fun xrayLatency(cfg: String?, label: String): Long? = runCatching {
        if (cfg == null) {
            DiagnosticLog.write(DiagnosticLog.TEST, "xray latency: no probe config for $label")
            return null
        }
        val ms = Libv2ray.measureOutboundDelay(cfg, LATENCY_URL)
        // The Go side returns -1 on failure rather than throwing.
        if (ms < 0) {
            DiagnosticLog.write(
                DiagnosticLog.TEST,
                "xray core reported -1 for $label",
            )
        }
        if (ms < 0) null else ms
    }.onFailure {
        Log.d(TAG, "xray latency failed: ${it.message}")
        DiagnosticLog.write(
            DiagnosticLog.TEST,
            "xray latency exception for $label: ${it.message}",
        )
    }.getOrNull()

    private fun tcpLatency(host: String, port: Int): Long? = runCatching {
        val start = System.nanoTime()
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), TCP_TIMEOUT_MS)
        }
        (System.nanoTime() - start) / 1_000_000
    }.onFailure { Log.d(TAG, "tcp latency failed: ${it.message}") }.getOrNull()

    // -------------------------------------------------------------------- probe

    /** What a full probe found. Any field may be null if that part failed. */
    data class ProbeResult(
        val latencyMs: Long? = null,
        val countryCode: String? = null,
        val egressIp: String? = null,
        val error: String? = null,
    ) {
        /**
         * v2rayNG-style summary, e.g.
         * `Success: Connection took 153ms (DE) 89.58.40.177`
         */
        fun summary(): String {
            if (error != null) return error
            val parts = buildList {
                latencyMs?.let { add("${it}ms") }
                countryCode?.let { add("($it)") }
                egressIp?.let { add(it) }
            }
            return if (parts.isEmpty()) "No response" else parts.joinToString(" ")
        }
    }

    /**
     * Full probe of one Xray profile: latency plus egress country and IP.
     *
     * Returns an error-bearing result rather than throwing, so a failing server
     * shows a reason on its row instead of vanishing.
     */
    suspend fun probe(context: Context, uuid: String, store: XrayStore): ProbeResult =
        withContext(Dispatchers.IO) {
            // Forwarded to :xray when called from any other process (XrayIpc).
            if (!XrayIpc.inXrayProcess()) {
                return@withContext XrayRemote.probe(context, uuid)
            }
            // Refuse while a tunnel is live — see startProbeInstance for why this
            // is a correctness guard, not caution.
            if (XrayEngine.isRunning || ir.swiftvpn.xray.XrayVpnService.isTunnelRunning) {
                return@withContext ProbeResult(error = ERR_BUSY)
            }

            val raw = store.link(uuid)
                ?: return@withContext ProbeResult(error = "Profile could not be read")
            val custom = XrayConfig.isCustomJson(raw)
            val outbound = if (custom) null else XrayShareLink.parse(raw)
            if (!custom && outbound == null) {
                return@withContext ProbeResult(error = "Profile could not be read")
            }

            if (!XrayRuntime.ensure(context)) {
                return@withContext ProbeResult(error = "Xray runtime unavailable")
            }

            mutex.withLock {
                val port = freePort() ?: return@withContext ProbeResult(
                    error = "No free local port for the test"
                )

                var controller: CoreController? = null
                try {
                    CrashReporter.breadcrumb(context, "probe ${outbound?.address ?: "custom"}")
                    controller = startProbeInstance(
                        if (custom) XrayProbeConfig.buildCustom(raw, port)
                        else outbound?.let { XrayProbeConfig.build(it, port) },
                        port,
                    )
                        ?: return@withContext ProbeResult(
                            error = "Test instance failed to start"
                        )

                    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))

                    val start = System.nanoTime()
                    // runInterruptible is what makes the timeout real: the fetch
                    // is a plain blocking socket read, and without it a
                    // withTimeoutOrNull could only set a flag the blocked thread
                    // never checks — a trickling server would pin this core well
                    // past the deadline.
                    val body = withTimeoutOrNull(HTTP_TIMEOUT_MS.toLong()) {
                        runInterruptible { fetchThroughProxy(IP_INFO_URL, proxy) }
                    }
                    val elapsed = (System.nanoTime() - start) / 1_000_000

                    if (body == null) {
                        return@withContext ProbeResult(
                            error = "No response through this server"
                        )
                    }

                    val json = runCatching { JSONObject(body) }.getOrNull()
                    ProbeResult(
                        latencyMs = elapsed,
                        countryCode = json?.optString("countryCode")?.takeIf { it.isNotBlank() },
                        egressIp = json?.optString("query")?.takeIf { it.isNotBlank() },
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "probe failed", e)
                    ProbeResult(error = e.localizedMessage ?: "Test failed")
                } finally {
                    runCatching { controller?.stopLoop() }
                    CrashReporter.breadcrumb(context, null)
                    // Same cooldown as the latency path: let the closed core
                    // unwind before anything starts another one.
                    runCatching { delay(CORE_COOLDOWN_MS) }
                }
            }
        }

    // ---------------------------------------------------------------- speed

    /**
     * Download throughput in bytes/sec through [uuid], or null on failure.
     *
     * Deliberately a separate call from [probe]: it moves ~10 MB, so it must be
     * an explicit user action on one server, never part of a bulk sweep.
     * [onProgress] reports bytes so far, for a live readout.
     */
    suspend fun downloadSpeed(
        context: Context,
        uuid: String,
        store: XrayStore,
        onProgress: (Long) -> Unit = {},
    ): Long? = withContext(Dispatchers.IO) {
        // Forwarded to :xray when called from any other process (XrayIpc).
        if (!XrayIpc.inXrayProcess()) {
            return@withContext XrayRemote.downloadSpeed(context, uuid)
        }
        // Same refusal as probe(): starting a second core would rewrite the live
        // tunnel's TUN fd env var.
        if (XrayEngine.isRunning || ir.swiftvpn.xray.XrayVpnService.isTunnelRunning) {
            return@withContext null
        }

        val raw = store.link(uuid) ?: return@withContext null
        val custom = XrayConfig.isCustomJson(raw)
        val outbound = if (custom) null else XrayShareLink.parse(raw)
        if (!custom && outbound == null) return@withContext null
        if (!XrayRuntime.ensure(context)) return@withContext null

        mutex.withLock {
            val port = freePort() ?: return@withContext null

            var controller: CoreController? = null
            try {
                CrashReporter.breadcrumb(context, "speed test ${outbound?.address ?: "custom"}")
                controller = startProbeInstance(
                    if (custom) XrayProbeConfig.buildCustom(raw, port)
                    else outbound?.let { XrayProbeConfig.build(it, port) },
                    port,
                ) ?: return@withContext null
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))

                val conn = (URL(SPEED_URL).openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = TCP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    requestMethod = "GET"
                }

                var total = 0L
                val start = System.nanoTime()
                conn.inputStream.use { input ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        onProgress(total)
                        // Stop early once we have enough to be accurate, so the
                        // test stays bounded even on a fast link.
                        if (System.nanoTime() - start > SPEED_TEST_LIMIT_NS) break
                    }
                }
                conn.disconnect()

                val seconds = (System.nanoTime() - start) / 1_000_000_000.0
                if (seconds <= 0 || total <= 0) null else (total / seconds).toLong()
            } catch (e: Exception) {
                Log.d(TAG, "speed test failed", e)
                null
            } finally {
                runCatching { controller?.stopLoop() }
                CrashReporter.breadcrumb(context, null)
                runCatching { delay(CORE_COOLDOWN_MS) }
            }
        }
    }

    /** Shown when a test is refused because a tunnel is live. */
    const val ERR_BUSY = "Disconnect first — testing needs the tunnel to be off"

    // -------------------------------------------------------------- internals

    /**
     * Starts a private Xray instance exposing SOCKS on [port] and nothing else.
     *
     * `startLoop(cfg, 0)` — the 0 is the TUN fd, and the wrapper treats 0 as "do
     * not use TUN", so this instance never builds a tun inbound.
     *
     * DANGER, and the reason [probe] refuses to run while a tunnel is live:
     * `CoreController.StartLoop` in the Go wrapper does
     *
     *     setEnvVariable("xray.tun.fd", strconv.Itoa(int(tunFd)))
     *
     * UNCONDITIONALLY, before it looks at anything else — and that lands in
     * `os.Setenv`, which is **process-global**. So starting a probe with fd 0
     * overwrites the value the live tunnel's own StartLoop wrote. The live core
     * caches its fd at startup so an established tunnel keeps working, but if a
     * probe's Setenv interleaves with a connect, the real tunnel would bind fd 0
     * (this process's stdin) as its TUN and pass no traffic at all — a failure
     * that looks exactly like a bad server.
     *
     * The defence is to never let the two overlap: [probe] and [downloadSpeed]
     * refuse outright while Xray holds the tunnel. There is deliberately no
     * "restore the value afterwards" fallback — the only way to write that env
     * var is another StartLoop, so a restore would itself be the racing write it
     * is meant to fix. Refusing is the honest, correct answer, and the user is
     * told to disconnect first rather than being handed a silently broken tunnel.
     *
     * The [mutex] additionally serialises probes against each other, so two rows
     * tested in quick succession cannot interleave their own Setenv calls.
     */
    private fun startProbeInstance(cfg: String?, port: Int): CoreController? =
        runCatching {
            if (cfg == null) return null
            val ctrl = Libv2ray.newCoreController(SilentCallback)
            ctrl.startLoop(cfg, 0)
            // The listener needs a moment to bind before the first connect.
            Thread.sleep(START_SETTLE_MS)
            ctrl
        }.onFailure { Log.d(TAG, "probe instance failed: ${it.message}") }.getOrNull()

    /** Serialises everything that starts a core, for the reason above. */
    private val mutex = Mutex()

    private fun fetchThroughProxy(url: String, proxy: Proxy): String? = runCatching {
        val conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = TCP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            requestMethod = "GET"
        }
        conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }.getOrNull()

    /**
     * An ephemeral local port, found by letting the OS pick one and closing it.
     *
     * Inherently a small race — something else could claim the port in between —
     * but it is the standard approach and the window is microseconds on loopback.
     */
    private fun freePort(): Int? = runCatching {
        ServerSocket(0).use { it.localPort }
    }.getOrNull()

    /** The probe has no UI; swallow the core's callbacks. */
    private object SilentCallback : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(l: Long, s: String?): Long = 0
    }

    private const val TCP_TIMEOUT_MS = 5_000
    private const val HTTP_TIMEOUT_MS = 10_000
    private const val START_SETTLE_MS = 250L
    /**
     * Gap left between one core instance closing and the next one starting.
     * Empirically necessary: a tight create/destroy loop faults in native code.
     */
    private const val CORE_COOLDOWN_MS = 300L
    private const val SPEED_TEST_LIMIT_NS = 8_000_000_000L // cap the test at 8s
}
