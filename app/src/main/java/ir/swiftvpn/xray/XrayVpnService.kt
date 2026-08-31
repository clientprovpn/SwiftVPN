package ir.swiftvpn.xray

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import ir.swiftvpn.MainActivity
import ir.swiftvpn.R
import ir.swiftvpn.engine.DiagnosticLog
import ir.swiftvpn.engine.EngineFormat
import ir.swiftvpn.engine.VpnState
import ir.swiftvpn.engine.XrayEngine
import ir.swiftvpn.engine.XrayIpc
import ir.swiftvpn.engine.XrayStore
import ir.swiftvpn.notification.WireGuardDisconnectReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * The VpnService for Xray.
 *
 * This is the one engine where we own the VpnService outright, because Xray is a
 * PROXY, not a TUN VPN. The flow is:
 *
 *   1. Build a TUN with VpnService.Builder and — critically —
 *      addDisallowedApplication(ourPackage). That exclusion is what breaks the
 *      routing loop: Xray's own uplink sockets belong to this app, so they skip
 *      the TUN and reach the real network. It is the non-root equivalent of the
 *      BindToDevice trick, and it means we need no per-socket protect callback.
 *   2. Hand the raw fd to xray-core, whose built-in gvisor stack reads L3
 *      packets off it and proxies them out through the configured outbound.
 *   3. Poll xray-core's stats once a second for the speedometer.
 *
 * The service holds the fd and the CoreController for their whole lifetime and
 * reports state back through a Messenger to the UI process (see [XrayIpc] for
 * why this service runs isolated in :xray — two gomobile Go runtimes in one
 * process crash each other's signal handling).
 */
class XrayVpnService : VpnService() {

    init {
        // See XrayTestService: the shared go.Seq runtime classes no longer
        // auto-load a library; :xray must pull in libgojni itself, first.
        System.loadLibrary("gojni")
    }

    /**
     * The UI process's event sink, handed over in the START intent. All tunnel
     * events (EV_*) go through it; XrayEngine's report* methods are only ever
     * invoked on the UI side.
     */
    private var reporter: Messenger? = null

    private fun sendEvent(what: Int, data: Bundle? = null) {
        val r = reporter ?: return
        runCatching {
            r.send(Message.obtain(null, what).apply { this.data = data ?: Bundle() })
        }.onFailure { android.util.Log.w("XrayVpnService", "event send failed", it) }
    }

    private var controller: CoreController? = null

    /**
     * The raw TUN descriptor, after ownership was detached from its
     * ParcelFileDescriptor.
     *
     * WE own it and WE close it, in cleanup(). That is not the obvious reading:
     * xray-core's Handler.Close() does call tun.Close(), and on Linux that
     * releases the descriptor — but `AndroidTun.Close()` is literally
     * `return nil`, and gvisor's fdbased endpoint never closes its FDs either.
     * So on Android nothing in the Go layer ever closes it, and skipping our own
     * close leaks one descriptor per connect.
     */
    private var tunFd: Int = -1

    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null

    private var uuid: String? = null

    // Cumulative totals, accumulated from the per-second deltas queryStats gives.
    private var totalIn = 0L
    private var totalOut = 0L
    private var connectedAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            else -> {
                uuid = intent?.getStringExtra(EXTRA_UUID)
                // The UI's event sink for this session. Keep the LAST one: a
                // reconnect may arrive while a stale service instance lingers.
                (intent?.getParcelableExtra<Messenger>(EXTRA_MESSENGER))?.let {
                    reporter = it
                }
                instance = this
                // Reset per-connection counters. The system can reuse a service
                // instance across a fast stop/start, so without this a reconnect
                // would inherit the previous session's totals.
                totalIn = 0
                totalOut = 0
                connectedAt = 0
                createChannel()
                // startForeground must happen within a few seconds of start;
                // post the connecting notification now, before the core work.
                startForegroundCompat()
                bringUp()
            }
        }
        // Not sticky: if the process is killed the tunnel is gone; a blind
        // restart would establish a TUN with no core behind it.
        return START_NOT_STICKY
    }

    /** Establishes the TUN and starts xray-core, all off the main thread. */
    private fun bringUp() {
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        newScope.launch {
            val id = uuid
            if (id == null) {
                fail("No profile specified")
                return@launch
            }

            val config = XrayEngine.buildConfig(this@XrayVpnService, id)
            if (config == null) {
                fail("This Xray profile could not be read")
                return@launch
            }

            DiagnosticLog.write(DiagnosticLog.XRAY, "building TUN interface")
            val pfd = establishTun()
            if (pfd == null) {
                DiagnosticLog.error(DiagnosticLog.XRAY, "VpnService.Builder.establish() returned null")
                fail("Android refused to create the tunnel interface")
                return@launch
            }

            try {
                // Shared one-time bootstrap. Lives in XrayRuntime rather than
                // here because the server tester also starts cores, and a test
                // run before any connection would otherwise reach Go with no
                // context and crash the process in native code.
                if (!ir.swiftvpn.engine.XrayRuntime.ensure(this@XrayVpnService)) {
                    runCatching { pfd.close() }
                    fail("Xray runtime could not be initialised")
                    return@launch
                }

                // TRANSFER the fd, do not lend it.
                //
                // `pfd.fd` only reads the number out while ParcelFileDescriptor
                // stays the owner. The descriptor then has two potential closers:
                // the PFD (via our cleanup, or its own finalizer) and xray-core,
                // which SetNonblocks it and closes it on any setup error. A close
                // by one while the other still holds the number is a use-after-free
                // on a descriptor the kernel may already have reassigned.
                //
                // detachFd() collapses that to a single owner: us. See the tunFd
                // field for why WE are the ones who must close it.
                val rawFd = pfd.detachFd()
                tunFd = rawFd
                DiagnosticLog.write(DiagnosticLog.XRAY, "TUN established, fd=$rawFd (ownership detached)")

                // newCoreController is itself a native call (it allocates the Go
                // controller), so it gets the same breadcrumb treatment as
                // startLoop — a fault here is just as invisible without one.
                ir.swiftvpn.engine.CrashReporter.breadcrumb(
                    this@XrayVpnService, "xray newCoreController",
                )
                val ctrl = Libv2ray.newCoreController(callback)
                ir.swiftvpn.engine.CrashReporter.breadcrumb(this@XrayVpnService, null)
                controller = ctrl
                sendEvent(
                    XrayIpc.EV_LOG,
                    Bundle().apply {
                        putString(
                            XrayIpc.K_TEXT,
                            "Starting Xray: ${runCatching { Libv2ray.checkVersionX() }.getOrDefault("?")}",
                        )
                    },
                )

                // Breadcrumb around the native call: a fault inside the Go core
                // raises no Java exception, so without this the process would
                // simply vanish leaving nothing to diagnose.
                ir.swiftvpn.engine.CrashReporter.breadcrumb(
                    this@XrayVpnService,
                    "Xray connect ${XrayStore(this@XrayVpnService).name(id)}",
                )
                // startLoop reads xray.tun.fd (set from this fd) and blocks only
                // briefly to bring the instance up.
                DiagnosticLog.write(DiagnosticLog.XRAY, "startLoop: entering native core")
                ctrl.startLoop(config, rawFd)
                DiagnosticLog.write(DiagnosticLog.XRAY, "startLoop: returned OK")
                ir.swiftvpn.engine.CrashReporter.breadcrumb(this@XrayVpnService, null)

                connectedAt = System.currentTimeMillis()
                isTunnelRunning = true
                publishTunnelInfo(id)
                sendEvent(XrayIpc.EV_CONNECTED)
                startPolling()
            } catch (e: Exception) {
                ir.swiftvpn.engine.CrashReporter.breadcrumb(this@XrayVpnService, null)
                DiagnosticLog.error(DiagnosticLog.XRAY, "connect failed", e)
                fail(e.localizedMessage ?: e.javaClass.simpleName)
            }
        }
    }

    private fun establishTun(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(uuid?.let { XrayStore(this).name(it) } ?: "Xray")
            .setMtu(MTU)
            // A private point-to-point address; the value is irrelevant because
            // the gvisor stack terminates everything, but one is required.
            .addAddress("10.10.10.10", 32)
            .addAddress("fdfe:dcba:9876::1", 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("2606:4700:4700::1111")

        // THE loop breaker. Without excluding our own package, Xray's outbound
        // to the server would be captured by our own TUN and loop forever.
        runCatching { builder.addDisallowedApplication(packageName) }
            .onFailure {
                sendEvent(
                    XrayIpc.EV_LOG,
                    Bundle().apply {
                        putString(XrayIpc.K_TEXT, "could not exclude own package: ${it.message}")
                    },
                )
            }

        return runCatching { builder.establish() }.getOrNull()
    }

    /**
     * The core's callbacks into us. These MUST return immediately and do nothing.
     *
     * This was the connect crash. `startLoop` invokes `Startup()` and
     * `OnEmitStatus()` SYNCHRONOUSLY, from a Go-scheduled thread that has been
     * attached to the JVM by gomobile for the duration of the call — Go calling
     * back into Java, on Go's stack. Doing real work there (the old version wrote
     * to a StateFlow, which fans out to collectors and ultimately touches
     * Compose) re-entered the JNI bridge from inside that borrowed frame, and the
     * process died in `runtime.cgocallback` — the C-to-Go trampoline — with no
     * Java exception at all. The crash address in the user's tombstone
     * (0x010c3fe0) symbolised to exactly that function.
     *
     * So the rule here is: capture the string, hand it to our OWN thread, return.
     * Nothing that could allocate on the Java heap, take a lock, or touch state
     * with observers runs while the Go frame is still on the stack.
     */
    private val callback = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(l: Long, s: String?): Long {
            if (!s.isNullOrBlank()) {
                // Queue and get out. scope is our own IO scope, so the log write
                // happens after the Go frame has unwound.
                val line = s
                scope?.launch {
                    sendEvent(
                        XrayIpc.EV_LOG,
                        Bundle().apply { putString(XrayIpc.K_TEXT, line) },
                    )
                }
            }
            return 0
        }
    }

    // --------------------------------------------------------------- polling

    /** Consecutive automatic restarts triggered by the stall watchdog. */
    private var stallRestarts = 0

    /**
     * Tears the tunnel down and brings it straight back up with the same
     * profile, in a fresh coroutine (cleanup() cancels the scope this poll loop
     * lives in, so the restart cannot run inside it). After 3 consecutive
     * failures the stall is structural — give up and disconnect cleanly.
     */
    private fun autoRestart() {
        stallRestarts++
        if (stallRestarts > 3) {
            DiagnosticLog.error(
                DiagnosticLog.XRAY,
                "STALL: tunnel still dead after 3 automatic restarts — disconnecting",
            )
            sendEvent(
                XrayIpc.EV_LOG,
                Bundle().apply {
                    putString(XrayIpc.K_TEXT, "Tunnel kept stalling after 3 restarts — disconnecting")
                },
            )
            fail("The connection kept stalling; please reconnect")
            return
        }
        DiagnosticLog.error(
            DiagnosticLog.XRAY,
            "STALL: apps sending but no return traffic for 16s — auto-restarting tunnel (attempt $stallRestarts/3)",
        )
        sendEvent(
            XrayIpc.EV_LOG,
            Bundle().apply {
                putString(XrayIpc.K_TEXT, "No return traffic — restarting tunnel automatically ($stallRestarts/3)")
            },
        )
        CoroutineScope(Dispatchers.IO).launch {
            cleanup()
            bringUp()
        }
    }

    /**
     * queryStats(tag, dir) returns the bytes since the LAST call and resets the
     * counter, so each poll yields the per-second delta directly — no manual
     * diffing, unlike WireGuard. We keep running totals for the cumulative
     * figures the notification and Usage tab show.
     */
    private fun startPolling() {
        var silentSeconds = 0
        pollJob = scope?.launch {
            while (isActive) {
                val ctrl = controller ?: break
                val down = runCatching { ctrl.queryStats(PROXY_TAG, "downlink") }.getOrDefault(0)
                val up = runCatching { ctrl.queryStats(PROXY_TAG, "uplink") }.getOrDefault(0)
                // What the APPS pushed into the tunnel this interval.
                val tunUp = runCatching { ctrl.queryStats(TUN_TAG, "uplink") }.getOrDefault(0)
                totalIn += down
                totalOut += up

                // Stall watchdog. The signature of a dead path (5G CGNAT drop,
                // dead upstream link) is: apps keep SENDING into the tunnel
                // (tunUp > 0) while NOTHING comes back from the proxy
                // (down == 0) for many seconds. An idle phone — both zero —
                // must never trip this, or we'd restart a healthy tunnel.
                // 16s of that means the path is gone; restart automatically,
                // capped at 3 tries so a dead server can't loop forever.
                if (down == 0L && tunUp > 0L) {
                    silentSeconds++
                    if (silentSeconds == 8) {  // 8 polls × 2s = 16s
                        silentSeconds = 0
                        autoRestart()
                    }
                } else {
                    silentSeconds = 0
                    stallRestarts = 0
                }
                sendEvent(
                    XrayIpc.EV_BYTES,
                    Bundle().apply {
                        putLong(XrayIpc.K_TOTAL_IN, totalIn)
                        putLong(XrayIpc.K_TOTAL_OUT, totalOut)
                        putLong(XrayIpc.K_DIFF_IN, down)
                        putLong(XrayIpc.K_DIFF_OUT, up)
                    },
                )
                updateNotification(down, up)
                delay(2_000)
            }
        }
    }

    // ---------------------------------------------------------------- teardown

    private fun fail(message: String) {
        sendEvent(
            XrayIpc.EV_ERROR,
            Bundle().apply { putString(XrayIpc.K_ERROR, message) },
        )
        cleanup()
        stopSelfCompat()
    }

    private fun shutdown() {
        sendEvent(XrayIpc.EV_STOPPED)
        cleanup()
        stopSelfCompat()
    }

    /**
     * Synchronous teardown for the router, used when ANOTHER engine is about to
     * claim the single VPN slot.
     *
     * The normal stop is an async intent, which is fine for a user tap but wrong
     * here: if the next engine establishes its TUN before ours is released,
     * Android revokes one and the new connection fails looking like a config
     * error. This runs cleanup inline so the fd is closed before the caller
     * proceeds. It is invoked from the router on an IO thread, so the blocking
     * stopLoop is safe. reportStopped is intentionally NOT called — the router
     * is mid-switch and its gate would drop it anyway.
     */
    fun stopForRouter() {
        cleanup()
        stopSelfCompat()
    }

    private fun cleanup() {
        isTunnelRunning = false
        pollJob?.cancel()
        pollJob = null
        scope?.cancel()
        scope = null

        // Order matters: stop the core FIRST so nothing is still reading the
        // descriptor, then close it ourselves. stopLoop is a native call too —
        // breadcrumb it so a teardown fault is attributable like a startup one.
        ir.swiftvpn.engine.CrashReporter.breadcrumb(this, "xray stopLoop")
        runCatching { controller?.stopLoop() }
        ir.swiftvpn.engine.CrashReporter.breadcrumb(this, null)
        controller = null

        // We close it, because on Android the Go side never does.
        //
        // Worth being explicit, since the obvious assumption is wrong: xray-core's
        // Handler.Close() calls tun.Close(), and on Linux that really does close
        // the descriptor — but `AndroidTun.Close()` (proxy/tun/tun_android.go) is
        // literally `return nil`, and gvisor's fdbased endpoint never closes its
        // FDs either. So after detachFd() moved ownership off the
        // ParcelFileDescriptor, THIS is the only close in the system. Leaving it
        // out leaks one descriptor per connect until the process hits its fd
        // limit.
        if (tunFd >= 0) {
            // adoptFd takes ownership of the raw int and gives back a PFD whose
            // close() releases it — one owner, one close, no Os/FileDescriptor
            // juggling.
            runCatching { ParcelFileDescriptor.adoptFd(tunFd).close() }
                .onFailure { android.util.Log.w("XrayVpnService", "tun close failed", it) }
            tunFd = -1
        }
    }

    private fun stopSelfCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    /** The system calls this when another VPN takes over or the user revokes. */
    override fun onRevoke() {
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        // Belt and braces: if we are torn down without an explicit stop, still
        // release the core and tell the router.
        if (controller != null || tunFd >= 0) {
            sendEvent(XrayIpc.EV_STOPPED)
            cleanup()
        }
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Two bind channels share one service:
     *  - the system's VPN bind (SERVICE_INTERFACE) goes to the platform;
     *  - our ACTION_CONTROL bind serves the UI process's synchronous
     *    force-stop, which cannot reach a static `instance` field across the
     *    process boundary anymore.
     */
    override fun onBind(intent: Intent): IBinder? {
        if (intent.action == XrayIpc.ACTION_CONTROL) return controlMessenger.binder
        @Suppress("DEPRECATION")
        return super.onBind(intent)
    }

    private val controlMessenger = Messenger(
        Handler(android.os.Looper.getMainLooper()) { msg ->
            if (msg.what == XrayIpc.REQ_FORCE_STOP) {
                // Same body as stopForRouter: release fd and core inline, then
                // acknowledge so the router can hand the VPN slot over safely.
                stopForRouter()
                runCatching { msg.replyTo?.send(Message.obtain(null, XrayIpc.EV_FORCE_STOPPED)) }
            }
            true
        },
    )

    private fun startForegroundCompat() {
        // On API 34+ the running foreground type must be asserted at the call
        // site, not just declared in the manifest, or startForeground can throw
        // MissingForegroundServiceTypeException. ServiceCompat handles the
        // per-API plumbing; specialUse matches the manifest and the type the
        // embedded OpenVPN engine already uses, so no new permission is pulled.
        val notification = buildNotification(VpnState.CONNECTING, null)
        runCatching {
            androidx.core.app.ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        }.onFailure {
            android.util.Log.w("XrayVpnService", "startForeground failed", it)
        }
    }

    // ----------------------------------------------------------- notification

    private fun publishTunnelInfo(id: String) {
        val (remote, summary) = XrayStore(this).describe(id) ?: return
        sendEvent(
            XrayIpc.EV_TUNNEL_INFO,
            Bundle().apply {
                putString(XrayIpc.K_REMOTE, remote)
                putString(XrayIpc.K_PROXY, summary)
                putStringArrayList(
                    XrayIpc.K_DNS,
                    arrayListOf("1.1.1.1", "2606:4700:4700::1111"),
                )
                putStringArrayList(XrayIpc.K_ROUTES, arrayListOf("0.0.0.0/0", "::/0"))
                putInt(XrayIpc.K_MTU, MTU)
            },
        )
    }

    private fun updateNotification(down: Long, up: Long) {
        val line = EngineFormat.statusLine(
            totalIn = totalIn,
            rateIn = down,
            totalOut = totalOut,
            rateOut = up,
            uptimeMillis = if (connectedAt > 0) System.currentTimeMillis() - connectedAt else 0L,
        )
        notificationManager.notify(NOTIFICATION_ID, buildNotification(VpnState.CONNECTED, line))
    }

    private fun buildNotification(state: VpnState, line: String?): Notification {
        val name = uuid?.let { XrayStore(this).name(it) }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setContentTitle(
                if (name != null && state == VpnState.CONNECTED) {
                    getString(R.string.notification_title_wg, name)
                } else {
                    getString(R.string.state_connecting)
                }
            )
            .setContentText(line ?: getString(R.string.state_connecting))
            .setSmallIcon(
                if (state == VpnState.CONNECTED) R.drawable.ic_stat_vpn
                else R.drawable.ic_stat_vpn_outline
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .addAction(disconnectAction())

        @Suppress("DEPRECATION")
        builder.setPriority(Notification.PRIORITY_LOW)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_SERVICE)
            builder.setVisibility(Notification.VISIBILITY_PUBLIC)
        }
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun disconnectAction(): Notification.Action {
        // Reuses the shared disconnect receiver: it just calls
        // VpnEngine.disconnect, which routes by the active protocol — so the same
        // receiver correctly stops whichever engine is live.
        val pending = PendingIntent.getBroadcast(
            this,
            2,
            Intent(this, WireGuardDisconnectReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            null,
            getString(R.string.action_disconnect),
            pending,
        ).build()
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_xray),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_START = "ir.swiftvpn.xray.START"
        private const val ACTION_STOP = "ir.swiftvpn.xray.STOP"
        private const val EXTRA_UUID = "uuid"
        private const val EXTRA_MESSENGER = XrayIpc.EXTRA_MESSENGER
        private const val CHANNEL_ID = "swiftvpn_xray"
        private const val NOTIFICATION_ID = 0x5852 // "XR"
        private const val PROXY_TAG = "proxy"
        private const val TUN_TAG = "tun-in"
        private const val MTU = 1500

        /**
         * True while this process holds a live Xray tunnel. Checked by the
         * in-process tester (XrayTester) as a second line of defence against
         * starting a throwaway core next to the live one — the UI-side
         * XrayEngine.activeUuid cannot be read from here anymore.
         */
        @Volatile
        var isTunnelRunning: Boolean = false
            private set

        /**
         * The live service instance, so the router can tear the tunnel down
         * synchronously when switching engines. Only meaningful INSIDE the
         * :xray process; the UI process must use the control binder instead.
         * @Volatile because it is written on the main thread
         * (onStartCommand/onDestroy) and read on IO.
         */
        @Volatile
        private var instance: XrayVpnService? = null

        /**
         * Synchronous stop for the engine switch path. Returns once the fd is
         * released, so the next engine can safely claim the VPN slot. No-op if
         * nothing is running. Same-process use only.
         */
        fun stopBlocking() {
            instance?.stopForRouter()
        }

        fun start(context: Context, uuid: String, reporter: Messenger) {
            val intent = Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_UUID, uuid)
                putExtra(EXTRA_MESSENGER, reporter)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                android.util.Log.w("XrayVpnService", "could not start", it)
                XrayEngine.reportError("The VPN service could not be started")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }
}
