package ir.swiftvpn.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The UI-process end of the Xray IPC (see [XrayIpc] for why this exists).
 *
 * Three jobs:
 *
 *  1. [events] — the Messenger handed to XrayVpnService at connect time; it
 *     translates the service's EV_* messages back into the same
 *     XrayEngine.report* calls the router has always consumed.
 *  2. Test client — latency/probe/speed requests bound to XrayTestService in
 *     :xray, so libgojni is only ever loaded there. Requests are serialised:
 *     the :xray side starts a throwaway core per measurement, and queuing is
 *     both cheaper and more accurate than competing cores.
 *  3. [forceStopBlocking] — the router's synchronous teardown, rebound as a
 *     control message with an acknowledgement, because the old direct
 *     `instance` handle cannot reach across processes.
 */
object XrayRemote {

    private const val TAG = "XrayRemote"

    /** Generous ceilings; the :xray side has its own, tighter timeouts. */
    private const val LATENCY_TIMEOUT_MS = 30_000L
    private const val PROBE_TIMEOUT_MS = 45_000L
    private const val SPEED_TIMEOUT_MS = 120_000L
    private const val BIND_TIMEOUT_MS = 5_000L
    private const val FORCE_STOP_TIMEOUT_MS = 5_000L

    // ---------------------------------------------------------------- events

