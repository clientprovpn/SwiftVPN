package ir.swiftvpn.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.swiftvpn.R
import ir.swiftvpn.engine.Ikev2Engine
import ir.swiftvpn.engine.Ikev2Profile
import org.strongswan.android.logic.TrustedCertificateManager
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * IKEv2 profile editor — full coverage of the strongSwan core's options:
 * gateway/port, VPN type (EAP, certificate, mixed), credentials, CA and client
 * certificates, identities, proposals, MTU/keepalive/DNS and the revocation
 * and signature flags. Saves through the strongSwan profile store, the same
 * database the tunnel service reads, so what is saved is what connects.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Ikev2SettingsScreen(
    initial: Ikev2Profile,
    onBack: () -> Unit,
    onSave: (Ikev2Profile) -> Unit,
    /** Launches the system KeyChain picker; result is the chosen alias. */
    onPickClientCert: ((String?) -> Unit) -> Unit,
) {
    val context = LocalContext.current

    var name by remember { mutableStateOf(initial.name) }
    var gateway by remember { mutableStateOf(initial.gateway) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var vpnType by remember { mutableStateOf(initial.vpnType) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var caAlias by remember { mutableStateOf(initial.caAlias) }
    var userCertAlias by remember { mutableStateOf(initial.userCertAlias) }
    var localId by remember { mutableStateOf(initial.localId) }
    var remoteId by remember { mutableStateOf(initial.remoteId) }
    var mtu by remember { mutableStateOf(initial.mtu.takeIf { it > 0 }?.toString() ?: "") }
    var keepalive by remember {
        mutableStateOf(initial.natKeepalive.takeIf { it > 0 }?.toString() ?: "")
    }
    var ikeProposal by remember { mutableStateOf(initial.ikeProposal) }
    var espProposal by remember { mutableStateOf(initial.espProposal) }
    var eapType by remember { mutableStateOf(initial.eapType) }
    var dns by remember { mutableStateOf(initial.dnsServers) }
    var suppressCertReqs by remember { mutableStateOf(initial.suppressCertReqs) }
    var disableCrl by remember { mutableStateOf(initial.disableCrl) }
    var disableOcsp by remember { mutableStateOf(initial.disableOcsp) }
    var strictRevocation by remember { mutableStateOf(initial.strictRevocation) }
    var rsaPss by remember { mutableStateOf(initial.rsaPss) }
    var ipv6 by remember { mutableStateOf(initial.ipv6Transport) }

    var caMessage by remember { mutableStateOf<Int?>(null) }

    // Local CA imports, refreshed after every successful import.
    var localCAs by remember {
        mutableStateOf(
            runCatching {
                Ikev2Engine.init(context)
                TrustedCertificateManager.getInstance().load()
                    .getCACertificates(
                        TrustedCertificateManager.TrustedCertificateSource.LOCAL,
                    ).keys.toList()
            }.getOrDefault(emptyList()),
        )
    }

    val caPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val imported = runCatching {
            val cert = context.contentResolver.openInputStream(uri)?.use { input ->
                CertificateFactory.getInstance("X.509").generateCertificate(input)
            } as? X509Certificate ?: return@runCatching null
            val store = KeyStore.getInstance("LocalCertificateStore")
            store.load(null, null)
            store.setCertificateEntry(null, cert)
            TrustedCertificateManager.getInstance().reset()
            store.getCertificateAlias(cert)
        }.getOrNull()
        if (imported != null) {
            caAlias = imported
            localCAs = runCatching {
                TrustedCertificateManager.getInstance().load()
                    .getCACertificates(
                        TrustedCertificateManager.TrustedCertificateSource.LOCAL,
                    ).keys.toList()
            }.getOrDefault(localCAs)
            caMessage = R.string.ikev2_ca_imported
        } else {
            caMessage = R.string.ikev2_ca_import_failed
        }
    }

    val profile = initial.copy(
        name = name.trim(),
        gateway = gateway.trim(),
        port = port.toIntOrNull() ?: 500,
        vpnType = vpnType,
        username = username.trim(),
        password = password,
        caAlias = caAlias,
        userCertAlias = userCertAlias,
        localId = localId.trim(),
        remoteId = remoteId.trim(),
        mtu = mtu.toIntOrNull() ?: 0,
        natKeepalive = keepalive.toIntOrNull() ?: 0,
        ikeProposal = ikeProposal.trim(),
        espProposal = espProposal.trim(),
        eapType = eapType,
        dnsServers = dns.trim(),
        suppressCertReqs = suppressCertReqs,
        disableCrl = disableCrl,
        disableOcsp = disableOcsp,
        strictRevocation = strictRevocation,
        rsaPss = rsaPss,
        ipv6Transport = ipv6,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ikev2_settings_title)) },
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
                        onClick = { onSave(profile) },
                        enabled = name.isNotBlank() && gateway.isNotBlank(),
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
            // ------------------------------------------------------- server
            SettingsSection(stringResource(R.string.settings_section_server)) {
                Field(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.settings_name),
                    isError = name.isBlank(),
                )
                Field(
                    value = gateway,
                    onValueChange = { gateway = it },
                    label = stringResource(R.string.settings_server),
                    keyboardType = KeyboardType.Uri,
                    isError = gateway.isBlank(),
                )
                Field(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit) },
                    label = stringResource(R.string.settings_port),
                    keyboardType = KeyboardType.Number,
                )
                ChoiceField(
                    label = stringResource(R.string.ikev2_type),
                    value = vpnType,
                    options = listOf(
                        "ikev2-eap", "ikev2-cert", "ikev2-cert-eap", "ikev2-eap-tls",
                    ),
                    onSelect = { vpnType = it },
                )
            }

            // --------------------------------------------------------- auth
            SettingsSection(stringResource(R.string.ikev2_section_auth)) {
                if (profile.needsUserPass) {
                    Field(
                        value = username,
                        onValueChange = { username = it },
                        label = stringResource(R.string.label_username),
                    )
                    Field(
                        value = password,
                        onValueChange = { password = it },
                        label = stringResource(R.string.settings_password_label),
                    )
                }
                if (profile.needsCertificate) {
                    Text(
                        text = stringResource(R.string.ikev2_client_cert),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            onPickClientCert { alias ->
                                if (alias != null) userCertAlias = alias
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            userCertAlias.ifBlank {
                                stringResource(R.string.ikev2_choose_cert)
                            },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                ChoiceField(
                    label = stringResource(R.string.ikev2_ca_cert),
                    value = caAlias.ifBlank { stringResource(R.string.ikev2_ca_default) },
                    options = listOf(stringResource(R.string.ikev2_ca_default)) + localCAs,
                    onSelect = { sel ->
                        caAlias = if (sel == context.getString(R.string.ikev2_ca_default)) {
                            ""
                        } else {
                            sel
                        }
                    },
                )
                OutlinedButton(
                    onClick = { caPicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.ikev2_import_ca))
                }
                caMessage?.let {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it == R.string.ikev2_ca_import_failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            // ------------------------------------------------------- advanced
            SettingsSection(stringResource(R.string.settings_section_advanced)) {
                Field(
                    value = localId,
                    onValueChange = { localId = it },
                    label = stringResource(R.string.ikev2_local_id),
                )
                Field(
                    value = remoteId,
                    onValueChange = { remoteId = it },
                    label = stringResource(R.string.ikev2_remote_id),
                )
                Field(
                    value = mtu,
                    onValueChange = { mtu = it.filter(Char::isDigit) },
                    label = stringResource(R.string.settings_mtu),
                    keyboardType = KeyboardType.Number,
                )
                Field(
                    value = keepalive,
                    onValueChange = { keepalive = it.filter(Char::isDigit) },
                    label = stringResource(R.string.ikev2_nat_keepalive),
                    keyboardType = KeyboardType.Number,
                )
                // Encryption preset: choosing one writes the matching
                // strongSwan proposal strings into the two fields below, which
                // stay editable for full manual control. "Auto" (blank) lets
                // the server pick from strongSwan's default proposal list.
                Text(
                    text = stringResource(R.string.ikev2_encryption),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                )
                EncryptionPresetRow(
                    ikeProposal = ikeProposal,
                    espProposal = espProposal,
                    onSelect = { ike, esp ->
                        ikeProposal = ike
                        espProposal = esp
                    },
                )
                Field(
                    value = ikeProposal,
                    onValueChange = { ikeProposal = it },
                    label = stringResource(R.string.ikev2_ike_proposal),
                    placeholder = "aes256gcm16-prfsha256-ecp384",
                )
                Field(
                    value = espProposal,
                    onValueChange = { espProposal = it },
                    label = stringResource(R.string.ikev2_esp_proposal),
                    placeholder = "aes256gcm16-ecp384",
                )
                // EAP method: Surfshark regular accounts only authenticate via
                // plain EAP-MSCHAPv2 (their RADIUS breaks strongSwan PEAP), so
                // this selector lets the user force the working method per
                // profile. Auto (blank) = accept whatever the server proposes.
                Text(
                    text = stringResource(R.string.ikev2_eap_method),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                )
                EapMethodRow(
                    selected = eapType,
                    onSelect = { eapType = it },
                )
                Field(
                    value = dns,
                    onValueChange = { dns = it },
                    label = stringResource(R.string.ikev2_dns),
                    placeholder = "1.1.1.1, 8.8.8.8",
                )
            }

            // ---------------------------------------------------------- flags
            SettingsSection(stringResource(R.string.ikev2_section_flags)) {
                FlagRow(stringResource(R.string.ikev2_suppress_cert_reqs), suppressCertReqs) {
                    suppressCertReqs = it
                }
                FlagRow(stringResource(R.string.ikev2_disable_crl), disableCrl) {
                    disableCrl = it
                }
                FlagRow(stringResource(R.string.ikev2_disable_ocsp), disableOcsp) {
                    disableOcsp = it
                }
                FlagRow(stringResource(R.string.ikev2_strict_revocation), strictRevocation) {
                    strictRevocation = it
                }
                FlagRow(stringResource(R.string.ikev2_rsa_pss), rsaPss) {
                    rsaPss = it
                }
                FlagRow(stringResource(R.string.ikev2_ipv6), ipv6) {
                    ipv6 = it
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** strongSwan proposal strings behind each encryption preset. */
private const val IKE_AES = "aes256gcm16-prfsha256-ecp256"
private const val ESP_AES = "aes256gcm16-ecp256"
private const val IKE_CHACHA = "chacha20poly1305-prfsha256-ecp256"
private const val ESP_CHACHA = "chacha20poly1305-ecp256"

/**
 * Three-option preset row (Auto / AES-256-GCM / ChaCha20-Poly1305). The
 * current selection is derived from the proposal strings, so a hand-edited
 * proposal simply lights up none of the chips.
 */
@Composable
private fun EncryptionPresetRow(
    ikeProposal: String,
    espProposal: String,
    onSelect: (ike: String, esp: String) -> Unit,
) {
    val selected = when {
        ikeProposal.isBlank() && espProposal.isBlank() -> "auto"
        ikeProposal == IKE_AES && espProposal == ESP_AES -> "aes"
        ikeProposal == IKE_CHACHA && espProposal == ESP_CHACHA -> "chacha"
        else -> ""
    }
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        listOf(
            Triple("auto", stringResource(R.string.ikev2_enc_auto), "" to ""),
            Triple("aes", "AES-256-GCM", IKE_AES to ESP_AES),
            Triple("chacha", "ChaCha20-Poly1305", IKE_CHACHA to ESP_CHACHA),
        ).forEach { (key, label, proposals) ->
            val active = selected == key
            val accent = MaterialTheme.colorScheme.primary
            androidx.compose.foundation.layout.Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (active) accent.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    )
                    .clickable { onSelect(proposals.first, proposals.second) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Four-option EAP method row (Auto / MSCHAPv2 / PEAP / TTLS). Blank string
 * means Auto: strongSwan accepts whatever EAP method the server proposes.
 */
@Composable
private fun EapMethodRow(
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        listOf(
            "" to stringResource(R.string.ikev2_enc_auto),
            "mschapv2" to "MSCHAPv2",
            "peap" to "PEAP",
            "ttls" to "TTLS",
        ).forEach { (value, label) ->
            val active = selected == value
            val accent = MaterialTheme.colorScheme.primary
            androidx.compose.foundation.layout.Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (active) accent.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FlagRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {    Row(
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
