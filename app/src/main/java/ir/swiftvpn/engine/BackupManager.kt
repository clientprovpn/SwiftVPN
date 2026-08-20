package ir.swiftvpn.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full-app backup: every profile's original config plus the app-level state
 * (favourites, tile selection) in one zip that the ordinary "Import .zip
 * archive" flow can restore.
 *
 * Entries are named after the PROFILE, not the internal uuid, so a restore
 * brings back the names the user actually recognises — and the same zip also
 * loads in the official WireGuard app or any OpenVPN client, because a backup
 * is only a backup if something other than the app that wrote it can read it.
 */
object BackupManager {

    private const val TAG = "BackupManager"
    const val MANIFEST = "manifest.json"

    /**
     * Builds the backup zip in the FileProvider-exportable cache folder and
     * returns it, or null when there is nothing to back up.
     */
    fun exportAll(context: Context, profiles: List<Profile>): File? {
        if (profiles.isEmpty()) return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val outDir = File(context.cacheDir, "export").apply { mkdirs() }
        val out = File(outDir, "swiftvpn-backup-$stamp.zip")

        return runCatching {
            var written = 0
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                val usedNames = mutableSetOf<String>()
                for (p in profiles) {
                    val (dirName, ext) = when (p.protocol) {
                        Protocol.WIREGUARD -> "wireguard" to "conf"
                        Protocol.OPENVPN -> "openvpn" to "ovpn"
                        Protocol.XRAY -> "xray" to "link"
                        Protocol.IKEV2 -> "ikev2" to "json"
                    }
                    val src = when (p.protocol) {
                        Protocol.WIREGUARD -> File(context.filesDir, "wireguard/${p.uuid}.conf")
                        Protocol.OPENVPN -> File(context.filesDir, "openvpn_src/${p.uuid}.ovpn")
                        Protocol.XRAY -> File(context.filesDir, "xray/${p.uuid}.link")
                        // IKEv2 profiles live in the strongSwan SQLite store, so
                        // the backup materialises them as a JSON dump on the fly.
                        Protocol.IKEV2 -> {
                            val prof = VpnEngine.ikev2Settings(context, p.uuid)
                            val tmp = File(outDir, "ikev2-${p.uuid}.json")
                            if (prof != null) {
                                tmp.writeText(JSONObject().apply {
                                    put("name", prof.name)
                                    put("gateway", prof.gateway)
                                    put("port", prof.port)
                                    put("vpnType", prof.vpnType)
                                    put("username", prof.username)
                                    // Passwords are exported too — a backup is
                                    // only useful if the profile can reconnect.
                                    put("password", prof.password)
                                    put("caAlias", prof.caAlias)
                                    put("userCertAlias", prof.userCertAlias)
                                    put("localId", prof.localId)
                                    put("remoteId", prof.remoteId)
                                    put("mtu", prof.mtu)
                                    put("natKeepalive", prof.natKeepalive)
                                    put("ikeProposal", prof.ikeProposal)
                                    put("espProposal", prof.espProposal)
                                    put("dnsServers", prof.dnsServers)
                                    put("suppressCertReqs", prof.suppressCertReqs)
                                    put("disableCrl", prof.disableCrl)
                                    put("disableOcsp", prof.disableOcsp)
                                    put("strictRevocation", prof.strictRevocation)
                                    put("rsaPss", prof.rsaPss)
                                    put("ipv6Transport", prof.ipv6Transport)
                                    // Certificate material travels WITH the
                                    // profile, so a restore never asks the user
                                    // to re-import anything. Personal-app
                                    // backup — kept in cleartext by design.
                                    runCatching {
                                        if (prof.caAlias.isNotBlank()) {
                                            Ikev2Engine.init(context)
                                            org.strongswan.android.logic.TrustedCertificateManager
                                                .getInstance()
                                                .getCACertificateFromAlias(prof.caAlias)
                                                ?.let { cert ->
                                                    put("caCertDerB64",
                                                        android.util.Base64.encodeToString(
                                                            cert.encoded, android.util.Base64.DEFAULT))
                                                }
                                        }
                                    }
                                    runCatching {
                                        if (prof.userCertAlias.isNotBlank()) {
                                            val chain = android.security.KeyChain
                                                .getCertificateChain(context, prof.userCertAlias)
                                            if (chain != null && chain.isNotEmpty()) {
                                                put("userCertChainDerB64", JSONArray(
                                                    chain.map {
                                                        android.util.Base64.encodeToString(
                                                            it.encoded, android.util.Base64.DEFAULT)
                                                    }))
                                            }
                                            // Software-backed keys are exportable;
                                            // hardware-backed ones return null here.
                                            android.security.KeyChain
                                                .getPrivateKey(context, prof.userCertAlias)
                                                ?.encoded?.let { keyDer ->
                                                    put("userKeyPkcs8B64",
                                                        android.util.Base64.encodeToString(
                                                            keyDer, android.util.Base64.DEFAULT))
                                                }
                                        }
                                    }
                                }.toString(2))
                            }
                            tmp
                        }
                    }
                    if (!src.exists()) {
                        Log.w(TAG, "no stored config for ${p.name}; skipping")
                        continue
                    }
                    var base = safeName(p.name)
                    while (!usedNames.add("$dirName/$base")) base = "${base}_"
                    zip.putNextEntry(ZipEntry("$dirName/$base.$ext"))
                    src.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    written++
                }
                if (written == 0) throw IllegalStateException("nothing to back up")
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(manifest(context, profiles).toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            out
        }.onFailure {
            Log.w(TAG, "backup export failed", it)
            out.delete()
        }.getOrNull()
    }

    /** App-level state worth restoring, keyed by profile NAME (uuids change). */
    private fun manifest(context: Context, profiles: List<Profile>): JSONObject {
        val store = ProfileStore(context)
        return JSONObject()
            .put("version", 1)
            .put("app", "SwiftVPN-3p")
            .put("favourites", JSONArray(profiles.filter { it.isFavourite }.map { it.name }))
            .put("tileProfile", store.selected()?.name ?: "")
    }

    /** Reads the manifest from a backup zip, or null when there is none. */
    fun readManifest(input: java.io.InputStream): JSONObject? = runCatching {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val base = entry.name.replace('\\', '/').substringAfterLast('/')
                if (base == MANIFEST) {
                    return@runCatching JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                }
                zip.closeEntry()
            }
            null
        }
    }.getOrNull()

    /**
     * Applies favourites + tile selection from a restored backup, matching by
     * profile name against the profiles that exist NOW (post-import).
     */
    fun applyManifest(context: Context, manifest: JSONObject, current: List<Profile>) {
        val store = ProfileStore(context)
        val favNames = buildSet {
            val arr = manifest.optJSONArray("favourites") ?: return@buildSet
            for (i in 0 until arr.length()) add(arr.optString(i))
        }
        val tileName = manifest.optString("tileProfile", "")
        for (p in current) {
            if (p.name in favNames && !store.favourites().contains(p.uuid)) {
                store.toggleFavourite(p.uuid)
            }
            if (p.name == tileName) store.select(p)
        }
    }

    /** Makes a profile name safe to embed in a zip entry. */
    fun safeName(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifBlank { "profile" }
}
