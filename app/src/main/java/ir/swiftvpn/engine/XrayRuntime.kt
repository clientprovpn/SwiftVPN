package ir.swiftvpn.engine

import android.content.Context
import android.util.Log

/**
 * One-time bootstrap for the Xray Go runtime.
 *
 * WHY THIS EXISTS — it fixes a native crash. The bootstrap used to live inside
 * [ir.swiftvpn.xray.XrayVpnService], which meant it only ran when a tunnel was
 * started. But the server tester also starts Xray cores
 * ([XrayTester.latency] via `measureOutboundDelay`, and the probe via
 * `startLoop`), and a user who taps "test all" on a fresh launch reaches those
 * without ever having connected. The core then ran with no gomobile context and
 * no configured file reader, and died in `libgojni.so` — taking the whole process
 * with it, silently, because a native SIGSEGV bypasses the app's own crash
 * dialog entirely.
 *
 * So the bootstrap moved here and is called from both entry points. It is
 * idempotent and cheap after the first call.
 *
 * Two things have to happen before ANY core starts:
 *  * `go.Seq.setContext` — gomobile's bridge needs the app context to reach the
 *    asset manager. Without it any Go code touching mobile/asset dereferences a
 *    null Java reference from native.
 *  * `Libv2ray.initCoreEnv` — sets the asset/cert directory and installs the
 *    custom file reader. xray-core reads that env on demand; if the path is unset
 *    it falls back to a reader that has no context to work with.
 */
object XrayRuntime {

    private const val TAG = "XrayRuntime"

    @Volatile
    private var ready = false

    private val lock = Any()

    /**
     * Prepares the Go runtime. Safe to call from any thread, any number of
     * times; only the first call does work.
     *
     * Returns true when the runtime is usable. A false means the native library
     * could not be initialised at all, and callers should refuse to start a core
     * rather than crash trying.
     */
    fun ensure(context: Context): Boolean {
        if (ready) return true
        synchronized(lock) {
            if (ready) return true
            val app = context.applicationContext
            return runCatching {
                // Capture the Go runtime's death rattle BEFORE the first native
                // call. A Go fatal error (which is how libgojni dies on this
                // class of crash) prints a full goroutine dump to fd 2 and then
                // re-raises the signal — no Java exception, and logcat on a
                // production device often never surfaces it. Redirecting stderr
                // to a file turns that invisible dump into the single most
                // useful artefact for diagnosis. dup2 is kernel-level, so the
                // dump lands even as the process is being torn down.
                captureGoStderr(app)

                // Breadcrumb EACH native step. This function used to run before
                // any breadcrumb existed, so a fault in either Go call below
                // (both cross into libgojni.so) left the process dead with zero
                // evidence — the same blind spot the connect crash had.
                CrashReporter.breadcrumb(app, "xray runtime init: go.Seq.setContext")
                go.Seq.setContext(app)
                CrashReporter.breadcrumb(app, "xray runtime init: initCoreEnv")
                // Empty key: XUDP base key is only needed for xudp, which none of
                // our generated configs enable.
                libv2ray.Libv2ray.initCoreEnv(app.filesDir.absolutePath, "")
                CrashReporter.breadcrumb(app, null)
                ready = true
                true
            }.getOrElse {
                CrashReporter.breadcrumb(app, null)
                Log.w(TAG, "Xray runtime bootstrap failed", it)
                false
            }
        }
    }

    /**
     * Redirects this process's stderr (fd 2) to filesDir/logs/go-stderr.log.
     *
     * Uses android.system.Os.dup2 — no native code of ours needed. The original
     * fd 2 (logcat) is replaced, which is acceptable: everything we emit goes
     * through DiagnosticLog anyway, and the Go dump is the one stream that has
     * no other route to us.
     */
    private fun captureGoStderr(context: Context) {
        runCatching {
            val out = java.io.File(
                context.filesDir, "logs/go-stderr.log",
            ).apply { parentFile?.mkdirs() }
            val pfd = android.os.ParcelFileDescriptor.open(
                out,
                android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or
                    android.os.ParcelFileDescriptor.MODE_CREATE or
                    android.os.ParcelFileDescriptor.MODE_TRUNCATE,
            )
            // fd 2 now refers to the same open file description as pfd, so it
            // survives closing pfd here.
            android.system.Os.dup2(pfd.fileDescriptor, 2)
            pfd.close()
            Log.i(TAG, "stderr redirected to ${out.absolutePath}")
        }.onFailure { Log.w(TAG, "could not redirect stderr", it) }
    }
}
