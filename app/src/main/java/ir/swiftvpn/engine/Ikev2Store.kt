package ir.swiftvpn.engine

import android.content.Context
import org.strongswan.android.data.VpnProfile
import org.strongswan.android.data.VpnProfileSqlDataSource
import java.util.UUID

/**
 * Persistence for IKEv2 profiles, on top of the vendored strongSwan SQLite
 * store — the same database CharonVpnService reads when starting a tunnel, so
 * nothing needs to be duplicated or synced.
 */
class Ikev2Store(private val context: Context) {

    private fun <T> withDb(block: (VpnProfileSqlDataSource) -> T): T {
        val ds = VpnProfileSqlDataSource(context)
        ds.open()
        try {
            @Suppress("UNCHECKED_CAST")
            return block(ds)
        } finally {
            ds.close()
        }
    }

    fun exists(uuid: String): Boolean = withDb { ds ->
        runCatching { ds.getVpnProfile(UUID.fromString(uuid)) != null }.getOrDefault(false)
    }

    fun profile(uuid: String): Ikev2Profile? = withDb { ds ->
        runCatching { ds.getVpnProfile(UUID.fromString(uuid)) }.getOrNull()
            ?.let(Ikev2Profile::fromVpnProfile)
    }

    /** Insert or overwrite; returns the (possibly new) uuid. */
    fun save(profile: Ikev2Profile): String = withDb { ds ->
        val p = profile.toVpnProfile()
        if (runCatching { ds.getVpnProfile(p.getUUID()) }.getOrNull() != null) {
            ds.updateVpnProfile(p)
        } else {
            ds.insertProfile(p)
        }
        p.getUUID().toString()
    }

    fun delete(uuid: String): Unit = withDb { ds ->
        runCatching { ds.getVpnProfile(UUID.fromString(uuid)) }.getOrNull()
            ?.let { ds.deleteVpnProfile(it) }
        Unit
    }

    /** All profiles, for the list screen. */
    fun all(): List<Ikev2Profile> = withDb { ds ->
        ds.allVpnProfiles.map(Ikev2Profile::fromVpnProfile)
    }
}
