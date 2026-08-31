package ir.swiftvpn.engine

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A durable, app-wide diagnostic log the user can export and send.
 *
 * WHY THIS EXISTS SEPARATELY FROM [VpnEngine.logs]: that one lives in a
 * StateFlow, holds only the current tunnel's lines, and is wiped when the
 * process dies. Every hard bug in this app so far has been a NATIVE crash — the
 * process is killed by a signal, so an in-memory buffer evaporates exactly when
 * it would have been most useful, and no Java handler ever runs.
 *
 * So this writes to disk. Two mechanisms, deliberately:
 *
 *  * a small in-memory ring for the viewer, cheap to read and render, and
 *  * an append-only file flushed on EVERY write, so whatever was recorded a
 *    millisecond before a SIGSEGV survives.
 *
 * Flushing each line is normally wasteful. Here it is the entire point: a log
 * that buffers is a log that loses precisely the last line, which is the one
 * naming the operation that killed the process.
 *
 * The file is capped and rotated so it cannot grow without bound.
 */
object DiagnosticLog {

    private const val FILE_NAME = "diagnostic.log"
    private const val PREV_NAME = "diagnostic.prev.log"
    private const val MAX_BYTES = 512 * 1024
    private const val MEMORY_LINES = 800

    /** Category tags, so a reader can tell at a glance where a line came from. */
    const val APP = "app"
    const val OPENVPN = "ovpn"
    const val WIREGUARD = "wg"
    const val XRAY = "xray"
    const val IKEV2 = "ikev2"
    const val TEST = "test"
    const val SUB = "sub"

    private val memory = ConcurrentLinkedQueue<String>()

    @Volatile
    private var file: File? = null

    @Volatile
    private var listener: (() -> Unit)? = null

