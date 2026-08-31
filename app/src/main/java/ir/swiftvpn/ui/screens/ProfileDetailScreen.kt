package ir.swiftvpn.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.swiftvpn.R
import ir.swiftvpn.engine.LogLine
import ir.swiftvpn.engine.Profile
import ir.swiftvpn.engine.TrafficStats
import ir.swiftvpn.engine.TunnelInfo
import ir.swiftvpn.engine.UsageHistoryStore
import ir.swiftvpn.engine.VpnState
import ir.swiftvpn.engine.formatBytes
import ir.swiftvpn.engine.formatRate
import ir.swiftvpn.engine.formatUptime
import ir.swiftvpn.ui.components.glassPanel
import ir.swiftvpn.ui.StatusColors
import ir.swiftvpn.ui.components.TrafficGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DetailTab(val labelRes: Int) {
    STATUS(R.string.tab_status),
    ROUTING(R.string.tab_routing),
    LOG(R.string.tab_log),
    USAGE(R.string.tab_usage),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileDetailScreen(
    profile: Profile,
    vpnState: VpnState,
    stateMessage: String?,
    traffic: TrafficStats,
    tunnelInfo: TunnelInfo,
    logs: List<LogLine>,
    connectedSince: Long?,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onOpenSettings: () -> Unit,
    onDelete: () -> Unit,
    onClearLog: () -> Unit,
    onShowQr: () -> Unit,
) {
    // The pager owns the current tab: taps animate the pager, swipes between
    // pages move the tab indicator. One source of truth, no two-way sync bugs.
    val pagerState = rememberPagerState(pageCount = { DetailTab.entries.size })
    val pagerScope = rememberCoroutineScope()

    // One ticker drives every elapsed-time readout, so the uptime advances even
    // while traffic is idle.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedSince) {
        while (connectedSince != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    // Xray shares its share link, WireGuard its raw config —
                    // both as QR plus a copyable text, so only OpenVPN (which
                    // has no self-contained text format here) hides the button.
                    if (profile.protocol != ir.swiftvpn.engine.Protocol.OPENVPN) {
                        IconButton(onClick = onShowQr) {
                            Icon(
                                Icons.Default.QrCode,
                                stringResource(R.string.action_show_qr),
                            )
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, stringResource(R.string.action_settings))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, stringResource(R.string.action_delete))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
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
            StatusStrip(
                profile = profile,
                vpnState = vpnState,
                statusText = stateLabel(vpnState, stateMessage),
                onToggle = onToggle,
                modifier = Modifier.padding(horizontal = 14.dp),
            )

            Spacer(Modifier.height(10.dp))

            SecondaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Color.Transparent,
            ) {
                DetailTab.entries.forEachIndexed { index, t ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            pagerScope.launch { pagerState.animateScrollToPage(index) }
                        },
                        text = {
                            Text(
                                stringResource(t.labelRes),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                    )
                }
            }

            // Swipeable pages. Each page fills the pager; pages with their own
            // vertical scrolling (Routing/Usage/Log) nest fine because the
            // pager only claims horizontal drags.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (DetailTab.entries[page]) {
                    DetailTab.STATUS -> StatusTab(profile, tunnelInfo, traffic)
                    DetailTab.ROUTING -> RoutingTab(profile, tunnelInfo)
                    DetailTab.LOG -> LogTab(logs, onClearLog)
                    DetailTab.USAGE -> UsageTab(profile, traffic, connectedSince, now)
                }
            }
        }
    }
}

/**
 * Compact header: name and endpoint on the left, and a state pill on the right
 * that IS the connect/disconnect control.
 *
 * Making the pill the button means the thing that reports the state is also the
 * thing that changes it, which is why there is no separate button anywhere on
 * this screen. Live rates and uptime deliberately live in the tabs below rather
 * than being repeated here.
 */
