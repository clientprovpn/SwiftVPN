package ir.swiftvpn.engine

import java.util.Locale

/**
 * Formats a byte-per-second rate the way the reference app does:
 * "1.49 kB/s", "12.3 MB/s".
 */
fun formatRate(bytesPerSec: Long): String {
    val v = bytesPerSec.toDouble()
    return when {
        v < 1_000 -> String.format(Locale.US, "%.2f  B/s", v)
        v < 1_000_000 -> String.format(Locale.US, "%.2f kB/s", v / 1_000)
        v < 1_000_000_000 -> String.format(Locale.US, "%.2f MB/s", v / 1_000_000)
        else -> String.format(Locale.US, "%.2f GB/s", v / 1_000_000_000)
    }
}

/** Formats a cumulative byte total: "1.2 MB", "840 kB". */
fun formatBytes(bytes: Long): String {
    val v = bytes.toDouble()
    return when {
        v < 1_000 -> "${bytes} B"
        v < 1_000_000 -> String.format(Locale.US, "%.1f kB", v / 1_000)
        v < 1_000_000_000 -> String.format(Locale.US, "%.1f MB", v / 1_000_000)
        else -> String.format(Locale.US, "%.2f GB", v / 1_000_000_000)
    }
}

/** Formats an elapsed duration as HH:MM:SS, matching the Uptime field. */
fun formatUptime(millis: Long): String {
    val total = millis / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}

/** Axis label for a graph whose peak is [peak] bytes/sec. */
fun formatAxisMax(peak: Long): String = when {
    peak < 1_000 -> "1 kB/s"
    peak < 1_000_000 -> "${(peak / 1_000).coerceAtLeast(1)} kB/s"
    else -> String.format(Locale.US, "%.1f MB/s", peak / 1_000_000.0)
}
