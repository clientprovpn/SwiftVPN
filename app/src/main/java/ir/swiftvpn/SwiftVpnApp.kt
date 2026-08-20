package ir.swiftvpn

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import de.blinkt.openvpn.core.ICSOpenVPNApplication
import ir.swiftvpn.engine.CrashReporter
import ir.swiftvpn.engine.DiagnosticLog
import ir.swiftvpn.engine.VpnEngine

/**
 * Extends the engine's own Application rather than android.app.Application.
 *
 * This is NOT optional. ICSOpenVPNApplication.onCreate performs engine
 * bootstrap that nothing else does:
 *
 *  - AppRestrictions.checkRestrictions() -> GlobalPreferences.setInstance().
 *    Without it, GlobalPreferences.getInstance() throws
 *    "Global preferences instance is not set" and OpenVPNService dies on
 *    startup, so no tunnel can ever come up.
 *  - StatusListener().init() — the engine's cross-process status bridge, which
 *    feeds the static VpnStatus in this process.
 *  - createNotificationChannels() for the engine's own notifications.
 *  - createFirstLaunchSetting() and LocaleHelper wiring.
 *
 * An earlier version of this app subclassed Application directly and lost all
 * of the above, which presented as "SwiftVPN keeps stopping" on connect.
 */
class SwiftVpnApp : ICSOpenVPNApplication() {

    override fun onCreate() {
        // Runs in every process, including the engine's :openvpn process, so
        // tunnel crashes are captured too.
        CrashReporter.install(this)

        // Started here, before anything else can fail, and in EVERY process —
        // the OpenVPN tunnel lives in :openvpn, and a log that missed that
        // process would be blind to exactly the failures that are hardest to
        // reproduce. Both processes append to the same file.
        DiagnosticLog.init(this)

        // If the previous run died of a NATIVE crash, pull the system
        // tombstone now — it is the only artefact that names the faulting
        // instruction, and historical exit reasons are per-process so each
        // process collects its own.
        CrashReporter.captureNativeTombstone(this)

        // Engine bootstrap first — everything below depends on it.
        super.onCreate()

        // The engine process has no UI; do not set up UI-side plumbing there.
        if (isEngineProcess()) {
            DiagnosticLog.write(DiagnosticLog.APP, "engine process ready")
            return
        }

        VpnEngine.init(this)
        DiagnosticLog.write(DiagnosticLog.APP, "ui process ready")
    }

    /**
     * True when this is a non-UI process: the engine's ":openvpn" or Xray's
     * ":xray". OpenVPNService is declared with android:process=":openvpn", the
     * Xray services with android:process=":xray", and Android runs
     * Application.onCreate in every process.
     */
    private fun isEngineProcess(): Boolean =
        currentProcessName()?.let {
            it.endsWith(":openvpn") || it.endsWith(":xray")
        } == true

    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching { getProcessName() }.getOrNull()
        }
        val pid = Process.myPid()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return runCatching {
            am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        }.getOrNull()
    }
}