@Composable
private fun StatusStrip(
    profile: Profile,
    vpnState: VpnState,
    statusText: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent by animateColorAsState(
        targetValue = when (vpnState) {
            VpnState.CONNECTED -> StatusColors.connected
            VpnState.CONNECTING -> StatusColors.connecting
            VpnState.AUTH_FAILED, VpnState.NO_NETWORK -> StatusColors.error
            VpnState.PAUSED -> StatusColors.connecting
            else -> StatusColors.idle
        },
        animationSpec = tween(350),
        label = "stripAccent",
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(90),
        label = "pillPress",
    )

    Row(
        modifier
            .fillMaxWidth()
            .glassPanel(18.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = profile.endpoint,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        Spacer(Modifier.width(10.dp))

        // The tappable state pill. clip() precedes clickable() so the ripple is
        // bounded to the rounded shape instead of flashing a square halo, and
        // heightIn keeps the touch target reachable despite the compact look.
        Box(
            Modifier
                .scale(pressScale)
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(50))
                .background(accent.copy(alpha = 0.16f))
                .clickable(
                    interactionSource = interaction,
                    indication = ripple(color = accent),
                    onClick = onToggle,
                )
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun stateLabel(vpnState: VpnState, stateMessage: String?): String = when (vpnState) {
    VpnState.CONNECTED -> stringResource(R.string.state_connected)
    VpnState.CONNECTING -> stateMessage?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.state_connecting)
    VpnState.PAUSED -> stringResource(R.string.state_paused)
    VpnState.AUTH_FAILED -> stringResource(R.string.state_auth_failed)
    VpnState.NO_NETWORK -> stringResource(R.string.state_no_network)
    VpnState.WAITING_FOR_INPUT -> stringResource(R.string.state_waiting_input)
    else -> stringResource(R.string.state_disconnected)
}

/**
 * The speed-meter tab. Deliberately NOT scrollable: both graphs are sized to
 * fit one screen, which is the whole point of the compact header above.
 */
@Composable
private fun StatusTab(
    profile: Profile,
    info: TunnelInfo,
    traffic: TrafficStats,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        PlainLine(stringResource(R.string.label_proxy), info.proxy ?: "")
        PlainLine(
            stringResource(R.string.label_remote_server),
            info.remoteServer ?: profile.endpoint,
        )

        Spacer(Modifier.height(10.dp))

        TrafficGraph(
            label = stringResource(R.string.label_download),
            currentRate = traffic.downBytesPerSec,
            history = traffic.downHistory,
            accent = StatusColors.download,
        )
        Spacer(Modifier.height(14.dp))
        TrafficGraph(
            label = stringResource(R.string.label_upload),
            currentRate = traffic.upBytesPerSec,
            history = traffic.upHistory,
            accent = StatusColors.upload,
        )
    }
}

/** A plain "Label: value" line, matching the graph headers' register. */
@Composable
private fun PlainLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RoutingTab(profile: Profile, info: TunnelInfo) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        InfoCard {
            InfoRow(
                stringResource(R.string.label_remote_server),
                info.remoteServer ?: profile.endpoint,
            )
            InfoRow(stringResource(R.string.label_proxy), info.proxy ?: "—")
            InfoRow(stringResource(R.string.label_local_ipv4), info.localIPv4 ?: "—")
            InfoRow(stringResource(R.string.label_local_ipv6), info.localIPv6 ?: "—")
            InfoRow(stringResource(R.string.label_mtu), info.mtu?.toString() ?: "—", last = true)
        }

        Spacer(Modifier.height(10.dp))
        SectionHeader(stringResource(R.string.label_dns))
        InfoCard {
            if (info.dnsServers.isEmpty()) {
                InfoRow("", "—", last = true)
            } else {
                info.dnsServers.forEachIndexed { i, dns ->
                    InfoRow("", dns, last = i == info.dnsServers.lastIndex)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        SectionHeader(stringResource(R.string.label_routes))
        InfoCard {
            if (info.routes.isEmpty()) {
                InfoRow("", "—", last = true)
            } else {
                info.routes.forEachIndexed { i, route ->
                    InfoRow("", route, last = i == info.routes.lastIndex)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LogTab(logs: List<LogLine>, onClear: () -> Unit) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = stringResource(R.string.action_clear_log),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onClear),
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            reverseLayout = true,
        ) {
            items(logs.reversed()) { line ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(
                        text = formatter.format(Date(line.timestamp)),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = line.message,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        color = if (line.level == -2)
                            StatusColors.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageTab(profile: Profile, traffic: TrafficStats, connectedSince: Long?, now: Long) {
    val context = LocalContext.current
    // Recomputed whenever the live counters move (about once a second while
    // connected), so the historical totals pick up the running session too.
    val totals = remember(traffic.bytesIn, traffic.bytesOut) {
        UsageHistoryStore.totals(context, profile.uuid)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigStat(
                label = stringResource(R.string.label_downloaded),
                value = formatBytes(traffic.bytesIn),
                accent = StatusColors.download,
                modifier = Modifier.weight(1f),
            )
            BigStat(
                label = stringResource(R.string.label_uploaded),
                value = formatBytes(traffic.bytesOut),
                accent = StatusColors.upload,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))

        InfoCard {
            InfoRow(
                stringResource(R.string.label_uptime),
                connectedSince?.let { formatUptime(now - it) } ?: "00:00:00",
            )
            InfoRow(stringResource(R.string.label_peak_down), formatRate(traffic.peakDown))
            InfoRow(
                stringResource(R.string.label_peak_up),
                formatRate(traffic.peakUp),
                last = true,
            )
        }

        Spacer(Modifier.height(10.dp))
        SectionHeader(stringResource(R.string.usage_history))
        InfoCard {
            InfoRow(
                stringResource(R.string.usage_today),
                usageTotalsText(totals.dayIn, totals.dayOut),
            )
            InfoRow(
                stringResource(R.string.usage_this_week),
                usageTotalsText(totals.weekIn, totals.weekOut),
            )
            InfoRow(
                stringResource(R.string.usage_this_month),
                usageTotalsText(totals.monthIn, totals.monthOut),
                last = true,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** "↓ 1.2 GB  ↑ 300 MB" — one compact line per reporting window. */
private fun usageTotalsText(bytesIn: Long, bytesOut: Long): String =
    "↓ ${formatBytes(bytesIn)}   ↑ ${formatBytes(bytesOut)}"

@Composable
private fun BigStat(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .glassPanel(16.dp)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(accent, CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Groups related rows into one rounded panel. */
@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .glassPanel(16.dp)
            .padding(horizontal = 14.dp, vertical = 2.dp)
    ) {
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String, last: Boolean = false) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (label.isNotEmpty()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
        }
        if (!last) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 5.dp),
    )
}
