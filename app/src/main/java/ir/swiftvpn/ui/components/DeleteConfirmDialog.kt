package ir.swiftvpn.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ir.swiftvpn.R
import ir.swiftvpn.ui.GlassTokens
import ir.swiftvpn.ui.StatusColors

/**
 * Confirms deletion, because it cannot be undone.
 *
 * There is no trash and no undo snackbar here: an OpenVPN profile lives inside
 * the engine's own ProfileManager and a WireGuard one is a file on disk, so
 * restoring a deleted profile would mean building a staging area for something
 * the user rarely wants back. A clear confirmation up front is the honest trade.
 *
 * Two details earn their place:
 *  * naming the single profile, so a mis-tapped row is caught by reading the
 *    dialog rather than by discovering the loss afterwards, and
 *  * warning when one of the selected profiles is CONNECTED — deleting it stops
 *    the tunnel, which the user has a right to know before confirming rather
 *    than noticing their connection dropped.
 */
@Composable
fun DeleteConfirmDialog(
    count: Int,
    singleName: String?,
    includesLive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        containerColor = GlassTokens.dialogColor(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = if (count == 1 && singleName != null) {
                        stringResource(R.string.delete_body_one, singleName)
                    } else {
                        stringResource(R.string.delete_body_many, count)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (includesLive) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.delete_live_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.connecting,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.action_confirm_delete),
                    // Red on the destructive action so the two buttons are not
                    // interchangeable at a glance.
                    color = StatusColors.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
