package ir.swiftvpn.engine

import android.content.Context
import android.util.Log
import org.amnezia.awg.config.Config
import java.io.File
import java.util.UUID

/**
 * Persistence for WireGuard profiles.
 *
 * The WireGuard library deliberately ships no profile store — it parses a
 * wg-quick document into an immutable [Config] and nothing more. The official
 * app keeps its own; so must we.
 *
 * Design: each profile is one file under `wireguard/<uuid>.conf`, holding the
 * raw text exactly as imported. Storing the TEXT rather than a serialised
 * Config matters for two reasons:
 *
 *  1. Round-tripping through `Config.toWgQuickString()` drops comments and any
 *     key the library does not model, so a user's annotated config would come
 *     back stripped after one edit.
 *  2. A future library version that understands more keys will pick them up for
 *     free, because we never normalised them away.
 *
 * Display metadata (the human name) lives in SharedPreferences keyed by uuid,
 * so renaming never rewrites the config file.
 */
class WireGuardStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs =
        appContext.getSharedPreferences("swiftvpn_wireguard", Context.MODE_PRIVATE)

    private val dir: File
        get() = File(appContext.filesDir, "wireguard").apply { mkdirs() }

    private fun fileFor(uuid: String) = File(dir, "$uuid.conf")

    // ------------------------------------------------------------------- read

    /** All stored profiles, parsed. Unreadable entries are skipped, not thrown. */
    fun profiles(): List<Profile> =
        dir.listFiles { f -> f.extension == "conf" }
            ?.mapNotNull { file ->
                val uuid = file.nameWithoutExtension
                val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
                toProfile(uuid, text)
            }
            ?: emptyList()

    fun rawConfig(uuid: String): String? =
        runCatching { fileFor(uuid).readText() }.getOrNull()

    fun name(uuid: String): String =
        prefs.getString(keyName(uuid), null) ?: uuid.take(8)

    /**
     * Parsed config for the engine, or null when the file is missing or invalid.
     *
     * Parsing is cheap but does hit the disk, so callers should stay off the
     * main thread.
     */
    fun config(uuid: String): Config? {
        val text = rawConfig(uuid) ?: return null
        return parse(text)
    }

    // ------------------------------------------------------------------ write

    /**
     * Validates and stores [text] as a new profile.
     *
     * Returns null when the text is not a usable wg-quick config — the caller
     * shows the import error. Validation happens BEFORE anything is written, so
     * a bad file never leaves a half-made profile behind.
     */
    fun import(text: String, preferredName: String): Profile? {
        val config = parse(text) ?: return null
        val uuid = UUID.randomUUID().toString()
        val name = uniqueName(preferredName.ifBlank { "wireguard" })

        return runCatching {
            fileFor(uuid).writeText(text)
            prefs.edit().putString(keyName(uuid), name).apply()
            profileFrom(uuid, name, config)
        }.getOrElse {
            Log.w(TAG, "import failed", it)
            runCatching { fileFor(uuid).delete() }
            null
        }
    }

    /** Replaces the stored text. Rejects the write if the new text is invalid. */
    fun saveConfig(uuid: String, text: String): Boolean {
        if (parse(text) == null) return false
        return runCatching { fileFor(uuid).writeText(text); true }
            .getOrElse {
                Log.w(TAG, "saveConfig failed", it)
                false
            }
    }

    fun rename(uuid: String, newName: String) {
        if (newName.isBlank()) return
        prefs.edit().putString(keyName(uuid), newName).apply()
    }

    fun delete(uuid: String) {
        runCatching { fileFor(uuid).delete() }
        prefs.edit().remove(keyName(uuid)).apply()
    }

    fun exists(uuid: String): Boolean = fileFor(uuid).exists()

    // ---------------------------------------------------------------- helpers

    /**
     * True when [text] looks like a WireGuard config rather than an .ovpn one.
     *
     * Used to route an imported file to the right engine. Checking for the
     * section header plus a key is more reliable than the file extension, since
     * pickers hand back arbitrary names and users rename files.
     */
    fun looksLikeWireGuard(text: String): Boolean {
        val head = text.take(4_000)
        // Anchored at line start, so the words merely APPEARING in an .ovpn
        // comment is not enough to misroute the file. Both a section header and
        // a WireGuard-specific key are required.
        return SECTION_INTERFACE.containsMatchIn(head) &&
            (WG_KEY.containsMatchIn(head) || SECTION_PEER.containsMatchIn(head))
    }

    private fun parse(text: String): Config? =
        runCatching { Config.parse(text.byteInputStream()) }
            .onFailure { Log.d(TAG, "not a valid wg config: ${it.message}") }
            .getOrNull()

    private fun toProfile(uuid: String, text: String): Profile? {
        val config = parse(text) ?: return null
        return profileFrom(uuid, name(uuid), config)
    }

    private fun profileFrom(uuid: String, name: String, config: Config): Profile {
        // The endpoint lives on the first peer that declares one. A config can
        // legitimately omit it (a server-side or listen-only config), in which
        // case there is nothing to show.
        val endpoint = config.peers
            .firstNotNullOfOrNull { it.endpoint.orElse(null) }

        return Profile(
            uuid = uuid,
            name = name,
            server = endpoint?.host ?: "",
            port = endpoint?.port?.toString() ?: "",
            // WireGuard is UDP-only; there is no TCP mode to represent.
            useUdp = true,
            authTypeLabel = "WG KEY",
            protocol = Protocol.WIREGUARD,
        )
    }

    private fun uniqueName(wanted: String): String {
        val taken = profiles().map { it.name }.toSet()
        if (wanted !in taken) return wanted
        var i = 2
        while ("$wanted ($i)" in taken) i++
        return "$wanted ($i)"
    }

    private fun keyName(uuid: String) = "name_$uuid"

    private companion object {
        const val TAG = "WireGuardStore"

        val SECTION_INTERFACE =
            Regex("""^\s*\[Interface]\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        val SECTION_PEER =
            Regex("""^\s*\[Peer]\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        val WG_KEY =
            Regex("""^\s*PrivateKey\s*=""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    }
}
