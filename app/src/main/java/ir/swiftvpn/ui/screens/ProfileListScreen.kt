package ir.swiftvpn.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.swiftvpn.R
import ir.swiftvpn.engine.Profile
import ir.swiftvpn.engine.Protocol
import ir.swiftvpn.engine.Subscription
import ir.swiftvpn.engine.ThemeMode
import ir.swiftvpn.engine.VpnState
import ir.swiftvpn.ui.GlassTokens
import ir.swiftvpn.ui.components.glassPanel
import ir.swiftvpn.ui.StatusColors
import ir.swiftvpn.ui.components.ConnectControl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileListScreen(
    profiles: List<Profile>,
    /** Per-profile state, so a row can show connecting the instant it is tapped. */
    stateFor: (String) -> VpnState,
    themeMode: ThemeMode,
    protocolFilter: Protocol?,
    availableProtocols: List<Protocol>,
    subscriptions: List<Subscription>,
    groupFilter: String?,
    onSetGroupFilter: (String?) -> Unit,
    latency: Map<String, Long?>,
    testing: Set<String>,
    selected: Set<String>,
    onToggleProfile: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onToggleFavourite: (String) -> Unit,
    onSetProtocolFilter: (Protocol?) -> Unit,
    onCycleTheme: () -> Unit,
    /** "+" menu actions (previously one onImport that opened a dialog). */
    onScanQr: () -> Unit,
    onImportClipboard: () -> Unit,
    onImportFile: () -> Unit,
    onAddManual: (String) -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenLog: () -> Unit,
    onBackup: () -> Unit,
    /** Global "real delay" test for every currently visible profile. */
    onTestDelay: () -> Unit,
    onToggleSelected: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    // Selection mode is derived, not a separate flag: it exists exactly while
    // something is ticked, so the mode cannot get stuck on with nothing selected.
    val selecting = selected.isNotEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selecting) stringResource(R.string.selected_count, selected.size)
                        else stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = onClearSelection) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_cancel),
                            )
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = onSelectAll) {
                            Icon(
                                Icons.Default.SelectAll,
                                contentDescription = stringResource(R.string.action_select_all),
                            )
                        }
                        IconButton(onClick = onDeleteSelected) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_delete_selected),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                    // Exclave-style top bar: "+" opens a linear glass menu
                    // (scan / clipboard / file / manual), the overflow holds
                    // the rest.
                    ir.swiftvpn.ui.components.AddProfileMenu(
                        onScanQr = onScanQr,
                        onImportClipboard = onImportClipboard,
                        onImportFile = onImportFile,
                        onAddManual = onAddManual,
                    )
                    // One labelled menu instead of a row of cryptic icons —
                    // each entry shows icon + name so first-time users know
                    // what every option does.
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.action_menu),
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            containerColor = GlassTokens.menuColor(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, GlassTokens.cardBorderBrush()),
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_test_delay)) },
                                leadingIcon = { Icon(Icons.Default.Speed, null) },
                                onClick = { menuOpen = false; onTestDelay() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_backup)) },
                                leadingIcon = { Icon(Icons.Default.Save, null) },
                                onClick = { menuOpen = false; onBackup() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_subscriptions)) },
                                leadingIcon = { Icon(Icons.Default.CloudDownload, null) },
                                onClick = { menuOpen = false; onOpenSubscriptions() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_log)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ListAlt, null) },
                                onClick = { menuOpen = false; onOpenLog() },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (themeMode == ThemeMode.DARK) R.string.action_theme_light
                                            else R.string.action_theme_dark,
                                        ),
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = when (themeMode) {
                                            ThemeMode.DARK -> Icons.Default.LightMode
                                            else -> Icons.Default.DarkMode
                                        },
                                        contentDescription = null,
                                    )
                                },
                                onClick = { menuOpen = false; onCycleTheme() },
                            )
                        }
                    }
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
            // The chip row stays visible even when the filtered list is empty —
            // otherwise filtering down to a protocol with no servers would strand
            // the user on an empty screen with no way to clear the filter.
            FilterChipRow(
                protocolFilter = protocolFilter,
                availableProtocols = availableProtocols,
                subscriptions = subscriptions,
                groupFilter = groupFilter,
                onSetProtocolFilter = onSetProtocolFilter,
                onSetGroupFilter = onSetGroupFilter,
            )

            if (profiles.isEmpty()) {
                EmptyState(
                    onImport = onImportClipboard,
                    filtered = protocolFilter != null || groupFilter != null,
                    onClearFilter = {
                        onSetProtocolFilter(null)
                        onSetGroupFilter(null)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 14.dp, end = 14.dp, top = 4.dp, bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(profiles, key = { it.uuid }) { profile ->
                    ProfileRow(
                        profile = profile,
                        vpnState = stateFor(profile.uuid),
                        latencyMs = latency[profile.uuid],
                        latencyTested = latency.containsKey(profile.uuid),
                        isTesting = profile.uuid in testing,
                        selecting = selecting,
                        isSelected = profile.uuid in selected,
                        onToggle = { onToggleProfile(profile.uuid) },
                        // While selecting, a plain tap toggles the tick instead of
                        // opening the profile.
                        onOpen = {
                            if (selecting) onToggleSelected(profile.uuid)
                            else onOpenProfile(profile.uuid)
                        },
                        onLongPress = { onToggleSelected(profile.uuid) },
                        onToggleFavourite = { onToggleFavourite(profile.uuid) },
                    )
                }
            }
        }
    }
}

