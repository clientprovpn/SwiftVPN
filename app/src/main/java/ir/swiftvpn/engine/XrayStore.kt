package ir.swiftvpn.engine

import android.content.Context
import android.util.Log
import ir.swiftvpn.engine.xray.XrayConfig
import ir.swiftvpn.engine.xray.XrayOutbound
import ir.swiftvpn.engine.xray.XrayShareLink
import java.io.File
import java.util.UUID

/**
 * Persistence for Xray profiles.
 *
 * Mirrors [WireGuardStore]: one file per profile under `xray/<uuid>.link`
 * holding the raw share link exactly as pasted, plus a display name in
 * SharedPreferences keyed by uuid.
 *
 * Storing the LINK rather than the expanded JSON config is deliberate, for the
 * same reason WireGuardStore keeps the raw text: the link is the user's source
 * of truth, it round-trips losslessly, and a future parser that understands more
 * transport options will pick them up for free. The JSON is regenerated from the
 * link at connect time by [ir.swiftvpn.engine.xray.XrayConfig].
 */
class XrayStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs =
        appContext.getSharedPreferences("swiftvpn_xray", Context.MODE_PRIVATE)

    private val dir: File
        get() = File(appContext.filesDir, "xray").apply { mkdirs() }

    private fun fileFor(uuid: String) = File(dir, "$uuid.link")
    private fun jsonFileFor(uuid: String) = File(dir, "$uuid.json")

    /** The stored file, whichever flavour this profile is (link or raw JSON). */
    private fun storedFile(uuid: String): File = when {
        fileFor(uuid).exists() -> fileFor(uuid)
        jsonFileFor(uuid).exists() -> jsonFileFor(uuid)
        else -> fileFor(uuid)
    }

    // ------------------------------------------------------------------- read

    fun profiles(): List<Profile> =
        (dir.listFiles { f -> f.extension == "link" || f.extension == "json" })
            ?.mapNotNull { file ->
                val uuid = file.nameWithoutExtension
                val raw = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
                toProfile(uuid, raw)
            }
            ?: emptyList()

    /** Raw stored text — a share link OR a raw JSON config, whichever it is. */
    fun link(uuid: String): String? = runCatching { storedFile(uuid).readText() }.getOrNull()

    fun name(uuid: String): String =
        prefs.getString(keyName(uuid), null) ?: uuid.take(8)

    /** Parsed outbound for display/editing, or null for bad links AND raw JSON. */
    fun outbound(uuid: String): XrayOutbound? =
        link(uuid)?.let { XrayShareLink.parse(it) }

    /** True when the profile is a raw JSON ("Custom config") rather than a link. */
    fun isCustomJson(uuid: String): Boolean =
        link(uuid)?.let { XrayConfig.isCustomJson(it) } == true

    /**
     * Display fallback for tunnel info on custom-JSON profiles: first outbound's
     * endpoint and protocol, read straight from the JSON.
     */
    fun describe(uuid: String): Pair<String, String>? {
        val raw = link(uuid) ?: return null
        if (!XrayConfig.isCustomJson(raw)) {
            val o = XrayShareLink.parse(raw) ?: return null
            return "${o.address}:${o.port}" to o.summary
        }
        return runCatching {
            val first = org.json.JSONObject(raw).getJSONArray("outbounds").getJSONObject(0)
            val proto = first.optString("protocol", "custom")
            val settings = first.optJSONObject("settings")
            val addr = settings?.optJSONArray("vnext")?.optJSONObject(0)
                ?.let { "${it.optString("address")}:${it.optString("port")}" }
                ?: settings?.optJSONArray("servers")?.optJSONObject(0)
                    ?.let { "${it.optString("address")}:${it.optString("port")}" }
                ?: "custom"
            addr to proto
        }.getOrNull()
    }

    fun exists(uuid: String): Boolean = storedFile(uuid).exists()

    // ------------------------------------------------------------------ write

    /**
     * Validates and stores a share link as a new profile.
     *
     * Returns null when the link cannot be parsed — validation happens BEFORE
     * writing, so a bad paste never leaves a half-made profile.
     */
    fun import(
        link: String,
        preferredName: String,
        subscriptionId: String? = null,
        /**
         * Names already in use. Supplied by [importBatch] so a bulk import does
         * not re-list and re-parse every stored profile for each link — that is
         * O(N^2) disk work, which a 200-server subscription makes painful.
         * Null means "look them up", the right behaviour for a single import.
         */
        takenNames: MutableSet<String>? = null,
    ): Profile? {
        val trimmed = link.trim()
        val parsed = XrayShareLink.parse(trimmed) ?: return null
        val uuid = UUID.randomUUID().toString()
        // Prefer the name embedded in the link (its #fragment) over the filename.
        val wanted = parsed.name.ifBlank { preferredName.ifBlank { "xray" } }
        val name = if (takenNames == null) uniqueName(wanted) else {
            uniqueAgainst(wanted, takenNames).also { takenNames.add(it) }
        }

        return runCatching {
            fileFor(uuid).writeText(trimmed)
            prefs.edit().apply {
                putString(keyName(uuid), name)
                // Recording the owning subscription is what lets a refresh replace
                // exactly its own servers and leave hand-added ones alone.
                if (subscriptionId != null) putString(keySub(uuid), subscriptionId)
            }.apply()
            profileFrom(uuid, name, parsed, subscriptionId)
        }.getOrElse {
            Log.w(TAG, "import failed", it)
            runCatching { fileFor(uuid).delete() }
            null
        }
    }

    /**
     * Imports many links at once, sharing a single name-uniqueness set so the
     * cost stays linear in the number of links.
     */
    fun importBatch(links: List<String>, subscriptionId: String? = null): Int {
        val taken = profiles().map { it.name }.toMutableSet()
        return links.count { import(it, "", subscriptionId, taken) != null }
    }

    /**
     * Imports every share link found in [text], for a pasted batch or a
     * subscription body. Returns how many were accepted.
     */
    fun importMany(text: String, subscriptionId: String? = null): Int =
        importBatch(
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && XrayShareLink.looksLikeShareLink(it) }
                .toList(),
            subscriptionId,
        )

    /** Which subscription owns [uuid], or null when it was added by hand. */
    fun subscriptionOf(uuid: String): String? = prefs.getString(keySub(uuid), null)

    /**
     * Removes every profile owned by [subscriptionId], except [keepUuid].
     *
     * [keepUuid] exists so a refresh cannot delete the profile that is CURRENTLY
     * CONNECTED. Deleting it would leave the tunnel running with no row in the
     * list pointing at it — unstoppable from inside the app, because the engine
     * still holds a uuid nothing on screen matches. The survivor is orphaned from
     * its subscription (its ownership key is cleared) so the next refresh treats
     * it as hand-added rather than deleting it again.
     */
    fun deleteBySubscription(subscriptionId: String, keepUuid: String? = null) {
        // listFiles returns a materialised array, so deleting inside the loop is
        // safe — there is no live iterator over the directory.
        dir.listFiles { f -> f.extension == "link" || f.extension == "json" }?.forEach { file ->
            val uuid = file.nameWithoutExtension
            if (subscriptionOf(uuid) != subscriptionId) return@forEach
            if (uuid == keepUuid) {
                prefs.edit().remove(keySub(uuid)).apply()
                return@forEach
            }
            delete(uuid)
        }
    }

    /**
     * Stores a raw JSON config as a new "Custom config" profile.
     * Validated before writing, exactly like [import].
     */
    fun importCustomJson(
        jsonText: String,
        preferredName: String,
        subscriptionId: String? = null,
    ): Profile? {
        val trimmed = jsonText.trim()
        if (!XrayConfig.isValidCustomJson(trimmed)) return null
        val uuid = UUID.randomUUID().toString()
        val name = uniqueName(preferredName.ifBlank { "custom" })
        return runCatching {
            jsonFileFor(uuid).writeText(trimmed)
            prefs.edit().apply {
                putString(keyName(uuid), name)
                if (subscriptionId != null) putString(keySub(uuid), subscriptionId)
            }.apply()
            toProfile(uuid, trimmed)
        }.getOrElse {
            Log.w(TAG, "importCustomJson failed", it)
            runCatching { jsonFileFor(uuid).delete() }
            null
        }
    }

    /** Replaces the stored link. Rejects the write if the new link is invalid. */
    fun saveLink(uuid: String, link: String): Boolean {
        val trimmed = link.trim()
        // Raw JSON configs validate as JSON and live in the .json file; share
        // links validate by re-parsing and live in the .link file. Saving one
        // flavour removes the other so a profile never exists twice.
        if (XrayConfig.isCustomJson(trimmed)) {
            if (!XrayConfig.isValidCustomJson(trimmed)) return false
            return runCatching {
                jsonFileFor(uuid).writeText(trimmed)
                fileFor(uuid).delete()
                true
            }.getOrElse {
                Log.w(TAG, "saveLink(json) failed", it)
                false
            }
        }
        if (XrayShareLink.parse(trimmed) == null) return false
        return runCatching {
            fileFor(uuid).writeText(trimmed)
            jsonFileFor(uuid).delete()
            true
        }
            .getOrElse {
                Log.w(TAG, "saveLink failed", it)
                false
            }
    }

    fun rename(uuid: String, newName: String) {
        if (newName.isBlank()) return
        prefs.edit().putString(keyName(uuid), newName).apply()
    }

    fun delete(uuid: String) {
        runCatching { fileFor(uuid).delete() }
        runCatching { jsonFileFor(uuid).delete() }
        prefs.edit().remove(keyName(uuid)).remove(keySub(uuid)).apply()
    }

    // ---------------------------------------------------------------- helpers

    private fun toProfile(uuid: String, raw: String): Profile? {
        // Custom-JSON profiles have no share link to parse; their display
        // fields come from the first outbound inside the JSON.
        if (XrayConfig.isCustomJson(raw)) {
            val (remote, summary) = describe(uuid) ?: ("custom" to "custom · json")
            return Profile(
                uuid = uuid,
                name = name(uuid),
                server = remote.substringBeforeLast(':'),
                port = remote.substringAfterLast(':', ""),
                useUdp = true,
                authTypeLabel = summary,
                protocol = Protocol.XRAY,
                subscriptionId = subscriptionOf(uuid),
            )
        }
        val parsed = XrayShareLink.parse(raw) ?: return null
        return profileFrom(uuid, name(uuid), parsed, subscriptionOf(uuid))
    }

    private fun profileFrom(
        uuid: String,
        name: String,
        o: XrayOutbound,
        subscriptionId: String? = null,
    ): Profile = Profile(
        uuid = uuid,
        name = name,
        server = o.address,
        port = o.port.toString(),
        // Xray outbounds are effectively UDP-tolerant proxies; the TCP/UDP badge
        // is meaningless here, so the endpoint line omits it (see Profile).
        useUdp = true,
        authTypeLabel = o.summary,
        protocol = Protocol.XRAY,
        subscriptionId = subscriptionId,
    )

    private fun uniqueName(wanted: String): String =
        uniqueAgainst(wanted, profiles().map { it.name }.toSet())

    private fun uniqueAgainst(wanted: String, taken: Set<String>): String {
        if (wanted !in taken) return wanted
        var i = 2
        while ("$wanted ($i)" in taken) i++
        return "$wanted ($i)"
    }

    private fun keyName(uuid: String) = "name_$uuid"
    private fun keySub(uuid: String) = "sub_$uuid"

    private companion object {
        const val TAG = "XrayStore"
    }
}
