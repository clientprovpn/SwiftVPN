package ir.swiftvpn.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Masks
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ir.swiftvpn.R
import ir.swiftvpn.ui.GlassTokens

/**
 * The "+" menu, Exclave-style: a linear glass dropdown in the top bar with one
 * icon per row, and a "Manual settings" sub-menu that holds every protocol the
 * app can build by hand (IKEv2 included).
 *
 * This replaced the old AddProfileDialog: a modal with a paste box, a stack of
 * rectangular buttons and a two-column protocol grid was visually heavy next to
 * the overflow menu, and pasting from the clipboard never needed a text field
 * of its own — the clipboard already holds the text, so the item reads it
 * directly.
 */
@Composable
fun AddProfileMenu(
    onScanQr: () -> Unit,
    onImportClipboard: () -> Unit,
    onImportFile: () -> Unit,
    /** Manual creation; "ikev2" selects the IKEv2 editor, anything else Xray-family. */
    onAddManual: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var manualOpen by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.action_import),
            )
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = GlassTokens.menuColor(),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassTokens.cardBorderBrush()),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_scan_qr)) },
                leadingIcon = { Icon(Icons.Default.QrCodeScanner, null) },
                onClick = { open = false; onScanQr() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_from_clipboard)) },
                leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                onClick = { open = false; onImportClipboard() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_from_file)) },
                leadingIcon = { Icon(Icons.Default.UploadFile, null) },
                onClick = { open = false; onImportFile() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_manual)) },
                leadingIcon = { Icon(Icons.Default.Build, null) },
                trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                onClick = { open = false; manualOpen = true },
            )
        }

        // Sub-menu: every protocol that can be built by hand, one row each.
        // Anchored to the same box, so it opens where the parent item was.
        DropdownMenu(
            expanded = manualOpen,
            onDismissRequest = { manualOpen = false },
            containerColor = GlassTokens.menuColor(),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassTokens.cardBorderBrush()),
        ) {
            manualEntries().forEach { (kind, label, icon) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Icon(icon, null) },
                    onClick = { manualOpen = false; onAddManual(kind) },
                )
            }
        }
    }
}

private data class ManualEntry(val kind: String, val label: String, val icon: ImageVector)

@Composable
private fun manualEntries(): List<ManualEntry> = listOf(
    ManualEntry("vless", "VLESS", Icons.Default.VpnLock),
    ManualEntry("vmess", "VMess", Icons.Default.Cloud),
    ManualEntry("trojan", "Trojan", Icons.Default.Security),
    ManualEntry("shadowsocks", "Shadowsocks", Icons.Default.VisibilityOff),
    ManualEntry("socks", "SOCKS", Icons.Default.Lan),
    ManualEntry("http", "HTTP", Icons.Default.Http),
    ManualEntry("custom", stringResource(R.string.add_custom_json), Icons.Default.Code),
    ManualEntry("ikev2", "IKEv2", Icons.Default.VpnKey),
    ManualEntry("warp", stringResource(R.string.add_warp), Icons.Default.Cloud),
)
