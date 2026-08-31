package ir.swiftvpn.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ir.swiftvpn.engine.VpnEngine

/**
 * Backs the Disconnect action on the WireGuard notification.
 *
 * A broadcast receiver rather than a PendingIntent straight to the service,
 * because the engine's own notification offers the same affordance and users
 * expect the button to work without the app being open — a receiver runs even
 * when no Activity exists.
 *
 * `VpnEngine.disconnect` dispatches its own work off the main thread, so there is
 * nothing to await here; the receiver's short lifetime is not a problem.
 */
class WireGuardDisconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        VpnEngine.disconnect(context.applicationContext)
    }
}
