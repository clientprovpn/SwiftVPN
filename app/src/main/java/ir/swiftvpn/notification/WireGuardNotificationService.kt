package ir.swiftvpn.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import ir.swiftvpn.MainActivity
import ir.swiftvpn.R
import ir.swiftvpn.engine.EngineFormat
import ir.swiftvpn.engine.Protocol
import ir.swiftvpn.engine.VpnEngine
import ir.swiftvpn.engine.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * Posts the ongoing notification for WireGuard connections.
 *
 * **Why this service has to exist.** For OpenVPN the engine's own
 * `OpenVPNService` posts the notification and calls `startForeground` itself, so
 * the app never had to. `GoBackend$VpnService` does neither — verified by
 * disassembling the shipped AAR: it references no Notification API and never
 * calls startForeground. So a WireGuard tunnel came up with no shade entry and
 * no speedometer at all, which is exactly what the user reported.
 *
 * **Why it is a foreground service rather than a bare notification.** Two
 * reasons, and the first is the important one:
 *
 *  1. `GoBackend$VpnService` is not in the foreground, so on Android 8+ nothing
 *     is protecting the process once the Activity is gone. Android is free to
 *     kill it and the tunnel dies with it. Holding a foreground service in the
 *     same process keeps the whole thing alive — the notification is the visible
 *     side effect of a lifecycle requirement, not just decoration.
 *  2. An ongoing notification posted without a foreground service can be
 *     dismissed by the user on Android 14+, which would leave a running tunnel
 *     with no indicator.
 *
 * The content is formatted to match the engine's OpenVPN notification exactly
 * (see [EngineFormat]), so switching protocols does not change how the shade
 * reads.
 */
class WireGuardNotificationService : Service() {

    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        // Post immediately: startForeground must be called within a few seconds
        // of the service starting or Android throws. Waiting for the first flow
        // emission would be cutting it close.
        startForeground(NOTIFICATION_ID, build(VpnState.CONNECTING, null, null))

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = newScope

        newScope.launch {
            combine(
                VpnEngine.state,
                VpnEngine.traffic,
                VpnEngine.connectedSince,
                VpnEngine.connectedUuid,
            ) { state, traffic, since, uuid ->
                Snapshot(state, traffic.bytesIn, traffic.bytesOut,
                    traffic.downBytesPerSec, traffic.upBytesPerSec, since, uuid)
            }.collect { snap ->
                // The tunnel ending is the signal to stop. Checking the protocol
                // too means an OpenVPN connection starting right after cannot
                // keep this service alive posting a second notification beside
                // the engine's own.
                if (!snap.state.isActive ||
                    VpnEngine.activeProtocol.value != Protocol.WIREGUARD
                ) {
                    stopSelf()
                    return@collect
                }
                notificationManager.notify(NOTIFICATION_ID, build(snap))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Not sticky: if Android kills this, the tunnel is gone too and a
        // restarted notification service would be describing nothing.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null

        // Belt and braces, in this order. Destroying a foreground service does
        // remove its notification automatically, but STOP_FOREGROUND_REMOVE is
        // the explicit, most portable form, and the manual cancel covers OEM
        // builds where the row has been seen to linger a moment longer.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        runCatching { notificationManager.cancel(NOTIFICATION_ID) }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ content

    private data class Snapshot(
        val state: VpnState,
        val bytesIn: Long,
        val bytesOut: Long,
        val rateIn: Long,
        val rateOut: Long,
        val since: Long?,
        val uuid: String?,
    )

    private fun build(snap: Snapshot): Notification = build(
        state = snap.state,
        line = if (snap.state == VpnState.CONNECTED) {
            EngineFormat.statusLine(
                totalIn = snap.bytesIn,
                rateIn = snap.rateIn,
                totalOut = snap.bytesOut,
                rateOut = snap.rateOut,
                uptimeMillis = snap.since?.let { System.currentTimeMillis() - it } ?: 0L,
            )
        } else null,
        since = snap.since,
    )

    private fun build(state: VpnState, line: String?, since: Long?): Notification {
        val name = profileName()

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setContentTitle(
                if (name != null) getString(R.string.notification_title_wg, name)
                else getString(R.string.state_connecting)
            )
            .setContentText(line ?: stateText(state))
            .setSmallIcon(iconFor(state))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .addAction(disconnectAction())

        // LOW, not MIN. Both are silent and neither ever peeks, but MIN also
        // hides the status-bar icon and collapses the shade row — so the live
        // figures are only readable after expanding it. The engine uses MIN for
        // OpenVPN, and this deliberately differs: the speedometer being visible
        // at a glance is the entire point of this notification.
        @Suppress("DEPRECATION")
        builder.setPriority(Notification.PRIORITY_LOW)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_SERVICE)
            builder.setVisibility(Notification.VISIBILITY_PUBLIC)
        }

        // The uptime is rendered INSIDE the text (as the engine does after our
        // local modification), so the corner timestamp is redundant noise.
        if (since != null) builder.setShowWhen(false)

        return builder.build()
    }