/**
 * The filter/sort chip row, modelled on VPN Client Pro's `[Name ▾] [Type ▾]`.
 *
 * Each chip shows its ACTIVE value in the label rather than a static title, so a
 * live filter is visible at a glance instead of hiding inside a menu — which is
 * the whole reason the pattern works.
 */
@Composable
private fun FilterChipRow(
    protocolFilter: Protocol?,
    availableProtocols: List<Protocol>,
    subscriptions: List<Subscription>,
    groupFilter: String?,
    onSetProtocolFilter: (Protocol?) -> Unit,
    onSetGroupFilter: (String?) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // One menu owns the whole categorisation: protocol types first (each
        // showing only hand-added profiles), then every subscription as its own
        // separate space below a divider.
        val activeSub = subscriptions.firstOrNull { it.id == groupFilter }
        DropdownChip(
            label = stringResource(
                R.string.filter_type,
                activeSub?.name
                    ?: protocolFilter?.label
                    ?: stringResource(R.string.filter_all),
            ),
            leadingIcon = if (activeSub != null) Icons.Default.Cloud else null,
            active = protocolFilter != null || groupFilter != null,
        ) { dismiss ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_all)) },
                onClick = {
                    onSetProtocolFilter(null)
                    onSetGroupFilter(null)
                    dismiss()
                },
            )
            // Only protocols that actually have hand-added profiles. Offering a
            // filter that can only ever produce an empty list is a dead end.
            availableProtocols.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.label) },
                    onClick = { onSetProtocolFilter(p); dismiss() },
                )
            }
            if (subscriptions.isNotEmpty()) {
                HorizontalDivider(
                    Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                subscriptions.forEach { sub ->
                    DropdownMenuItem(
                        text = { Text(sub.name, maxLines = 1) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Cloud,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = { onSetGroupFilter(sub.id); dismiss() },
                    )
                }
            }
        }
    }
}

/** A chip that opens a dropdown anchored to itself. */
@Composable
private fun DropdownChip(
    label: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    active: Boolean = false,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        // Outlined while idle, filled once a filter is applied — the same
        // "something is on" signal the reference app gives with its accent border.
        val border = if (active || open) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }
        Row(
            Modifier
                .background(
                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    else Color.Transparent,
                    RoundedCornerShape(10.dp),
                )
                .border(1.dp, border, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.let {
                Icon(
                    it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = GlassTokens.menuColor(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, GlassTokens.cardBorderBrush()),
        ) {
            content { open = false }
        }
    }
}

/**
 * One profile row.
 *
 * The connected row is lifted with an accent border and a pulsing status dot so
 * it is obvious which tunnel is live without reading any text.
 */
@Composable
private fun ProfileRow(
    profile: Profile,
    vpnState: VpnState,
    latencyMs: Long?,
    latencyTested: Boolean,
    isTesting: Boolean,
    selecting: Boolean,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val accent = when (vpnState) {
        VpnState.CONNECTED -> StatusColors.connected
        VpnState.CONNECTING -> StatusColors.connecting
        VpnState.AUTH_FAILED, VpnState.NO_NETWORK -> StatusColors.error
        else -> StatusColors.idle
    }
    val border by animateColorAsState(
        targetValue = if (vpnState.isActive) accent.copy(alpha = 0.55f) else Color.Transparent,
        animationSpec = tween(350),
        label = "border",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .glassPanel(18.dp)
            .border(
                1.dp,
                if (isSelected) MaterialTheme.colorScheme.primary else border,
                RoundedCornerShape(18.dp),
            )
            // clip BEFORE the click so the ripple follows the rounded shape.
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onLongPress,
            )
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(vpnState = vpnState, accent = accent)

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                // Endpoint on the left, latency right-aligned on the same line —
                // the layout the reference app uses, which keeps the row two lines
                // tall instead of three.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.endpoint,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (latencyTested || isTesting) {
                        Spacer(Modifier.width(8.dp))
                        LatencyBadge(
                            latencyMs = latencyMs,
                            testing = isTesting,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                // v2rayNG-style protocol line under the address: a plain coloured
                // text ("VLESS / ws / tls") instead of the old chip badge.
                Text(
                    text = protocolLine(profile),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = ProtocolTint,
                    maxLines = 1,
                )
            }

            IconButton(
                onClick = onToggleFavourite,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (profile.isFavourite)
                        Icons.Default.Star else Icons.Outlined.StarOutline,
                    contentDescription = stringResource(R.string.action_favourite),
                    tint = if (profile.isFavourite)
                        StatusColors.connecting
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp),
                )
            }

            ConnectControl(
                vpnState = vpnState,
                onClick = onToggle,
                size = 42.dp,
            )
        }
    }
}