    /**
     * Receives tunnel events from :xray and replays them into XrayEngine.
     * The handler runs on the main thread: every report* just sets StateFlow
     * values, which is safe anywhere, and main keeps ordering predictable.
     */
    val events: Messenger = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            val d = msg.data
            when (msg.what) {
                XrayIpc.EV_CONNECTED -> XrayEngine.reportConnected()
                XrayIpc.EV_ERROR -> XrayEngine.reportError(
                    d.getString(XrayIpc.K_ERROR) ?: "unknown",
                )
                XrayIpc.EV_STOPPED -> XrayEngine.reportStopped()
                XrayIpc.EV_BYTES -> XrayEngine.reportBytes(
                    d.getLong(XrayIpc.K_TOTAL_IN),
                    d.getLong(XrayIpc.K_TOTAL_OUT),
                    d.getLong(XrayIpc.K_DIFF_IN),
                    d.getLong(XrayIpc.K_DIFF_OUT),
                )
                XrayIpc.EV_LOG -> d.getString(XrayIpc.K_TEXT)?.let {
                    XrayEngine.reportLog(it)
                }
                XrayIpc.EV_TUNNEL_INFO -> XrayEngine.reportTunnelInfo(
                    TunnelInfo(
                        remoteServer = d.getString(XrayIpc.K_REMOTE),
                        proxy = d.getString(XrayIpc.K_PROXY),
                        dnsServers = d.getStringArrayList(XrayIpc.K_DNS) ?: emptyList(),
                        routes = d.getStringArrayList(XrayIpc.K_ROUTES) ?: emptyList(),
                        mtu = if (d.containsKey(XrayIpc.K_MTU)) d.getInt(XrayIpc.K_MTU) else null,
                    ),
                )
            }
            true
        },
    )

    // ---------------------------------------------------------- test client

    private var testService: Messenger? = null
    private var testBound = false
    private val bindMutex = Mutex()

    /** Completed by the reply handler; single because requests are serial. */
    @Volatile
    private var pending: CompletableDeferred<Message>? = null

    private val replyMessenger = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            pending?.complete(msg)
            true
        },
    )

    private val testConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            testService = Messenger(binder)
            testBound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // The :xray process died (crash or LMK). Any in-flight measurement
            // is lost; wake the waiter so it fails instead of hanging.
            testService = null
            testBound = false
            pending?.completeExceptionally(
                IllegalStateException("xray test process went away"),
            )
        }
    }

    private suspend fun ensureTestBound(context: Context): Messenger? {
        testService?.let { if (testBound) return it }
        return bindMutex.withLock {
            testService?.let { if (testBound) return@withLock it }
            val app = context.applicationContext
            val ok = runCatching {
                app.bindService(
                    Intent().setComponent(
                        ComponentName(app.packageName, "ir.swiftvpn.xray.XrayTestService"),
                    ),
                    testConnection,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(false)
            if (!ok) {
                Log.w(TAG, "bindService to :xray test endpoint refused")
                DiagnosticLog.write(
                    DiagnosticLog.TEST,
                    "xray test: bindService to :xray refused",
                )
                return@withLock null
            }
            // Wait briefly for onServiceConnected. bindService is async even on
            // success; 5s is generous for a local process.
            withContext(Dispatchers.IO) {
                repeat(50) {
                    if (testBound && testService != null) return@withContext
                    Thread.sleep(100)
                }
            }
            if (testBound) testService else run {
                DiagnosticLog.write(
                    DiagnosticLog.TEST,
                    "xray test: :xray process did not answer the bind",
                )
                null
            }
        }
    }

    /**
     * Serialises whole round-trips: [pending] holds exactly one waiter, so two
     * measurements must never overlap — same discipline the in-process tester
     * enforced with its own mutex.
     */
    private val requestMutex = Mutex()

    /**
     * Sends one request and awaits its reply, fully serialised. The reply's
     * Bundle is the payload; on timeout or transport failure the result is
     * null, which callers already treat as "measurement failed".
     */
    private suspend fun roundTrip(
        context: Context,
        what: Int,
        args: Bundle,
        timeoutMs: Long,
    ): Bundle? = withContext(Dispatchers.IO) {
        requestMutex.withLock {
            val remote = ensureTestBound(context) ?: return@withLock null
            val deferred = CompletableDeferred<Message>()
            pending = deferred
            val msg = Message.obtain(null, what).apply {
                data = args
                replyTo = replyMessenger
            }
            val sent = runCatching { remote.send(msg) }.isSuccess
            if (!sent) {
                pending = null
                return@withLock null
            }
            val reply = withTimeoutOrNull(timeoutMs) { deferred.await() }
            pending = null
            reply?.data
        }
    }

    /** Round-trip ms for [uuid], measured in :xray; null when unreachable. */
    suspend fun latency(context: Context, uuid: String): Long? {
        val d = roundTrip(
            context, XrayIpc.REQ_LATENCY,
            Bundle().apply { putString(XrayIpc.K_UUID, uuid) },
            LATENCY_TIMEOUT_MS,
        ) ?: run {
            DiagnosticLog.write(
                DiagnosticLog.TEST,
                "xray latency: no reply from :xray process for $uuid",
            )
            return null
        }
        val ms = d.getLong(XrayIpc.K_MS, -1)
        return if (ms >= 0) ms else null
    }

    /** Full probe (latency + egress country/IP) for [uuid], measured in :xray. */
    suspend fun probe(context: Context, uuid: String): XrayTester.ProbeResult {
        val d = roundTrip(
            context, XrayIpc.REQ_PROBE,
            Bundle().apply { putString(XrayIpc.K_UUID, uuid) },
            PROBE_TIMEOUT_MS,
        ) ?: return XrayTester.ProbeResult(error = "measurement process unavailable")
        return XrayTester.ProbeResult(
            latencyMs = d.getLong(XrayIpc.K_MS, -1).takeIf { it >= 0 },
            countryCode = d.getString(XrayIpc.K_COUNTRY),
            egressIp = d.getString(XrayIpc.K_IP),
            error = d.getString(XrayIpc.K_ERROR),
        )
    }

    /** Throughput in bytes/sec for [uuid], measured in :xray. */
    suspend fun downloadSpeed(context: Context, uuid: String): Long? {
        val d = roundTrip(
            context, XrayIpc.REQ_SPEED,
            Bundle().apply { putString(XrayIpc.K_UUID, uuid) },
            SPEED_TIMEOUT_MS,
        ) ?: return null
        val bps = d.getLong(XrayIpc.K_BYTES_PER_SEC, -1)
        return if (bps >= 0) bps else null
    }

    // ------------------------------------------------------------ force stop

    /**
     * The router's synchronous Xray teardown, across the process boundary.
     *
     * Binds to XrayVpnService's control channel, asks it to release the TUN fd,
     * and returns only when the service confirms (or the timeout passes). The
     * old in-process version reached a static `instance` field, which simply
     * does not exist in this process anymore.
     *
     * Must be called from a background thread (the router already does).
     */
    fun forceStopBlocking(context: Context) {
        val app = context.applicationContext
        val boundLatch = CountDownLatch(1)
        val ackLatch = CountDownLatch(1)
        var remote: Messenger? = null

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote = Messenger(binder)
                boundLatch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }

        val ack = Messenger(
            Handler(Looper.getMainLooper()) { msg ->
                if (msg.what == XrayIpc.EV_FORCE_STOPPED) ackLatch.countDown()
                true
            },
        )

        val ok = runCatching {
            app.bindService(
                Intent(XrayIpc.ACTION_CONTROL).setComponent(
                    ComponentName(app.packageName, "ir.swiftvpn.xray.XrayVpnService"),
                ),
                conn,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)

        if (ok && boundLatch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            val msg = Message.obtain(null, XrayIpc.REQ_FORCE_STOP).apply { replyTo = ack }
            runCatching { remote?.send(msg) }
            ackLatch.await(FORCE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } else {
            // Fall back to the async intent: better a racy stop than none.
            Log.w(TAG, "control bind failed, falling back to ACTION_STOP")
            ir.swiftvpn.xray.XrayVpnService.stop(app)
        }
        runCatching { app.unbindService(conn) }
    }
}