    private fun stateText(state: VpnState): String = getString(
        when (state) {
            VpnState.CONNECTING -> R.string.state_connecting
            VpnState.CONNECTED -> R.string.state_connected
            VpnState.PAUSED -> R.string.state_paused
            VpnState.AUTH_FAILED -> R.string.state_auth_failed
            VpnState.NO_NETWORK -> R.string.state_no_network
            else -> R.string.state_disconnected
        }
    )

    private fun iconFor(state: VpnState) = when (state) {
        VpnState.CONNECTED -> R.drawable.ic_stat_vpn
        VpnState.CONNECTING -> R.drawable.ic_stat_vpn_outline
        else -> R.drawable.ic_stat_vpn_offline
    }

    /**
     * Name of the profile this notification is describing, resolved at most once
     * per uuid.
     *
     * [build] runs on the main thread roughly once a second. Looking the name up
     * there would mean constructing a WireGuardStore and touching
     * SharedPreferences on every tick — cheap after the first read, but the FIRST
     * read forces a synchronous prefs load, and that one lands inside onCreate
     * where it eats into the five-second startForeground budget. Caching keeps
     * both costs to one.
     */
    private var cachedNameUuid: String? = null
    private var cachedName: String? = null

    private fun profileName(): String? {
        val uuid = VpnEngine.connectedUuid.value ?: VpnEngine.pendingUuid.value ?: return null
        if (uuid != cachedNameUuid) {
            cachedNameUuid = uuid
            cachedName = runCatching {
                ir.swiftvpn.engine.WireGuardStore(this).name(uuid)
            }.getOrNull()
        }
        return cachedName
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun disconnectAction(): Notification.Action {
        val intent = Intent(this, WireGuardDisconnectReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            this,
            1,
            intent,
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
            getString(R.string.notification_channel_wg),
            // LOW, not MIN — and on API 26+ it is the CHANNEL that decides;
            // Builder.setPriority is ignored there, so this line is the one
            // that actually matters. LOW is still completely silent and never
            // peeks, but unlike MIN it keeps the status-bar icon and an
            // expanded shade row, so the live speedometer is readable at a
            // glance. That visibility is the entire point of the notification.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "swiftvpn_wireguard"
        private const val NOTIFICATION_ID = 0x5747  // "WG"

        fun start(context: Context) {
            val intent = Intent(context, WireGuardNotificationService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                // Do NOT let this fail silently. On Android 12+ starting a
                // foreground service from the background throws
                // ForegroundServiceStartNotAllowedException, and the visible
                // result is a running tunnel with no notification — precisely
                // the bug this service was added to fix. Both current callers
                // are exempt (a foreground tap, or a Quick Settings tile click,
                // which grants a temporary allowance), so if this ever fires it
                // means a new unattended path was added and needs its own
                // exemption.
                android.util.Log.w(
                    "WireGuardNotification",
                    "could not start the notification service",
                    it,
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(
                    Intent(context, WireGuardNotificationService::class.java)
                )
            }
        }
    }
}