/**
 * Latency readout, e.g. a green `728ms`.
 *
 * Colour-coded by band because a bare number means little on its own — green
 * under 300ms, amber under 800, red beyond, and a plain dash when the server did
 * not answer at all. Monospace so digits do not shift the layout as they change.
 */
@Composable
private fun LatencyBadge(latencyMs: Long?, testing: Boolean) {
    if (testing) {
        CircularProgressIndicator(
            strokeWidth = 1.5.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
        return
    }
    val (text, tint) = when {
        // v2rayNG convention: a failed test shows as a red -1ms, not a dash.
        latencyMs == null -> "-1ms" to StatusColors.error
        latencyMs < 300 -> "${latencyMs}ms" to StatusColors.connected
        latencyMs < 800 -> "${latencyMs}ms" to StatusColors.connecting
        else -> "${latencyMs}ms" to StatusColors.error
    }
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.5.sp,
        color = tint,
        maxLines = 1,
    )
}

/**
 * The v2rayNG-style protocol line: "VLESS / ws / tls", "HYSTERIA2 / tls",
 * "WIREGUARD", "OPENVPN". Xray's `authTypeLabel` carries "proto · tls · net",
 * which we re-order into v2rayNG's PROTO / net / security order; a network that
 * just repeats the protocol (hysteria2) is dropped as noise.
 */
private fun protocolLine(profile: Profile): String = when (profile.protocol) {
    Protocol.XRAY -> {
        val parts = profile.authTypeLabel.split(" · ")
        val proto = parts.firstOrNull().orEmpty()
        val security = if (parts.size == 3) parts[1] else ""
        val network = parts.lastOrNull().orEmpty()
        buildList {
            add(proto.uppercase())
            if (network.isNotBlank() && !network.equals(proto, ignoreCase = true)) add(network)
            if (security.isNotBlank()) add(security)
        }.joinToString(" / ")
    }
    Protocol.WIREGUARD -> "WIREGUARD"
    Protocol.OPENVPN -> "OPENVPN"
    Protocol.IKEV2 -> "IKEV2"
}

/** The warm orange v2rayNG uses for its protocol line. */
private val ProtocolTint = Color(0xFFFF9800)

/** A status dot that only pulses while a connection is being established. */
@Composable
private fun StatusDot(vpnState: VpnState, accent: Color) {
    val pulse = rememberInfiniteTransition(label = "dotPulse")
    val haloAlpha by pulse.animateFloat(
        initialValue = if (vpnState == VpnState.CONNECTING) 0.10f else 0f,
        targetValue = if (vpnState == VpnState.CONNECTING) 0.45f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "haloAlpha",
    )

    Box(contentAlignment = Alignment.Center) {
        if (vpnState.isActive) {
            Box(
                Modifier
                    .size(22.dp)
                    .background(
                        accent.copy(
                            alpha = if (vpnState == VpnState.CONNECTING) haloAlpha else 0.18f
                        ),
                        CircleShape,
                    )
            )
        }
        Box(
            Modifier
                .size(9.dp)
                .background(
                    if (vpnState.isActive) accent
                    else MaterialTheme.colorScheme.outline,
                    CircleShape,
                )
        )
    }
}

@Composable
private fun EmptyState(
    onImport: () -> Unit,
    filtered: Boolean,
    onClearFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(
                if (filtered) R.string.no_match_title else R.string.no_profiles_title
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (filtered) R.string.no_match_body else R.string.no_profiles_body
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        // Offer the way OUT of the filter here, not another import button: with a
        // filter active the list is empty because of a choice the user made, and
        // importing would not change what they see.
        if (filtered) {
            ExtendedFloatingActionButton(
                onClick = onClearFilter,
                icon = { Icon(Icons.Default.Clear, null) },
                text = { Text(stringResource(R.string.action_clear_filter)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            ExtendedFloatingActionButton(
                onClick = onImport,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.action_import)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
