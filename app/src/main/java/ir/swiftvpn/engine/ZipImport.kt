package ir.swiftvpn.engine

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reads a zip of config files, for bulk-importing profiles.
 *
 * The official WireGuard app exports several tunnels as a zip of `.conf` files,
 * so that is the shape most users will already have. OpenVPN bundles are handled
 * too, since a provider sending "all my servers" as a zip of `.ovpn` is just as
 * common and there is no reason to reject it.
 *
 * SECURITY — a zip is untrusted input and this parser treats it as hostile:
 *
 *  * **No path traversal.** Entry names are never used as file paths; only the
 *    base name is kept, and only as a suggested profile name. A `../../` entry
 *    therefore cannot escape anywhere, because nothing is written using the
 *    archive's own naming.
 *  * **Bounded output.** A zip bomb — a few KB expanding to gigabytes — is
 *    stopped by a per-entry cap and a total cap, checked while streaming rather
 *    than after.
 *  * **Bounded entry count**, so an archive of a million tiny files cannot spin
 *    the importer.
 *  * **Directories and irrelevant files skipped** by extension, so images and
 *    readmes in the archive are quietly ignored instead of failing the import.
 */
object ZipImport {

    private const val TAG = "ZipImport"

    /** Largest single config accepted. Real ones are a few KB at most. */
    private const val MAX_ENTRY_BYTES = 512 * 1024

    /** Largest total payload read from one archive. */
    private const val MAX_TOTAL_BYTES = 8 * 1024 * 1024

    /** Most entries examined in one archive. */
    private const val MAX_ENTRIES = 500

    /** One config found in the archive. */
    data class Entry(
        val name: String,
        val text: String,
        /** True for IKEv2 JSON entries written by SwiftVPN's own backup. */
        val isIkev2Backup: Boolean = false,
    )

    /**
     * True when [name] looks like a zip. Used to decide whether to unpack;
     * content sniffing happens afterwards per entry, so a wrong guess here only
     * costs an attempt.
     */
    fun looksLikeZip(name: String?): Boolean =
        name?.endsWith(".zip", ignoreCase = true) == true

    /**
     * Extracts every plausible config from [input].
     *
     * Returns an empty list when the archive holds nothing usable — the caller
     * reports that as a failed import. Never throws for malformed input.
     */
    fun readConfigs(input: InputStream): List<Entry> {
        val found = mutableListOf<Entry>()
        var total = 0L
        var seen = 0

        return runCatching {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    seen++
                    if (seen > MAX_ENTRIES) {
                        Log.w(TAG, "archive has too many entries; stopping")
                        break
                    }
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }

                    // Only the base name, never the path. This is what makes a
                    // traversal entry harmless: the directory part is discarded
                    // and the name is used purely as a label.
                    val base = entry.name
                        .replace('\\', '/')
                        .substringAfterLast('/')

                    // SwiftVPN backups carry IKEv2 profiles as JSON under ikev2/ —
                    // accept that exact shape, but not random JSON files.
                    val isIkev2Backup = entry.name
                        .replace('\\', '/')
                        .startsWith("ikev2/") && base.lowercase().endsWith(".json")

                    if (!isIkev2Backup && !isConfigName(base)) {
                        zip.closeEntry()
                        continue
                    }

                    val remaining = MAX_TOTAL_BYTES - total
                    if (remaining <= 0) {
                        Log.w(TAG, "archive exceeded the total size budget")
                        break
                    }

                    val bytes = readCapped(zip, minOf(MAX_ENTRY_BYTES.toLong(), remaining))
                    zip.closeEntry()
                    if (bytes == null) continue

                    total += bytes.size
                    val text = bytes.toString(Charsets.UTF_8)
                    if (text.isNotBlank()) {
                        found.add(
                            Entry(
                                name = base.substringBeforeLast('.', base),
                                text = text,
                                isIkev2Backup = isIkev2Backup,
                            )
                        )
                    }
                }
            }
            found
        }.getOrElse {
            Log.w(TAG, "could not read archive", it)
            // Whatever was recovered before the failure is still worth importing:
            // one corrupt entry at the end of an otherwise good archive should not
            // discard the profiles already parsed.
            found
        }
    }

    /**
     * Reads at most [limit] bytes, returning null if the entry is larger.
     *
     * Enforced WHILE streaming, not afterwards — the point of the cap is to never
     * hold an unbounded amount in memory, so checking the size after reading it
     * all would defeat itself. This is the zip-bomb defence.
     */
    private fun readCapped(zip: ZipInputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var read = 0L
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            read += n
            if (read > limit) {
                Log.w(TAG, "entry exceeds the size cap; skipping")
                return null
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Whether a file inside the archive is worth parsing.
     *
     * Extension-based, unlike the top-level import which sniffs content — inside
     * an archive the extension is the only cheap signal, and reading every JPEG
     * in a bundle just to sniff it would be wasteful. Anything unrecognised is
     * skipped silently rather than failing the whole import.
     */
    private fun isConfigName(base: String): Boolean {
        if (base.isBlank() || base.startsWith(".")) return false
        val lower = base.lowercase()
        return lower.endsWith(".conf") ||
            lower.endsWith(".ovpn") ||
            // SwiftVPN's own backup zips store Xray share links as .link.
            lower.endsWith(".link") ||
            lower.endsWith(".txt") ||
            // The WireGuard app names exports plainly; accept an extensionless
            // file rather than silently dropping it.
            !lower.contains('.')
    }
}
