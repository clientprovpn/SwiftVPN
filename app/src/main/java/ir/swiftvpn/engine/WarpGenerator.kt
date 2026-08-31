package ir.swiftvpn.engine

import android.util.Log
import org.amnezia.awg.crypto.KeyPair
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Cloudflare WARP profile generator.
 *
 * WARP is plain WireGuard on our side: the device registers a freshly
 * generated public key with Cloudflare's registration endpoint and receives
 * the interface addresses to use. The result is an ordinary wg-quick config
 * that our existing WireGuard engine imports unchanged — no new engine, no
 * new native code (the Ninety client follows the same pattern).
 *
 * Network failures are reported as null; the caller shows an error. This
 * must be called off the main thread.
 */
object WarpGenerator {

    private const val TAG = "WarpGenerator"
    private const val REG_URL = "https://api.cloudflareclient.com/v0a2483/reg"
    private const val FALLBACK_ENDPOINT = "engage.cloudflareclient.com:2408"
    private const val FALLBACK_PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

    /**
     * Registers a new WARP device and returns its wg-quick config text,
     * or null on any network/parse failure.
     */
    fun generate(): String? {
        val keys = runCatching { KeyPair() }.getOrElse {
            Log.w(TAG, "keygen failed", it)
            return null
        }
        val response = register(keys.publicKey.toBase64()) ?: return null
        return runCatching { buildConfig(keys, response) }
            .onFailure { Log.w(TAG, "unexpected reg response: ${it.message}") }
            .getOrNull()
    }

    /** POSTs the public key to the registration endpoint; returns the parsed JSON body. */
    private fun register(publicKeyBase64: String): JSONObject? {
        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val body = JSONObject()
            .put("key", publicKeyBase64)
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", tos)
            .put("model", "Android")
            .put("serial_number", "")
            .put("locale", "en_US")
            .toString()

        var conn: HttpURLConnection? = null
        try {
        return runCatching {
            conn = (URL(REG_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("User-Agent", "okhttp/3.12.1")
                setRequestProperty("CF-Client-Version", "a-6.81-2410012351.0")
            }
            OutputStreamWriter(conn!!.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn!!.responseCode
            val text = BufferedReader(
                InputStreamReader(
                    if (code in 200..299) conn!!.inputStream else conn!!.errorStream,
                    Charsets.UTF_8,
                ),
            ).use { it.readText() }
            if (code !in 200..299) {
                Log.w(TAG, "reg HTTP $code: ${text.take(200)}")
                return null
            }
            JSONObject(text)
        }.getOrElse {
            Log.w(TAG, "reg request failed", it)
            null
        }
        } finally {
            conn?.disconnect()
        }
    }

    /** Shapes the registration response into a wg-quick document. */
    private fun buildConfig(keys: KeyPair, reg: JSONObject): String {
        val config = reg.getJSONObject("result").getJSONObject("config")
        val iface = config.getJSONObject("interface")
        val addresses = iface.getJSONObject("addresses")
        val v4 = addresses.getString("v4")
        val v6 = addresses.getString("v6")

        val peer = config.getJSONArray("peers").getJSONObject(0)
        val peerKey = peer.optString("public_key").ifBlank { FALLBACK_PEER_KEY }
        val endpoint = peer.optJSONObject("endpoint")
            ?.optString("host")
            ?.ifBlank { null }
            ?: FALLBACK_ENDPOINT

        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${keys.privateKey.toBase64()}")
            appendLine("Address = $v4, $v6")
            appendLine("DNS = 1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001")
            appendLine("MTU = 1280")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $peerKey")
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            appendLine("Endpoint = $endpoint")
        }
    }
}
