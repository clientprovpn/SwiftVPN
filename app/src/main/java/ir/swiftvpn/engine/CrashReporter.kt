package ir.swiftvpn.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions to a file so failures in the :openvpn process
 * are diagnosable without a computer.
 *
 * The engine's tunnel runs in a separate process, so a crash there produces
 * only a system "keeps stopping" dialog — nothing reaches the UI's log tab.
 * Writing to a shared file means the next app launch can surface it.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_FILES = 5
    private const val BREADCRUMB = "in_progress.txt"
    private const val TOMBSTONE = "last_tombstone.txt"
    private const val TOMBSTONE_SUMMARY = "last_tombstone_summary.txt"
    private const val TOMBSTONE_TS = "tombstone_ts.txt"
    private const val MAX_TOMBSTONE_BYTES = 512 * 1024

    fun install(context: Context) {
        val dir = crashDir(context).apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(dir, thread, error) }
                .onFailure { Log.w(TAG, "could not record crash", it) }
            // Always hand back to the platform so the process still dies
            // properly rather than being left in a broken state.
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Records that something risky is about to run, so a NATIVE crash can still
     * be attributed afterwards.
     *
     * This exists because of a real blind spot: an uncaught-exception handler only
     * sees Java throwables. When the Go core faulted inside libgojni.so the
     * process was killed by a signal, no handler ran, and the app simply vanished
     * with nothing recorded — the only evidence was the vendor's own crash log.
     *
     * So before entering native code we leave a breadcrumb, and clear it on the
     * way out. A breadcrumb still present at the next launch means the previous
     * run died inside that operation, which turns an invisible disappearance into
     * a named suspect.
     */
    fun breadcrumb(context: Context, what: String?) {
        runCatching {
            // mkdirs MUST happen here too: this dir is otherwise created only
            // by write(), which runs on a JAVA crash. On a fresh install the
            // very first native fault then hit writeText on a missing dir, the
            // exception was swallowed by this runCatching, and the one piece of
            // evidence the breadcrumb exists to leave was never written.
            val dir = crashDir(context).apply { mkdirs() }
            val f = File(dir, BREADCRUMB)
            if (what == null) f.delete() else f.writeText("$what @ ${stamp()}")
        }
    }

    /** A breadcrumb left by a previous run that never finished, if any. */
    fun lastBreadcrumb(context: Context): String? = runCatching {
        File(crashDir(context), BREADCRUMB).takeIf { it.exists() }?.readText()
    }.getOrNull()

    /**
     * Pulls the system tombstone for our own most recent NATIVE crash and saves
     * it locally.
     *
     * This is the missing piece the breadcrumb only approximates: a hard
     * SIGSEGV (which is what the Xray crash is) writes NOTHING to stderr and
     * raises no Java exception, so neither the Go dump nor our handler sees
     * anything. But the Android debuggerd always writes a tombstone, and since
     * Android 11 (API 30) an app may read its OWN exit reasons — including the
     * full tombstone trace — without any permission. The trace carries the
     * fault address, signal, and the native backtrace: everything needed to
     * symbolise the crash offline.
     *
     * Call once per launch; overwrites the previous copy.
     */
    fun captureNativeTombstone(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            val nativeCrash = exits.firstOrNull {
                it.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
            } ?: return@runCatching

            val dir = crashDir(context).apply { mkdirs() }

            // DEDUP: the system keeps exit history for DAYS. Without this, the
            // same old crash was re-captured on every launch, so the crash
            // dialog kept resurfacing forever after the user had dismissed it —
            // looking exactly like "the app crashes every time it opens".
            val tsFile = File(dir, TOMBSTONE_TS)
            val capturedTs = runCatching { tsFile.readText().trim().toLong() }
                .getOrDefault(-1L)
            if (nativeCrash.timestamp == capturedTs) return@runCatching

            val trace = nativeCrash.traceInputStream?.use { ins ->
                ins.readNBytesCompat(MAX_TOMBSTONE_BYTES)
            } ?: return@runCatching
            if (trace.isEmpty()) return@runCatching

            File(dir, TOMBSTONE).writeBytes(trace)
            File(dir, TOMBSTONE_SUMMARY).writeText(
                summarizeTombstone(trace, nativeCrash.timestamp, nativeCrash.processName),
            )
            // Only AFTER a successful capture: this crash is now accounted for.
            tsFile.writeText(nativeCrash.timestamp.toString())
        }.onFailure { Log.w(TAG, "could not capture tombstone", it) }
    }

    /**
     * A human-readable header for a raw proto tombstone. The proto bytes are
     * perfect for offline symbolication but unreadable in a dialog; this pulls
     * the few tokens a user (or a quick glance) needs: signal, code, fault
     * description, fingerprint, time, process.
     */
    private fun summarizeTombstone(
        trace: ByteArray,
        timestamp: Long,
        processName: String?,
    ): String {
        val tokens = Regex("[\\x20-\\x7e]{4,}")
            .findAll(String(trace, Charsets.ISO_8859_1))
            .map { it.value }
            .toList()
        val signal = tokens.firstOrNull { it.startsWith("SIG") && it.length <= 10 }
        val code = tokens.firstOrNull {
            it.startsWith("SEGV_") || it.startsWith("BUS_") ||
                it.startsWith("ILL_") || it.startsWith("FPE_")
        }
        val fault = tokens.firstOrNull {
            it.contains("pointer dereference") || it.contains("abort message") ||
                it.contains("invalid address")
        }
        val fingerprint = tokens.firstOrNull {
            it.contains("/") && it.contains(":user/")
        }
        val when_ = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", java.util.Locale.US,
        ).format(java.util.Date(timestamp))

        return buildString {
            appendLine("Signal: ${listOfNotNull(signal, code).joinToString(" / ").ifEmpty { "native fault" }}")
            fault?.let { appendLine("Fault: $it") }
            appendLine("Time: $when_")
            processName?.let { appendLine("Process: $it") }
            fingerprint?.let { appendLine("Build: $it") }
            append("(Full tombstone attached in the diagnostic export.)")
        }
    }

    /** The saved tombstone text from the last native crash, if any. */
    fun lastTombstone(context: Context): String? = runCatching {
        File(crashDir(context), TOMBSTONE)
            .takeIf { it.exists() && it.length() > 0 }
            ?.readText()
    }.getOrNull()

    /** The readable one-screen summary of the last native crash, if any. */
    fun lastTombstoneSummary(context: Context): String? = runCatching {
        File(crashDir(context), TOMBSTONE_SUMMARY)
            .takeIf { it.exists() && it.length() > 0 }
            ?.readText()
    }.getOrNull()

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var remaining = max
        while (remaining > 0) {
            val r = read(chunk, 0, minOf(chunk.size, remaining))
            if (r < 0) break
            buf.write(chunk, 0, r)
            remaining -= r
        }
        return buf.toByteArray()
    }

    /** The most recent crash report, or null when there is none. */
    fun lastCrash(context: Context): String? {
        val file = File(crashDir(context), FILE_NAME)
        return if (file.exists()) runCatching { file.readText() }.getOrNull() else null
    }

    fun clear(context: Context) {
        val dir = crashDir(context)
        runCatching { File(dir, FILE_NAME).delete() }
        // Tombstone files too — but NOT TOMBSTONE_TS: that marker is what stops
        // the same already-dismissed crash from being re-captured next launch.
        runCatching { File(dir, TOMBSTONE).delete() }
        runCatching { File(dir, TOMBSTONE_SUMMARY).delete() }
    }

    private fun write(dir: File, thread: Thread, error: Throwable) {
        dir.mkdirs()
        val stack = StringWriter().also { sw ->
            PrintWriter(sw).use { error.printStackTrace(it) }
        }.toString()

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val report = buildString {
            appendLine("time:    $stamp")
            appendLine("process: ${android.os.Process.myPid()}")
            appendLine("thread:  ${thread.name}")
            appendLine("device:  ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine()
            append(stack)
        }

        File(dir, FILE_NAME).writeText(report)

        // Keep a small rolling history for repeated failures.
        File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(report)
        dir.listFiles { f -> f.name.startsWith("crash-") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_FILES)
            ?.forEach { it.delete() }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun crashDir(context: Context) = File(context.filesDir, "crashes")
}
