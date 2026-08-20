package ir.swiftvpn.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import ir.swiftvpn.engine.xray.XrayShareLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/** A saved subscription URL. */
data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val lastUpdated: Long = 0L,
    val profileCount: Int = 0,
)

/**
 * Stores subscription URLs and imports the servers they publish.
 *
 * Refresh is MANUAL by design — the user asked for a button, not a background
 * job. That keeps the app free of scheduled work, and means a refresh only ever
 * happens while they are watching it.
 *
 * The wire format is not standardised in practice, so the fetcher accepts both
 * shapes seen in the wild:
 *  * a base64 blob that decodes to newline-separated share links, and
 *  * a plain text body that already IS newline-separated links.
 * Detection is by trying to parse, not by guessing from headers.
 *
 * Ownership: every profile imported from a subscription records its subscription
 * id, so a later refresh can replace exactly that set and leave hand-added
 * profiles untouched. Without that, a refresh would either duplicate everything
 * or wipe manual work.
 */
class XraySubscriptionStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs =
        appContext.getSharedPreferences("swiftvpn_xray_subs", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------- read

    fun all(): List<Subscription> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Subscription(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    url = o.getString("url"),
                    lastUpdated = o.optLong("lastUpdated"),
                    profileCount = o.optInt("profileCount"),
                )
            }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------ write

    fun add(name: String, url: String): Subscription? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
            return null
        }
        val sub = Subscription(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { hostOf(trimmed) },
            url = trimmed,
        )
        save(all() + sub)
        return sub
    }

    fun remove(id: String) {
        save(all().filterNot { it.id == id })
        // The profiles it owned go with it; leaving them would be orphaned rows
        // the user cannot refresh or trace back to anything. The one exception is
        // a profile that is connected right now — see deleteBySubscription.
        XrayStore(appContext).deleteBySubscription(id, keepUuid = XrayEngine.activeUuid)
    }

    fun rename(id: String, newName: String) {
        if (newName.isBlank()) return
        save(all().map { if (it.id == id) it.copy(name = newName.trim()) else it })
    }

    private fun save(list: List<Subscription>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("url", s.url)
                    .put("lastUpdated", s.lastUpdated)
                    .put("profileCount", s.profileCount)
            )
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    // ---------------------------------------------------------------- refresh

    /** Outcome of a refresh, for the message shown to the user. */
    data class RefreshResult(
        val imported: Int = 0,
        val error: String? = null,
    )

    /**
     * Fetches [sub] and replaces the profiles it owns.
     *
     * Replace-not-merge is deliberate: providers rotate servers, so a merge would
     * silently accumulate dead entries forever. The replacement only happens
     * AFTER a successful parse that yielded at least one link — a failed fetch
     * must never delete the user's working servers.
     */
    suspend fun refresh(sub: Subscription): RefreshResult = withContext(Dispatchers.IO) {
        val body = runCatching { fetch(sub.url) }
            .onFailure { Log.w(TAG, "subscription fetch failed", it) }
            .getOrNull()
            ?: return@withContext RefreshResult(error = "Could not reach the subscription URL")

        val links = parseLinks(body)
        if (links.isEmpty()) {
            return@withContext RefreshResult(
                error = "The subscription returned no usable servers"
            )
        }

        val store = XrayStore(appContext)
        // Keep the connected profile: its tunnel is live and deleting the row
        // would orphan it from the UI.
        store.deleteBySubscription(sub.id, keepUuid = XrayEngine.activeUuid)

        // Batch import: one name-uniqueness pass for the whole set rather than a
        // full directory rescan per link.
        val imported = store.importBatch(links, subscriptionId = sub.id)

        save(
            all().map {
                if (it.id == sub.id) {
                    it.copy(lastUpdated = System.currentTimeMillis(), profileCount = imported)
                } else it
            }
        )

        RefreshResult(imported = imported)
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            // Some panels serve different bodies (or 403) without a UA.
            setRequestProperty("User-Agent", "SwiftVPN")
            instanceFollowRedirects = true
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
            .also { conn.disconnect() }
    }

    /**
     * Extracts share links from a subscription body in either encoding.
     *
     * Tries base64 first because that is the more common form, but validates by
     * looking for an actual link in the decoded text rather than trusting the
     * decode to have succeeded — base64 decoding rarely fails outright, it just
     * produces garbage.
     */
    private fun parseLinks(body: String): List<String> {
        val decoded = runCatching {
            String(decodeBase64(body.trim()), StandardCharsets.UTF_8)
        }.getOrNull()

        // Score BOTH readings and keep whichever actually yields more links.
        //
        // The obvious shortcut — "does the decoded blob start with a scheme?" —
        // silently loses whole subscriptions: several panels prepend a title or
        // an "updated: …" remark line, so the first line is not a link and the
        // check fails even though every line after it is valid. Counting is
        // cheap and cannot be fooled that way.
        val fromDecoded = decoded?.let { collect(it) } ?: emptyList()
        val fromRaw = collect(body)
        return if (fromDecoded.size >= fromRaw.size) fromDecoded else fromRaw
    }

    private fun collect(text: String): List<String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && XrayShareLink.looksLikeShareLink(it) }
        .distinct()
        .toList()

    private fun decodeBase64(input: String): ByteArray {
        val s = input.replace("\n", "").replace("\r", "")
            .replace('-', '+').replace('_', '/')
        val padded = when (s.length % 4) {
            2 -> "$s=="
            3 -> "$s="
            else -> s
        }
        return Base64.decode(padded, Base64.DEFAULT)
    }

    private fun hostOf(url: String): String =
        runCatching { URL(url).host }.getOrNull()?.ifBlank { "subscription" } ?: "subscription"

    private companion object {
        const val TAG = "XraySubscription"
        const val KEY_LIST = "subscriptions"
    }
}
