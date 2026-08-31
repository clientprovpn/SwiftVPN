package ir.swiftvpn.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import ir.swiftvpn.MainActivity
import ir.swiftvpn.R
import ir.swiftvpn.engine.ProfileStore
import ir.swiftvpn.engine.VpnEngine
import ir.swiftvpn.engine.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: one tap to connect or disconnect.
 *
 * The engine now runs in our own process, so the tile reads state straight
 * from [VpnEngine] — no binder, no sync drift.
 *
 * A TileService cannot call startActivityForResult, so if VPN permission has
 * not been granted yet the tile opens MainActivity instead of failing quietly.
 */
class VpnTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var observeJob: Job? = null

    private val store by lazy { ProfileStore(this) }

    override fun onStartListening() {
        super.onStartListening()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = newScope
        observeJob = newScope.launch {
            VpnEngine.state.collect { render(it) }
        }
    }

    override fun onStopListening() {
        observeJob?.cancel()
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()

        val target = store.selected()
        if (target == null) {
            openApp()
            return
        }

        // Permission grants need an Activity.
        if (VpnEngine.vpnPermissionIntent(this) != null) {
            openApp()
            return
        }

        if (VpnEngine.state.value.isActive) {
            VpnEngine.disconnect(this)
        } else {
            // Optimistic feedback: a tile that looks dead for a second is the
            // exact problem this app exists to solve.
            render(VpnState.CONNECTING)
            // connect() suspends and dispatches to IO itself, so this scope can
            // stay on Main.immediate — the tile only needs it to render.
            scope?.launch {
                val started = VpnEngine.connect(this@VpnTileService, target.uuid)
                if (!started) render(VpnEngine.state.value)
            }
        }
    }

    private fun render(state: VpnState) {
        val tile = qsTile ?: return
        val name = store.selectedName()

        tile.state = when {
            name == null -> Tile.STATE_INACTIVE
            state.isActive -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(
            this,
            if (state.isActive) R.drawable.ic_tile_vpn else R.drawable.ic_tile_vpn_off,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                name == null -> getString(R.string.tile_pick_profile)
                state == VpnState.CONNECTED -> name
                state == VpnState.CONNECTING -> getString(R.string.state_connecting)
                state == VpnState.PAUSED -> getString(R.string.state_paused)
                state == VpnState.AUTH_FAILED -> getString(R.string.state_auth_failed)
                state == VpnState.NO_NETWORK -> getString(R.string.state_no_network)
                else -> getString(R.string.state_disconnected)
            }
        }

        tile.contentDescription = buildString {
            append(getString(R.string.tile_label))
            name?.let { append(", ").append(it) }
        }
        tile.updateTile()
    }

    /**
     * startActivityAndCollapse(Intent) throws on API 34+, which requires a
     * PendingIntent instead.
     */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
