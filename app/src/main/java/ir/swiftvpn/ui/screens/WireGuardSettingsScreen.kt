package ir.swiftvpn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.amnezia.awg.config.Config
import ir.swiftvpn.R
import ir.swiftvpn.engine.WireGuardSettings

/** Interface keys the structured editor models; anything else is preserved. */
private val WG_IFACE_KEYS = setOf("privatekey", "address", "dns", "mtu")

/** Peer keys the structured editor models; anything else is preserved. */
private val WG_PEER_KEYS =
    setOf("publickey", "presharedkey", "endpoint", "allowedips", "persistentkeepalive")

/**
 * A wg-quick document split into known keys and untouched remainder lines.
 *
 * The editor regenerates the known keys from its fields, but unknown keys,
 * comments and foreign sections are carried through verbatim, so a round trip
 * never silently strips a provider's annotations or extra options.
 */
private class WgDoc {
    val iface = mutableMapOf<String, String>()
    val peer = mutableMapOf<String, String>()
    val ifaceExtras = mutableListOf<String>()
    val peerExtras = mutableListOf<String>()
    val tailExtras = mutableListOf<String>()
    var peerCount = 0
}

private fun parseWgDoc(text: String): WgDoc {
    val doc = WgDoc()
    var section = ""
    for (raw in text.lines()) {
        val line = raw.trim()
        when {
            line.startsWith("[") && line.endsWith("]") -> {
                section = line.substring(1, line.length - 1).trim().lowercase()
                if (section == "peer") doc.peerCount++
                else if (section != "interface") doc.tailExtras.add(raw)
            }
            line.isEmpty() -> Unit // regenerated on save
            '=' in line -> {
                val key = line.substringBefore('=').trim().lowercase()
                val value = line.substringAfter('=').trim()
                when {
                    section == "interface" && key in WG_IFACE_KEYS -> doc.iface[key] = value
                    section == "peer" && doc.peerCount == 1 && key in WG_PEER_KEYS ->
                        doc.peer[key] = value
                    section == "interface" -> doc.ifaceExtras.add(raw)
                    section == "peer" -> doc.peerExtras.add(raw)
                    else -> doc.tailExtras.add(raw)
                }
            }
            else -> when (section) { // comments
                "interface" -> doc.ifaceExtras.add(raw)
                "peer" -> doc.peerExtras.add(raw)
                else -> doc.tailExtras.add(raw)
            }
        }
    }
    return doc
}

