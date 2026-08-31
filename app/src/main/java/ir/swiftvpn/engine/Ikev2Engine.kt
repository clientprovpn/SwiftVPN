package ir.swiftvpn.engine

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import org.strongswan.android.data.VpnProfileDataSource
import org.strongswan.android.logic.CharonVpnService
import org.strongswan.android.logic.SwiftContext
import org.strongswan.android.logic.TrustedCertificateManager
import org.strongswan.android.logic.VpnStateService
import org.strongswan.android.security.LocalCertificateKeyStoreProvider
import java.security.Security
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * IKEv2 engine: owns the vendored strongSwan/charon service.
 *
 * Lifecycle mirrors the WireGuard engine — connect() hands the profile UUID to
 * CharonVpnService (a VpnService that runs charon on its own thread), and
 * state comes back through the reporter we install on VpnStateService.
 */
object Ikev2Engine {

    private const val TAG = "Ikev2Engine"

    /** Bridges strongSwan states into the app's normalised state flow. */
    var onState: ((state: VpnState, error: String?) -> Unit)? = null

    /** Traffic counters for the in-app speedometer, sampled from the tun interface. */
    var onBytes: ((bytesIn: Long, bytesOut: Long, diffIn: Long, diffOut: Long) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var statsJob: Job? = null

    /**
     * Byte counters via per-UID traffic stats (netd). Reading
     * /sys/class/net/tun* is SELinux-denied for apps on modern Android, so the
     * interface-counter approach silently returned nothing. Our UID's sockets
     * are IKE/ESP carrying the tunnelled payload, so the rates track real
     * throughput closely.
     */
    private fun readTunBytes(): Pair<Long, Long>? {
        val uid = android.os.Process.myUid()
        val rx = android.net.TrafficStats.getUidRxBytes(uid)
        val tx = android.net.TrafficStats.getUidTxBytes(uid)
        if (rx == android.net.TrafficStats.UNSUPPORTED.toLong() ||
            tx == android.net.TrafficStats.UNSUPPORTED.toLong()
        ) {
            return null
        }
        return rx to tx
    }

    private fun startStats() {
        if (statsJob?.isActive == true) return
        statsJob = scope.launch {
            var baseRx = -1L
            var baseTx = -1L
            while (isActive) {
                val counters = readTunBytes()
                if (counters != null) {
                    val (rx, tx) = counters
                    if (baseRx < 0) {
                        baseRx = rx
                        baseTx = tx
                        onBytes?.invoke(rx, tx, 0, 0)
                    } else {
                        onBytes?.invoke(
                            rx, tx,
                            (rx - baseRx).coerceAtLeast(0),
                            (tx - baseTx).coerceAtLeast(0),
                        )
                        baseRx = rx
                        baseTx = tx
                    }
                }
                delay(1000)
            }
        }
    }

    private fun stopStats() {
        statsJob?.cancel()
        statsJob = null
    }

    @Volatile
    private var initialised = false

    @Synchronized
    fun init(context: Context) {
        if (initialised) return
        SwiftContext.init(context)
        // Registers the "LocalCertificateStore" KeyStore used for imported CA
        // certificates and user keys.
        if (Security.getProvider("LocalCertificateKeyStoreProvider") == null) {
            Security.addProvider(LocalCertificateKeyStoreProvider())
        }
        runCatching { TrustedCertificateManager.getInstance().load() }
        VpnStateService.setStateReporter { state, error, profile ->
            val mapped = when (state) {
                VpnStateService.State.CONNECTING -> VpnState.CONNECTING
                VpnStateService.State.CONNECTED -> VpnState.CONNECTED
                VpnStateService.State.DISCONNECTING -> VpnState.CONNECTING
                VpnStateService.State.DISABLED -> VpnState.DISCONNECTED
            }
            when (state) {
                VpnStateService.State.CONNECTED -> startStats()
                VpnStateService.State.DISABLED -> stopStats()
                else -> Unit
            }
            val err = when (error) {
                VpnStateService.ErrorState.NO_ERROR -> null
                VpnStateService.ErrorState.AUTH_FAILED -> "Authentication failed"
                VpnStateService.ErrorState.PEER_AUTH_FAILED -> "Server authentication failed"
                VpnStateService.ErrorState.LOOKUP_FAILED -> "Server address lookup failed"
                VpnStateService.ErrorState.UNREACHABLE -> "Server is unreachable"
                VpnStateService.ErrorState.PASSWORD_MISSING -> "Password missing"
                VpnStateService.ErrorState.CERTIFICATE_UNAVAILABLE -> "Client certificate unavailable"
                VpnStateService.ErrorState.GENERIC_ERROR -> "Connection failed"
            }
            onState?.invoke(mapped, err)
            DiagnosticLog.write(
                DiagnosticLog.IKEV2,
                "IKEv2 state: $state ${error.name} ${profile?.name ?: ""}",
            )
            // charon writes its own detailed log to charon.log — mirror its
            // tail into the diagnostic log on failure AND on connect, so an
            // export shows what was negotiated (virtual IP, DNS servers,
            // routes, MTU) when "connected but nothing loads" is reported.
            if (error != VpnStateService.ErrorState.NO_ERROR ||
                state == VpnStateService.State.CONNECTED
            ) {
                charonLogTail()?.let { tail ->
                    DiagnosticLog.write(DiagnosticLog.IKEV2, "--- charon log tail ---\n$tail")
                }
            }
        }
        initialised = true
    }

    /** Last few KB of charon's own log, or null when unavailable. */
    private fun charonLogTail(): String? = runCatching {
        val ctx = SwiftContext.get() ?: return null
        val f = java.io.File(ctx.filesDir, "charon.log")
        if (!f.exists()) return null
        val bytes = f.readBytes()
        val from = maxOf(0, bytes.size - 8192)
        String(bytes, from, bytes.size - from).lines().takeLast(80).joinToString("\n")
    }.getOrNull()

    fun connect(context: Context, uuid: String, password: String?) {
        init(context)
        // Truncate charon's filelog at the start of every attempt: it is opened
        // with append=TRUE and never rotated, so without this the "charon log
        // tail" mirrored into exports keeps showing stale sessions from hours
        // ago. O_APPEND in charon keeps writing correctly after an external
        // truncate.
        runCatching { java.io.File(context.filesDir, "charon.log").writeText("") }
        val intent = Intent(context, CharonVpnService::class.java)
        intent.putExtra(VpnProfileDataSource.KEY_UUID, uuid)
        if (!password.isNullOrBlank()) {
            intent.putExtra(VpnProfileDataSource.KEY_PASSWORD, password)
        }
        runCatching {
            ContextCompat.startForegroundService(context, intent)
        }.onFailure { Log.w(TAG, "failed to start CharonVpnService", it) }
    }

    fun disconnect(context: Context) {
        val intent = Intent(context, CharonVpnService::class.java)
        intent.action = CharonVpnService.DISCONNECT_ACTION
        runCatching { context.startService(intent) }
            .onFailure { Log.w(TAG, "disconnect failed", it) }
    }
}
