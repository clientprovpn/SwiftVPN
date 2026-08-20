package ir.swiftvpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ir.swiftvpn.R
import ir.swiftvpn.engine.ProfileSettings

/**
 * Full profile editor: server address, port, protocol, credentials, routing,
 * DNS and tuning. Everything writes back through VpnEngine.saveProfileSettings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSettingsScreen(
    initial: ProfileSettings,
    onBack: () -> Unit,
    onSave: (ProfileSettings) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var server by remember { mutableStateOf(initial.server) }
    var port by remember { mutableStateOf(initial.port) }
    var useUdp by remember { mutableStateOf(initial.useUdp) }
    var username by remember { mutableStateOf(initial.username) }

    var usePull by remember { mutableStateOf(initial.usePull) }
    var useLzo by remember { mutableStateOf(initial.useLzo) }
    var persistTun by remember { mutableStateOf(initial.persistTun) }

    var useDefaultRoute by remember { mutableStateOf(initial.useDefaultRoute) }
    var useDefaultRoute6 by remember { mutableStateOf(initial.useDefaultRoute6) }
    var customRoutes by remember { mutableStateOf(initial.customRoutes) }

    var overrideDns by remember { mutableStateOf(initial.overrideDns) }
    var dns1 by remember { mutableStateOf(initial.dns1) }
    var dns2 by remember { mutableStateOf(initial.dns2) }
    var searchDomain by remember { mutableStateOf(initial.searchDomain) }

    var mtu by remember { mutableStateOf(initial.tunMtu.takeIf { it > 0 }?.toString() ?: "") }
    var mssFix by remember { mutableStateOf(initial.mssFix.takeIf { it > 0 }?.toString() ?: "") }
    var timeout by remember {
        mutableStateOf(initial.connectTimeout.takeIf { it > 0 }?.toString() ?: "")
    }

    var cipher by remember { mutableStateOf(initial.cipher) }
    var auth by remember { mutableStateOf(initial.auth) }
    var dataCiphers by remember { mutableStateOf(initial.dataCiphers) }
    var tlsAuthDirection by remember { mutableStateOf(initial.tlsAuthDirection) }
    var checkRemoteCN by remember { mutableStateOf(initial.checkRemoteCN) }
    var expectTLSCert by remember { mutableStateOf(initial.expectTLSCert) }
    var remoteCN by remember { mutableStateOf(initial.remoteCN) }
    var useCustomConfig by remember { mutableStateOf(initial.useCustomConfig) }
    var customConfigOptions by remember { mutableStateOf(initial.customConfigOptions) }
    var allowLocalLAN by remember { mutableStateOf(initial.allowLocalLAN) }
    var blockUnusedAF by remember { mutableStateOf(initial.blockUnusedAF) }

    fun collect() = initial.copy(
        name = name.trim(),
        server = server.trim(),
        port = port.trim().ifBlank { "1194" },
        useUdp = useUdp,
        username = username.trim(),
        usePull = usePull,
        useLzo = useLzo,
        persistTun = persistTun,
        useDefaultRoute = useDefaultRoute,
        useDefaultRoute6 = useDefaultRoute6,
        customRoutes = customRoutes.trim(),
        overrideDns = overrideDns,
        dns1 = dns1.trim(),
        dns2 = dns2.trim(),
        searchDomain = searchDomain.trim(),
        tunMtu = mtu.toIntOrNull() ?: 0,
        mssFix = mssFix.toIntOrNull() ?: 0,
        connectTimeout = timeout.toIntOrNull() ?: 0,
        cipher = if (cipher == "default") "" else cipher,
        auth = if (auth == "default") "" else auth,
        dataCiphers = dataCiphers.trim(),
        tlsAuthDirection = if (tlsAuthDirection == "default") "" else tlsAuthDirection,
        checkRemoteCN = checkRemoteCN,
        expectTLSCert = expectTLSCert,
        remoteCN = remoteCN.trim(),
        useCustomConfig = useCustomConfig,
        customConfigOptions = customConfigOptions,
        allowLocalLAN = allowLocalLAN,
        blockUnusedAF = blockUnusedAF,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onSave(collect()) },
                        enabled = server.isNotBlank(),
                    ) {
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
            // ---------------------------------------------------------- server
            SettingsSection(stringResource(R.string.settings_section_server)) {
                Field(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.settings_name),
                )
                Field(
                    value = server,
                    onValueChange = { server = it },
                    label = stringResource(R.string.settings_server),
                    keyboardType = KeyboardType.Uri,
                    isError = server.isBlank(),
                )
                Field(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit) },
                    label = stringResource(R.string.settings_port),
                    keyboardType = KeyboardType.Number,
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_protocol),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = useUdp,
                        onClick = { useUdp = true },
                        label = { Text("UDP") },
                    )
                    FilterChip(
                        selected = !useUdp,
                        onClick = { useUdp = false },
                        label = { Text("TCP") },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Field(
                    value = timeout,
                    onValueChange = { timeout = it.filter(Char::isDigit) },
                    label = stringResource(R.string.settings_timeout),
                    keyboardType = KeyboardType.Number,
                )
            }

            // ------------------------------------------------------ credentials
            SettingsSection(stringResource(R.string.settings_section_auth)) {
                Text(
                    text = initial.authTypeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Field(
                    value = username,
                    onValueChange = { username = it },
                    label = stringResource(R.string.label_username),
                )
                Text(
                    text = stringResource(
                        if (initial.hasPassword) R.string.settings_password_saved
                        else R.string.settings_password_prompt
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---------------------------------------------------------- routing
            SettingsSection(stringResource(R.string.settings_section_routing)) {
                Toggle(
                    label = stringResource(R.string.settings_use_pull),
                    description = stringResource(R.string.settings_use_pull_desc),
                    checked = usePull,
                    onCheckedChange = { usePull = it },
                )
                Toggle(
                    label = stringResource(R.string.settings_default_route),
                    checked = useDefaultRoute,
                    onCheckedChange = { useDefaultRoute = it },
                )
                Toggle(
                    label = stringResource(R.string.settings_default_route6),
                    checked = useDefaultRoute6,
                    onCheckedChange = { useDefaultRoute6 = it },
                )
                Field(
                    value = customRoutes,
                    onValueChange = { customRoutes = it },
                    label = stringResource(R.string.settings_custom_routes),
                    placeholder = "10.0.0.0/8 192.168.1.0/24",
                )
            }

            // -------------------------------------------------------------- dns
            SettingsSection(stringResource(R.string.settings_section_dns)) {
                Toggle(
                    label = stringResource(R.string.settings_override_dns),
                    checked = overrideDns,
                    onCheckedChange = { overrideDns = it },
                )
                if (overrideDns) {
                    Field(
                        value = dns1,
                        onValueChange = { dns1 = it },
                        label = stringResource(R.string.settings_dns1),
                        keyboardType = KeyboardType.Uri,
                    )
                    Field(
                        value = dns2,
                        onValueChange = { dns2 = it },
                        label = stringResource(R.string.settings_dns2),
                        keyboardType = KeyboardType.Uri,
                    )
                    Field(
                        value = searchDomain,
                        onValueChange = { searchDomain = it },
                        label = stringResource(R.string.settings_search_domain),
                    )
                }
            }

            // ----------------------------------------------------------- tuning
            SettingsSection(stringResource(R.string.settings_section_tuning)) {
                Field(
                    value = mtu,
                    onValueChange = { mtu = it.filter(Char::isDigit) },
                    label = stringResource(R.string.settings_mtu),
                    keyboardType = KeyboardType.Number,
                    placeholder = "1500",
                )
                Field(
                    value = mssFix,
                    onValueChange = { mssFix = it.filter(Char::isDigit) },
                    label = stringResource(R.string.settings_mssfix),
                    keyboardType = KeyboardType.Number,
                )
                Toggle(
                    label = stringResource(R.string.settings_lzo),
                    checked = useLzo,
                    onCheckedChange = { useLzo = it },
                )
                Toggle(
                    label = stringResource(R.string.settings_persist_tun),
                    description = stringResource(R.string.settings_persist_tun_desc),
                    checked = persistTun,
                    onCheckedChange = { persistTun = it },
                )
            }

            // ------------------------------------------------------- encryption
            SettingsSection(stringResource(R.string.settings_section_encryption)) {
                ChoiceField(
                    label = stringResource(R.string.settings_cipher),
                    value = cipher.ifBlank { "default" },
                    options = listOf(
                        "default", "AES-128-GCM", "AES-256-GCM",
                        "AES-128-CBC", "AES-256-CBC", "CHACHA20-POLY1305",
                    ),
                    onSelect = { cipher = if (it == "default") "" else it },
                )
                Field(
                    value = dataCiphers,
                    onValueChange = { dataCiphers = it },
                    label = stringResource(R.string.settings_data_ciphers),
                    placeholder = "AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305",
                )
                ChoiceField(
                    label = stringResource(R.string.settings_auth_digest),
                    value = auth.ifBlank { "default" },
                    options = listOf("default", "SHA1", "SHA256", "SHA384", "SHA512"),
                    onSelect = { auth = if (it == "default") "" else it },
                )
            }

            // -------------------------------------------------------------- tls
            SettingsSection(stringResource(R.string.settings_section_tls)) {
                if (initial.hasTlsAuthKey) {
                    ChoiceField(
                        label = stringResource(R.string.settings_tls_auth_direction),
                        value = tlsAuthDirection.ifBlank { "default" },
                        options = listOf("default", "0", "1", "tls-crypt", "tls-crypt-v2"),
                        onSelect = {
                            tlsAuthDirection = if (it == "default") "" else it
                        },
                    )
                }
                Toggle(
                    label = stringResource(R.string.settings_check_remote_cn),
                    checked = checkRemoteCN,
                    onCheckedChange = { checkRemoteCN = it },
                )
                if (checkRemoteCN) {
                    Field(
                        value = remoteCN,
                        onValueChange = { remoteCN = it },
                        label = stringResource(R.string.settings_remote_cn),
                    )
                    Toggle(
                        label = stringResource(R.string.settings_expect_tls_cert),
                        checked = expectTLSCert,
                        onCheckedChange = { expectTLSCert = it },
                    )
                }
            }

            // --------------------------------------------------------- advanced
            SettingsSection(stringResource(R.string.settings_section_advanced)) {
                Toggle(
                    label = stringResource(R.string.settings_allow_local_lan),
                    checked = allowLocalLAN,
                    onCheckedChange = { allowLocalLAN = it },
                )
                Toggle(
                    label = stringResource(R.string.settings_block_unused_af),
                    checked = blockUnusedAF,
                    onCheckedChange = { blockUnusedAF = it },
                )
                Toggle(
                    label = stringResource(R.string.settings_use_custom_config),
                    checked = useCustomConfig,
                    onCheckedChange = { useCustomConfig = it },
                )
                if (useCustomConfig) {
                    OutlinedTextField(
                        value = customConfigOptions,
                        onValueChange = { customConfigOptions = it },
                        label = { Text(stringResource(R.string.settings_custom_options)) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        singleLine = false,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp),
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
internal fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Spacer(Modifier.height(14.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface,
                RoundedCornerShape(18.dp),
            )
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
internal fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun Toggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
