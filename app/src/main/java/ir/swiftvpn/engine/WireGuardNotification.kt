package ir.swiftvpn.engine

import java.util.Locale

/**
 * Reproduces the engine's notification number formatting EXACTLY.
 *
 * This file exists because [Formatters] and the OpenVPN engine disagree, and for
 * the notification the engine has to win. `OpenVPNService.humanReadableByteCount`
 * uses a **1024** divisor with these format strings:
 *
 *     B/s  "%.0f B/s"    kB/s "%.2f kB/s"   MB/s "%.2f MB/s"   GB/s "%.2f GB/s"
 *     B    "%.0f B"      kB   "%.1f kB"     MB   "%.1f MB"     GB   "%.1f GB"
 *
 * whereas [formatRate] / [formatBytes] use 1000 for the in-app readouts. If the
 * WireGuard notification used the in-app helpers, the same traffic would read
 * ~2.4% higher in the shade than in the engine's own OpenVPN notification —
 * a visible inconsistency between the two protocols on the same screen.
 *
 * So: notification numbers come from here, in-app numbers stay with [Formatters].
 */
internal object EngineFormat {

    /**
     * Mirrors `humanReadableByteCount(bytes, speed, res)` from OpenVPNService,
     * including its exponent clamp.
     *
     * Note `log(0)` is -Infinity, which the engine's `Math.max(0, ...)` clamps to
     * exponent 0. The same guard is reproduced here rather than relying on the
     * clamp, because Kotlin's Int conversion of -Infinity is 0 anyway but the
     * intent is worth being explicit about.
     */
    fun bytes(value: Long, speed: Boolean): String {
        val unit = 1024.0
        val exp = if (value <= 0) 0 else {
            (Math.log(value.toDouble()) / Math.log(unit)).toInt().coerceIn(0, 3)
        }
        val scaled = value / Math.pow(unit, exp.toDouble())

        return if (speed) {
            when (exp) {
                0 -> String.format(Locale.US, "%.0f B/s", scaled)
                1 -> String.format(Locale.US, "%.2f kB/s", scaled)
                2 -> String.format(Locale.US, "%.2f MB/s", scaled)
                else -> String.format(Locale.US, "%.2f GB/s", scaled)
            }
        } else {
            when (exp) {
                0 -> String.format(Locale.US, "%.0f B", scaled)
                1 -> String.format(Locale.US, "%.1f kB", scaled)
                2 -> String.format(Locale.US, "%.1f MB", scaled)
                else -> String.format(Locale.US, "%.1f GB", scaled)
            }
        }
    }

    /**
     * The status line, byte-for-byte the engine's `statusline_bytecount`:
     *
     *     D/L: 11.73 MB/s (1.2 MB)  U/L: 104.09 kB/s (340.0 kB)  Uptime: 00:00:07
     *
     * Mind the ordering — it is easy to get backwards. The engine's format string
     * is `"D/L: %2$s (%1$s)  U/L: %4$s (%3$s)  Uptime: %5$s"` and it fills the
     * args as (1) total in, (2) rate in, (3) total out, (4) rate out. Resolving
     * that `%2 %1 %4 %3` shuffle puts the **rate first and the cumulative total
     * in the parentheses**, which is the opposite of the reading order the arg
     * list suggests. The live figure is the headline; the total is the aside.
     */
    fun statusLine(
        totalIn: Long,
        rateIn: Long,
        totalOut: Long,
        rateOut: Long,
        uptimeMillis: Long,
    ): String = "D/L: ${bytes(rateIn, true)} (${bytes(totalIn, false)})  " +
        "U/L: ${bytes(rateOut, true)} (${bytes(totalOut, false)})  " +
        "Uptime: ${formatUptime(uptimeMillis)}"
}