    /** Called once from Application.onCreate, in every process. */
    fun init(context: Context) {
        val dir = File(context.applicationContext.filesDir, "logs").apply { mkdirs() }
        val f = File(dir, FILE_NAME)
        rotateIfLarge(f, File(dir, PREV_NAME))
        file = f

        // Backfill the in-memory ring from what survived on disk BEFORE writing
        // this session's header. Without this the viewer only ever showed lines
        // written since process start — so after a crash (the exact moment the
        // log matters) the screen looked empty and the report seemed to "not
        // log", when really the crashed session's lines were on disk only.
        runCatching {
            if (f.exists()) {
                val tail = f.readLines().takeLast(MEMORY_LINES)
                if (tail.isNotEmpty()) memory.addAll(tail)
            }
        }

        write(
            APP,
            "=== session start · SwiftVPN ${appVersion(context)} · " +
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT}) · process ${processTag()}",
        )
    }

    /** Appends one line. Safe from any thread and any process. */
    fun write(tag: String, message: String) {
        val line = "${stamp()} [$tag] $message"

        memory.add(line)
        while (memory.size > MEMORY_LINES) memory.poll()

        // Synchronised because the tunnel runs in a second process for OpenVPN
        // and several threads write here; interleaved appends would corrupt lines.
        val f = file ?: return
        synchronized(this) {
            runCatching { f.appendText(line + "\n") }
        }
        listener?.invoke()
    }

    /** Convenience for recording a failure with its cause. */
    fun error(tag: String, message: String, error: Throwable? = null) {
        write(tag, "ERROR $message" + (error?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    /** Everything currently in memory, oldest first. */
    fun lines(): List<String> = memory.toList()

    /**
     * The full log text for export, previous session included.
     *
     * The previous session matters more than the current one after a crash: the
     * user relaunches the app to export, which starts a fresh session, so the
     * lines that explain the crash are already in the rotated file.
     */
    fun exportText(context: Context): String {
        val dir = File(context.applicationContext.filesDir, "logs")
        val current = File(dir, FILE_NAME)
        val previous = File(dir, PREV_NAME)

        return buildString {
            appendLine("SwiftVPN diagnostic log")
            appendLine("exported: ${stamp()}")
            appendLine("app:      ${appVersion(context)}")
            appendLine("device:   ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android:  ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("abi:      ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine()

            CrashReporter.lastBreadcrumb(context)?.let {
                appendLine("!! UNFINISHED OPERATION FROM A PREVIOUS RUN: $it")
                appendLine("!! (the process died during this — almost certainly a native crash)")
                appendLine()
            }
            CrashReporter.lastCrash(context)?.let {
                appendLine("--- last java crash ---")
                appendLine(it)
                appendLine()
            }

            // The system tombstone for the last native crash: readable summary
            // first, then the raw proto as base64 so it survives copy/paste
            // intact for offline symbolication.
            CrashReporter.lastTombstoneSummary(context)?.let {
                appendLine("--- last native crash (summary) ---")
                appendLine(it)
                appendLine()
            }
            runCatching {
                val raw = File(
                    context.applicationContext.filesDir, "crashes/last_tombstone.txt",
                )
                if (raw.exists() && raw.length() > 0) {
                    appendLine("--- last native tombstone (base64) ---")
                    appendLine(
                        android.util.Base64.encodeToString(
                            raw.readBytes(), android.util.Base64.NO_WRAP,
                        ),
                    )
                    appendLine()
                }
            }

            // The Go runtime's own fatal dump, if stderr was captured before a
            // native death. This is the section that names the failing function
            // when a breadcrumb only says where we were.
            val goStderr = File(dir, "go-stderr.log")
            if (goStderr.exists() && goStderr.length() > 0) {
                appendLine("--- go runtime stderr (native crash dump) ---")
                // Cap the tail: a full goroutine dump can be long, and the
                // first lines after the fatal banner are what identify the bug.
                val text = runCatching { goStderr.readText() }.getOrDefault("(unreadable)")
                appendLine(text.take(64 * 1024))
                appendLine()
            }

            if (previous.exists()) {
                appendLine("--- previous session ---")
                appendLine(runCatching { previous.readText() }.getOrDefault("(unreadable)"))
                appendLine()
            }
            appendLine("--- current session ---")
            appendLine(runCatching { current.readText() }.getOrDefault("(unreadable)"))
        }
    }

    /** Writes the export to a shareable file and returns it. */
    fun exportToFile(context: Context): File? = runCatching {
        val dir = File(context.applicationContext.cacheDir, "export").apply { mkdirs() }
        // Unique name per export: share targets (Telegram, download managers)
        // can serve a stale cached copy when the filename repeats.
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "swiftvpn-log-$stamp.txt")
        // Prune older exports so the cache does not accumulate unsent reports.
        dir.listFiles()?.forEach { if (it.name != out.name) runCatching { it.delete() } }
        out.writeText(exportText(context))
        out
    }.getOrNull()

    fun clear(context: Context) {
        memory.clear()
        val dir = File(context.applicationContext.filesDir, "logs")
        synchronized(this) {
            runCatching { File(dir, FILE_NAME).writeText("") }
            runCatching { File(dir, PREV_NAME).delete() }
            // charon's own filelog lives outside logs/ and is never rotated;
            // without this, Clear leaves the biggest section of the export
            // (the charon tail mirror) full of stale sessions.
            runCatching { File(context.applicationContext.filesDir, "charon.log").writeText("") }
        }
        listener?.invoke()
    }

    /** Set by the viewer so new lines appear without polling. */
    fun setListener(block: (() -> Unit)?) {
        listener = block
    }

    // ---------------------------------------------------------------- internals

    private fun rotateIfLarge(current: File, previous: File) {
        runCatching {
            if (current.exists() && current.length() > MAX_BYTES) {
                previous.delete()
                current.renameTo(previous)
            }
        }
    }

    private fun stamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    /** Distinguishes the UI process from the engine's `:openvpn` one. */
    private fun processTag(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.app.Application.getProcessName().substringAfter(':', "main")
        } else {
            "?"
        }

    private fun appVersion(context: Context): String = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("?")
}
