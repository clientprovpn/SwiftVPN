package ir.swiftvpn.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.swiftvpn.R
import ir.swiftvpn.ui.GlassTokens

/**
 * Two ways to add a profile, in one dialog.
 *
 * Xray servers are shared as links a user copies from a panel, while OpenVPN and
 * WireGuard arrive as files. Rather than make the user guess which "+" does what,
 * the dialog offers both: paste a link, or pick a file. The paste field is first
 * because it is the new, common case; the file button sits below for the two
 * file-based protocols.
 */
@Composable
fun AddProfileDialog(
    onDismiss: () -> Unit,
    onPasteLink: (String) -> Unit,
    onPickFile: () -> Unit,
    onPickZip: () -> Unit,
    onScanQr: () -> Unit,
    onAddIkev2: () -> Unit,
) {
    var link by remember { mutableStateOf("") }

    AlertDialog(
        containerColor = GlassTokens.dialogColor(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    placeholder = { Text(stringResource(R.string.add_paste_hint)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onScanQr,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_scan_qr))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onPickFile,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.add_from_file))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onPickZip,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.add_zip))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onAddIkev2,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.add_ikev2))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPasteLink(link) },
                enabled = link.isNotBlank(),
            ) {
                Text(stringResource(R.string.add_paste))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