/**
 * WireGuard profile editor — structured over the core's real option set.
 *
 * The wg-quick file is parsed into Interface and Peer fields (private/public
 * keys, addresses, DNS, MTU, endpoint, allowed IPs, keepalive, preshared key).
 * On save the file is regenerated from those fields, unknown lines are
 * re-attached verbatim, and the result is validated with the real WireGuard
 * parser before it leaves the screen — so a stored profile always starts.
 *
 * Multi-peer or unparseable configs fall back to editing the raw file, and a
 * toggle keeps raw mode reachable for anything the form does not model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WireGuardSettingsScreen(
    initial: WireGuardSettings,
    onBack: () -> Unit,
    onSave: (WireGuardSettings) -> Unit,
) {
    val doc = remember(initial.rawConfig) { parseWgDoc(initial.rawConfig) }
    val singlePeer = doc.peerCount <= 1 && doc.iface.containsKey("privatekey")

    var name by remember { mutableStateOf(initial.name) }
    var rawMode by remember { mutableStateOf(!singlePeer) }
    var rawConfig by remember { mutableStateOf(initial.rawConfig) }

    // ------------------------------------------------------- structured state
    var privateKey by remember { mutableStateOf(doc.iface["privatekey"] ?: "") }
    var addresses by remember { mutableStateOf(doc.iface["address"] ?: "") }
    var dns by remember { mutableStateOf(doc.iface["dns"] ?: "") }
    var mtu by remember { mutableStateOf(doc.iface["mtu"] ?: "") }
    var publicKey by remember { mutableStateOf(doc.peer["publickey"] ?: "") }
    var presharedKey by remember { mutableStateOf(doc.peer["presharedkey"] ?: "") }
    var endpoint by remember { mutableStateOf(doc.peer["endpoint"] ?: "") }
    var allowedIps by remember { mutableStateOf(doc.peer["allowedips"] ?: "") }
    var keepalive by remember { mutableStateOf(doc.peer["persistentkeepalive"] ?: "") }

    var saveError by remember { mutableStateOf(false) }

    fun assemble(): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${privateKey.trim()}")
        appendLine("Address = ${addresses.trim()}")
        if (dns.isNotBlank()) appendLine("DNS = ${dns.trim()}")
        if (mtu.isNotBlank()) appendLine("MTU = ${mtu.trim()}")
        doc.ifaceExtras.forEach { appendLine(it) }
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${publicKey.trim()}")
        if (presharedKey.isNotBlank()) appendLine("PresharedKey = ${presharedKey.trim()}")
        appendLine("Endpoint = ${endpoint.trim()}")
        appendLine("AllowedIPs = ${allowedIps.trim()}")
        if (keepalive.isNotBlank()) appendLine("PersistentKeepalive = ${keepalive.trim()}")
        doc.peerExtras.forEach { appendLine(it) }
        if (doc.tailExtras.isNotEmpty()) {
            appendLine()
            doc.tailExtras.forEach { appendLine(it) }
        }
    }

    fun save() {
        val text = if (rawMode) rawConfig else assemble()
        // Prove the result with the same parser the engine uses: a config that
        // does not parse back is refused here, before it can be stored.
        val ok = runCatching { Config.parse(text.byteInputStream()) }.getOrNull() != null
        if (!ok) {
            saveError = true
        } else {
            onSave(initial.copy(name = name.trim(), rawConfig = text))
        }
    }

    val saveEnabled = name.isNotBlank() && (
        if (rawMode) rawConfig.isNotBlank()
        else privateKey.isNotBlank() && addresses.isNotBlank() &&
            publicKey.isNotBlank() && endpoint.isNotBlank() && allowedIps.isNotBlank()
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wg_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = ::save, enabled = saveEnabled) {
                        Icon(Icons.Default.Check, stringResource(R.string.action_save))
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
        ) {
            // ------------------------------------------------------- profile
            SettingsSection(stringResource(R.string.settings_section_server)) {
                Field(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.settings_name),
                    isError = name.isBlank(),
                )
            }

            if (rawMode) {
                // ------------------------------------------ raw file fallback
                SettingsSection(stringResource(R.string.wg_section_config)) {
                    Text(
                        text = stringResource(R.string.wg_config_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = rawConfig,
                        onValueChange = { rawConfig = it },
                        // Monospace because alignment carries meaning in an
                        // ini-style document, and the keyboard must not
                        // autocorrect base64 keys.
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                        ),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        singleLine = false,
                        isError = rawConfig.isBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp),
                    )
                }
            } else {
                // -------------------------------------------------- interface
                SettingsSection(stringResource(R.string.wg_section_interface)) {
                    Field(
                        value = privateKey,
                        onValueChange = { privateKey = it },
                        label = stringResource(R.string.wg_label_private_key),
                        isError = privateKey.isBlank(),
                    )
                    Field(
                        value = addresses,
                        onValueChange = { addresses = it },
                        label = stringResource(R.string.wg_label_addresses),
                        placeholder = "10.0.0.2/32, fd00::2/128",
                        isError = addresses.isBlank(),
                    )
                    Field(
                        value = dns,
                        onValueChange = { dns = it },
                        label = stringResource(R.string.wg_label_dns),
                        placeholder = "1.1.1.1, 8.8.8.8",
                    )
                    Field(
                        value = mtu,
                        onValueChange = { mtu = it.filter(Char::isDigit) },
                        label = stringResource(R.string.wg_label_mtu),
                        keyboardType = KeyboardType.Number,
                        placeholder = "1280",
                    )
                }

                // ------------------------------------------------------ peer
                SettingsSection(stringResource(R.string.wg_section_peer)) {
                    Field(
                        value = publicKey,
                        onValueChange = { publicKey = it },
                        label = stringResource(R.string.wg_label_public_key),
                        isError = publicKey.isBlank(),
                    )
                    Field(
                        value = presharedKey,
                        onValueChange = { presharedKey = it },
                        label = stringResource(R.string.wg_label_preshared_key),
                    )
                    Field(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = stringResource(R.string.wg_label_endpoint),
                        keyboardType = KeyboardType.Uri,
                        placeholder = "server.example.com:51820",
                        isError = endpoint.isBlank(),
                    )
                    Field(
                        value = allowedIps,
                        onValueChange = { allowedIps = it },
                        label = stringResource(R.string.wg_label_allowed_ips),
                        placeholder = "0.0.0.0/0, ::/0",
                        isError = allowedIps.isBlank(),
                    )
                    Field(
                        value = keepalive,
                        onValueChange = { keepalive = it.filter(Char::isDigit) },
                        label = stringResource(R.string.wg_label_keepalive),
                        keyboardType = KeyboardType.Number,
                        placeholder = "10",
                    )
                }
            }

            if (saveError) {
                Text(
                    text = stringResource(R.string.wg_invalid_config),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                )
            }

            // Unknown keys, comments and extra sections survive the structured
            // round trip; raw mode stays reachable for anything beyond that.
            if (singlePeer) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = {
                        rawMode = !rawMode
                        if (rawMode) rawConfig = initial.rawConfig
                    }) {
                        Text(
                            text = stringResource(
                                if (rawMode) R.string.wg_edit_structured
                                else R.string.wg_edit_raw
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One label/value line. Blank values are dropped rather than shown empty. */
@Composable
private fun SummaryRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.weight(0.58f),
        )
    }
}
