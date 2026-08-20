package ir.swiftvpn.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import ir.swiftvpn.R
import ir.swiftvpn.engine.XraySettings
import ir.swiftvpn.engine.xray.XrayOutbound
import ir.swiftvpn.engine.xray.XrayShareLink
import ir.swiftvpn.engine.xray.XrayStream
import ir.swiftvpn.ui.GlassTokens

/**
 * Xray profile editor — structured, per the core's real option set.
 *
 * The share link is parsed into a [XrayOutbound] and every field the protocol,
 * transport and security layers actually use is editable. On save the fields
 * are serialised back into a link with [XrayShareLink.toLink] and validated by
 * parsing it again; a link that does not survive the round trip is refused, so
 * a stored profile is always connectable and always importable by other
 * clients. When the original link cannot be parsed at all, the screen falls
 * back to editing the raw link directly (same behaviour as before).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XraySettingsScreen(
    initial: XraySettings,
    onBack: () -> Unit,
    onSave: (XraySettings) -> Unit,
) {
    val parsed = remember(initial.rawLink) { XrayShareLink.parse(initial.rawLink) }

    var name by remember { mutableStateOf(initial.name) }

    // Raw-link fallback (unparseable source, or explicitly requested).
    var rawMode by remember { mutableStateOf(parsed == null) }
    var rawLink by remember { mutableStateOf(initial.rawLink) }

    // ------------------------------------------------------- structured state
    var protocol by remember { mutableStateOf(parsed?.protocol ?: "vless") }
    var address by remember { mutableStateOf(parsed?.address ?: "") }
    var port by remember { mutableStateOf(parsed?.port?.toString() ?: "") }
    var uuid by remember { mutableStateOf(parsed?.uuid ?: "") }
    var flow by remember { mutableStateOf(parsed?.flow ?: "") }
    var alterId by remember { mutableStateOf(parsed?.alterId?.toString() ?: "0") }
    var vmessCipher by remember { mutableStateOf(parsed?.security ?: "auto") }
    var password by remember { mutableStateOf(parsed?.password ?: "") }
    var method by remember { mutableStateOf(parsed?.method ?: "aes-256-gcm") }

    val st = parsed?.stream
    var network by remember { mutableStateOf(st?.network ?: "tcp") }
    var security by remember { mutableStateOf(st?.security ?: "none") }
    var sni by remember { mutableStateOf(st?.sni ?: "") }
    var fingerprint by remember { mutableStateOf(st?.fingerprint ?: "") }
    var alpn by remember { mutableStateOf(st?.alpn ?: "") }
    var host by remember { mutableStateOf(st?.host ?: "") }
    var path by remember { mutableStateOf(st?.path ?: "/") }
    var headerType by remember { mutableStateOf(st?.headerType ?: "none") }
    var serviceName by remember { mutableStateOf(st?.serviceName ?: "") }
    var publicKey by remember { mutableStateOf(st?.publicKey ?: "") }
    var shortId by remember { mutableStateOf(st?.shortId ?: "") }
    var spiderX by remember { mutableStateOf(st?.spiderX ?: "") }
    var pqv by remember { mutableStateOf(st?.pqv ?: "") }
    var xhttpMode by remember { mutableStateOf(st?.mode ?: "") }
    var extra by remember { mutableStateOf(st?.extra ?: "") }
    // hysteria2 (parsed via reused slots): obfs switch + password, insecure.
    var hy2Obfs by remember {
        mutableStateOf(parsed?.protocol == "hysteria2" && !st?.path.isNullOrBlank())
    }
    var hy2Insecure by remember { mutableStateOf(st?.headerType == "1") }

    var saveError by remember { mutableStateOf(false) }

    fun buildOutbound(): XrayOutbound = when (protocol) {
        "hysteria2" -> XrayOutbound(
            protocol = protocol,
            name = name.trim(),
            address = address.trim(),
            port = port.toIntOrNull() ?: 0,
            password = password.trim(),
            stream = XrayStream(
                network = "hysteria2",
                security = "tls",
                sni = sni.trim(),
                host = if (hy2Obfs) host.trim() else "",           // obfs password
                path = if (hy2Obfs) "salamander" else "",          // obfs type
                headerType = if (hy2Insecure) "1" else "",         // allowInsecure
            ),
        )
        else -> XrayOutbound(
            protocol = protocol,
            name = name.trim(),
            address = address.trim(),
            port = port.toIntOrNull() ?: 0,
            uuid = uuid.trim(),
            flow = if (protocol == "vless") flow.trim() else "",
            encryption = "none",
            alterId = if (protocol == "vmess") alterId.toIntOrNull() ?: 0 else 0,
            security = if (protocol == "vmess") vmessCipher else "auto",
            password = password.trim(),
            method = if (protocol == "shadowsocks") method else "",
            stream = if (protocol == "shadowsocks") XrayStream() else XrayStream(
                network = network,
                security = security,
                sni = if (security == "none") "" else sni.trim(),
                fingerprint = if (security == "none") "" else fingerprint.trim(),
                alpn = if (security == "none") "" else alpn.trim(),
                host = host.trim(),
                path = path.trim().ifBlank { "/" },
                headerType = if (network == "tcp") headerType else "none",
                serviceName = serviceName.trim(),
                publicKey = if (security == "reality") publicKey.trim() else "",
                shortId = if (security == "reality") shortId.trim() else "",
                spiderX = if (security == "reality") spiderX.trim() else "",
                pqv = if (security == "reality") pqv.trim() else "",
                mode = if (network == "xhttp" || network == "splithttp") xhttpMode else "",
                extra = if (network == "xhttp" || network == "splithttp") extra.trim() else "",
            ),
        )
    }

    fun save() {
        if (rawMode) {
            onSave(initial.copy(name = name.trim(), rawLink = rawLink.trim()))
            return
        }
        val link = XrayShareLink.toLink(buildOutbound())
        // Prove the round trip: only store a link that parses back cleanly.
        if (link == null || XrayShareLink.parse(link) == null) {
            saveError = true
        } else {
            onSave(initial.copy(name = name.trim(), rawLink = link))
        }
    }

    val hasStream = protocol != "shadowsocks" && protocol != "hysteria2"
    val saveEnabled = name.isNotBlank() && (
        if (rawMode) rawLink.isNotBlank()
        else address.isNotBlank() && (port.toIntOrNull() ?: 0) > 0
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.xray_settings_title)) },
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
                // ------------------------------------------ raw link fallback
                SettingsSection(stringResource(R.string.xray_section_link)) {
                    Text(
                        text = stringResource(R.string.xray_link_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = rawLink,
                        onValueChange = { rawLink = it },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                        ),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        singleLine = false,
                        isError = rawLink.isBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp),
                    )
                }
            } else {
                // ---------------------------------------------------- server
                SettingsSection(stringResource(R.string.xray_section_connection)) {
                    ChoiceField(
                        label = "Protocol",
                        value = protocol,
                        options = listOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2"),
                        onSelect = { protocol = it },
                    )
                    Field(
                        value = address,
                        onValueChange = { address = it },
                        label = stringResource(R.string.settings_server),
                        keyboardType = KeyboardType.Uri,
                        isError = address.isBlank(),
                    )
                    Field(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        label = stringResource(R.string.settings_port),
                        keyboardType = KeyboardType.Number,
                        isError = (port.toIntOrNull() ?: 0) <= 0,
                    )
                }

                // ----------------------------------------- protocol options
                SettingsSection(protocol.replaceFirstChar { it.uppercase() }) {
                    when (protocol) {
                        "vless" -> {
                            Field(value = uuid, onValueChange = { uuid = it },
                                label = "UUID", isError = uuid.isBlank())
                            ChoiceField(label = "Flow", value = flow.ifBlank { "none" },
                                options = listOf("none", "xtls-rprx-vision"),
                                onSelect = { flow = if (it == "none") "" else it })
                        }
                        "vmess" -> {
                            Field(value = uuid, onValueChange = { uuid = it },
                                label = "UUID", isError = uuid.isBlank())
                            Field(value = alterId,
                                onValueChange = { alterId = it.filter(Char::isDigit) },
                                label = "Alter ID", keyboardType = KeyboardType.Number)
                            ChoiceField(label = "Security (cipher)", value = vmessCipher,
                                options = listOf("auto", "aes-128-gcm", "chacha20-poly1305",
                                    "none", "zero"),
                                onSelect = { vmessCipher = it })
                        }
                        "trojan", "hysteria2" -> {
                            Field(value = password, onValueChange = { password = it },
                                label = "Password", isError = password.isBlank())
                            if (protocol == "hysteria2") {
                                Field(value = sni, onValueChange = { sni = it },
                                    label = "SNI", keyboardType = KeyboardType.Uri)
                                SwitchRow(label = "Obfuscation (salamander)",
                                    checked = hy2Obfs, onCheckedChange = { hy2Obfs = it })
                                if (hy2Obfs) {
                                    Field(value = host, onValueChange = { host = it },
                                        label = "Obfs password")
                                }
                                SwitchRow(label = "Allow insecure (skip TLS verify)",
                                    checked = hy2Insecure, onCheckedChange = { hy2Insecure = it })
                            }
                        }
                        "shadowsocks" -> {
                            ChoiceField(label = "Method", value = method,
                                options = listOf(
                                    "aes-128-gcm", "aes-256-gcm",
                                    "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
                                    "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm",
                                    "2022-blake3-chacha20-poly1305",
                                ),
                                onSelect = { method = it })
                            Field(value = password, onValueChange = { password = it },
                                label = "Password", isError = password.isBlank())
                        }
                    }
                }

                // -------------------------------------------------- transport
                if (hasStream) {
                    SettingsSection(stringResource(R.string.xray_section_transport)) {
                        ChoiceField(label = "Network", value = network,
                            options = listOf("tcp", "ws", "grpc", "xhttp", "splithttp", "http"),
                            onSelect = { network = it })
                        when (network) {
                            "ws", "http", "xhttp", "splithttp" -> {
                                Field(value = path, onValueChange = { path = it },
                                    label = "Path")
                                Field(value = host, onValueChange = { host = it },
                                    label = "Host", keyboardType = KeyboardType.Uri)
                            }
                            "grpc" -> Field(value = serviceName,
                                onValueChange = { serviceName = it },
                                label = "Service name")
                            "tcp" -> ChoiceField(label = "Header type", value = headerType,
                                options = listOf("none", "http"),
                                onSelect = { headerType = it })
                        }
                        if (network == "xhttp" || network == "splithttp") {
                            ChoiceField(label = "Mode",
                                value = xhttpMode.ifBlank { "auto" },
                                options = listOf("auto", "packet-up", "stream-up", "stream-one"),
                                onSelect = { xhttpMode = if (it == "auto") "" else it })
                            Field(value = extra, onValueChange = { extra = it },
                                label = "Extra (JSON, optional)")
                        }
                    }

                    // ------------------------------------------------ security
                    SettingsSection(stringResource(R.string.xray_section_security)) {
                        ChoiceField(label = "Security", value = security,
                            options = listOf("none", "tls", "reality"),
                            onSelect = { security = it })
                        if (security != "none") {
                            Field(value = sni, onValueChange = { sni = it },
                                label = "SNI", keyboardType = KeyboardType.Uri)
                            ChoiceField(label = "Fingerprint (uTLS)",
                                value = fingerprint.ifBlank { "none" },
                                options = listOf("none", "chrome", "firefox", "safari",
                                    "ios", "android", "edge", "random", "randomized"),
                                onSelect = { fingerprint = if (it == "none") "" else it })
                            ChoiceField(label = "ALPN", value = alpn.ifBlank { "none" },
                                options = listOf("none", "h2", "http/1.1", "h2,http/1.1", "h3"),
                                onSelect = { alpn = if (it == "none") "" else it })
                        }
                        if (security == "reality") {
                            Field(value = publicKey, onValueChange = { publicKey = it },
                                label = "Public key", isError = publicKey.isBlank())
                            Field(value = shortId, onValueChange = { shortId = it },
                                label = "Short ID")
                            Field(value = spiderX, onValueChange = { spiderX = it },
                                label = "Spider X")
                            Field(value = pqv, onValueChange = { pqv = it },
                                label = "PQ verify seed (optional)")
                        }
                    }
                }

                if (saveError) {
                    Text(
                        text = stringResource(R.string.xray_invalid_link),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                    )
                }
            }

            // A parseable link can still hold keys this editor does not model;
            // raw mode stays reachable as the escape hatch.
            if (parsed != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = {
                        rawMode = !rawMode
                        if (rawMode) rawLink = initial.rawLink
                    }) {
                        Text(
                            text = stringResource(
                                if (rawMode) R.string.xray_edit_structured
                                else R.string.xray_edit_raw
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

/**
 * Read-only field that opens a dropdown of fixed choices. The transparent
 * overlay takes the clicks because a readOnly text field swallows them.
 */
@Composable
internal fun ChoiceField(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            singleLine = true,
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable { open = true },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = GlassTokens.menuColor(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, GlassTokens.cardBorderBrush()),
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        onSelect(opt)
                        open = false
                    },
                )
            }
        }
    }
}

/** Labelled switch row, local to this screen. */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
