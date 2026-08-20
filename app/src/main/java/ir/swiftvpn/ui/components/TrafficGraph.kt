package ir.swiftvpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.swiftvpn.engine.VpnEngine
import ir.swiftvpn.engine.formatRate

/**
 * A 60-second traffic graph, drawn as an instrument rather than a styled card.
 *
 * Deliberately plain: no surface, no rounded container, a full box grid, and an
 * ANGULAR polyline. Traffic is spiky by nature and smoothing rounds the peaks
 * off, which loses exactly the detail the graph exists to show.
 *
 * Layout mirrors the reference the user asked for:
 *   Download  304.00  B/s                          17.1 MB/s
 *   [ grid + curve ]
 *   60 Seconds                                             0
 *
 * Values are bytes/sec, oldest first. The x-axis is fixed at
 * [VpnEngine.HISTORY_SECONDS] so the line advances right-to-left rather than
 * rescaling as samples arrive.
 */
@Composable
fun TrafficGraph(
    label: String,
    currentRate: Long,
    history: List<Long>,
    accent: Color,
    plotHeight: Int = 132,
    modifier: Modifier = Modifier,
) {
    val peak = history.maxOrNull() ?: 0L
    // Never scale below 1 kB/s, otherwise idle noise looks like heavy traffic.
    val axisMax = maxOf(peak, 1_000L)

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier.fillMaxWidth()) {
        // Header: one monospace line, current rate beside the label, peak right.
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = labelColor,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatRate(currentRate),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = labelColor,
                modifier = Modifier.weight(1f),
            )
            Text(
                // The true peak, not a bucketed value — it is the y-axis scale.
                text = formatRate(axisMax),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = labelColor,
            )
        }

        Spacer(Modifier.height(4.dp))

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(plotHeight.dp)
        ) {
            val w = size.width
            val h = size.height

            // Full box grid: 6 columns x 10 rows, solid and very light.
            val cols = 6
            val rows = 10
            for (i in 0..cols) {
                val x = w * i / cols
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
            }
            for (i in 0..rows) {
                val y = h * i / rows
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }

            if (history.size < 2) return@Canvas

            val slots = VpnEngine.HISTORY_SECONDS
            val stepX = w / (slots - 1).toFloat()
            // Right-align: newest sample sits at the right edge.
            val startIndex = slots - history.size

            fun pointAt(i: Int): Offset {
                val x = (startIndex + i) * stepX
                val ratio = (history[i].toFloat() / axisMax.toFloat()).coerceIn(0f, 1f)
                return Offset(x, h - ratio * h)
            }

            // Straight segments — the spikes are the signal.
            val line = Path().apply {
                val first = pointAt(0)
                moveTo(first.x, first.y)
                for (i in 1..history.lastIndex) {
                    val p = pointAt(i)
                    lineTo(p.x, p.y)
                }
            }

            val fill = Path().apply {
                addPath(line)
                lineTo(pointAt(history.lastIndex).x, h)
                lineTo(pointAt(0).x, h)
                close()
            }

            drawPath(
                path = fill,
                brush = Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.02f))
                ),
            )
            drawPath(path = line, color = accent, style = Stroke(width = 1.8f))
        }

        Spacer(Modifier.height(2.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${VpnEngine.HISTORY_SECONDS} Seconds",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = labelColor,
            )
            Text(
                text = "0",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = labelColor,
            )
        }
    }
}
