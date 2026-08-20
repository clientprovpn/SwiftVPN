package ir.swiftvpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.swiftvpn.ui.components.glassPanel
import ir.swiftvpn.R
import ir.swiftvpn.engine.DiagnosticLog
import ir.swiftvpn.ui.StatusColors

/**
 * The diagnostic log viewer — what the user reads and sends when something
 * breaks.
 *
 * Deliberately a plain monospace dump rather than a prettified list. A bug
 * report is only useful if it is the raw truth, and any formatting that hides or
 * reorders lines makes the timeline harder to read, which is the one thing the
 * log is for.
 *
 * Lines are tinted by severity so an error stands out while scrolling, and the
 * view sticks to the bottom as new lines land — the newest line is almost always
 * the interesting one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticLogScreen(
    onBack: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
) {
    // Re-read on every notification from the log. A snapshot list rather than a
    // flow because the writer is process-wide and deliberately dependency-free.
    var lines by remember { mutableStateOf(DiagnosticLog.lines()) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        DiagnosticLog.setListener { lines = DiagnosticLog.lines() }
    }

    // Follow the tail as lines arrive.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            runCatching { listState.scrollToItem(lines.lastIndex) }
        }
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(stringResource(R.string.log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onCopy) {
                        Icon(
                            Icons.Default.ContentCopy,
                            stringResource(R.string.action_copy),
                        )
                    }
                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, stringResource(R.string.log_share))
                    }
                    IconButton(
                        onClick = {
                            onClear()
                            lines = DiagnosticLog.lines()
                        }
                    ) {
                        Icon(Icons.Default.Delete, stringResource(R.string.action_clear_log))
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                text = stringResource(R.string.log_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )

            if (lines.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.log_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
                return@Column
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .glassPanel(14.dp),
                contentPadding = PaddingValues(
                    start = 10.dp, end = 10.dp, top = 6.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                itemsIndexed(lines) { _, line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = tintFor(line),
                        // Horizontal scroll instead of wrapping: a wrapped log
                        // line loses its column alignment, which is what makes a
                        // timestamped dump scannable.
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Errors red, warnings amber, state changes accented, everything else plain. */
@Composable
private fun tintFor(line: String): Color = when {
    line.contains("ERROR") -> StatusColors.error
    line.contains("failed", ignoreCase = true) ||
        line.contains("!!") -> StatusColors.connecting
    line.contains("state ") || line.contains("=== session") -> StatusColors.connected
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
