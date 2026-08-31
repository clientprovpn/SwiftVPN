package ir.swiftvpn.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.swiftvpn.R
import ir.swiftvpn.ui.GlassTokens
import ir.swiftvpn.engine.QrCode

/**
 * Shows a share link as a QR code so another device can scan it.
 *
 * The bitmap is generated once per link via `remember` — encoding is fast but not
 * free, and recomposing on every frame of the dialog animation would be waste.
 *
 * The code sits on an explicit WHITE card, not the theme surface. In dark mode a
 * themed background would put a dark quiet zone around a dark-module code, and
 * many scanners refuse to read inverted or low-contrast codes.
 */
@Composable
fun QrDialog(
    link: String,
    onDismiss: () -> Unit,
    onCopied: () -> Unit,
) {
    val bitmap = remember(link) { QrCode.encode(link) }
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        containerColor = GlassTokens.dialogColor(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qr_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (bitmap == null) {
                    Text(
                        text = stringResource(R.string.qr_encode_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    // The raw link under the code, both as a fallback for a
                    // reluctant scanner and so the user can verify what is
                    // actually encoded before sharing it.
                    Text(
                        text = link,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_dismiss))
            }
        },
        dismissButton = {
            // The same payload the QR encodes, as plain text — for Xray this is
            // the share link (vless://…), for WireGuard the whole wg config,
            // both paste-importable in the usual clients.
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(link))
                    onCopied()
                },
            ) {
                Text(stringResource(R.string.action_copy))
            }
        },
    )
}
