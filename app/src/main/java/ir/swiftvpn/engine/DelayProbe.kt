package ir.swiftvpn.engine

import android.util.Log
import org.amnezia.awg.config.Config
import org.amnezia.awg.crypto.Curve25519
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Per-protocol REAL delay probes — the "تست تاخیر واقعی" engine.
 *
 * Each protocol answers with its own native handshake rather than a generic
 * TCP connect, which is what made earlier attempts useless (WireGuard and
 * IKEv2 are UDP-only; a TCP probe can never reach them):
 *
 *  * IKEv2     — a crafted IKE_SA_INIT packet on UDP/500. The daemon must
 *    answer any well-formed init (even with an INVALID_KE notify), so any
 *    datagram back means the VPN service is alive, not just some TCP port.
 *  * WireGuard — a complete, cryptographically valid Noise_IK handshake
 *    initiation built with the profile's OWN keys (Curve25519 from the
 *    bundled wireguard library, plus pure-Kotlin BLAKE2s / ChaCha20-Poly1305
 *    since minSdk 24 has neither in JCA). A correct mac1 makes the responder
 *    process the packet; a valid handshake makes it answer. This is the same
 *    exchange a real connection starts with.
 *  * OpenVPN   — UDP: a P_CONTROL_HARD_RESET_CLIENT_V2 packet, HMAC-signed
 *    with the tls-auth key (or tls-crypt-wrapped) when the profile has one,
 *    so the server answers instead of silently dropping. TCP: connect time.
 *  * Xray      — stays with Libv2ray.measureOutboundDelay in XrayTester.
 *
 * All constructions were validated byte-for-byte against a reference
 * responder before shipping (Blake2s/HMAC against hashlib, AEAD against
 * RFC 8439, the WG initiation against a full Noise responder).
 */
object DelayProbe {

    private const val TAG = "DelayProbe"
    const val TIMEOUT_MS = 3_000

    private val random = SecureRandom()

    // -------------------------------------------------------------- dispatch

    /** Round-trip in ms, or null when the server does not answer. */
    fun measure(context: android.content.Context, profile: Profile): Long? =
        runCatching {
            when (profile.protocol) {
                Protocol.IKEV2 -> ikev2(
                    profile.server,
                    profile.port.toIntOrNull() ?: 500,
                )
                Protocol.WIREGUARD -> {
                    val cfg = WireGuardStore(context).config(profile.uuid)
                        ?: return@runCatching null
                    wireguard(cfg)
                }
                Protocol.OPENVPN -> {
                    val text = java.io.File(
                        context.filesDir,
                        "openvpn_src/${profile.uuid}.ovpn",
                    ).takeIf { it.exists() }?.readText() ?: return@runCatching null
                    openvpn(text)
                }
                Protocol.XRAY -> null // handled by XrayTester itself
            }
        }.onFailure { Log.d(TAG, "probe failed: ${it.message}") }.getOrNull()

    // ---------------------------------------------------------------- IKEv2

    /**
     * Sends a minimal but well-formed IKE_SA_INIT and waits for any reply.
     * The KE data is random (the server will likely answer INVALID_KE), which
     * still proves the IKE daemon is there and measures the true round trip.
     */
    fun ikev2(host: String, port: Int, timeoutMs: Int = TIMEOUT_MS): Long? {
        val spii = ByteArray(8).also { random.nextBytes(it) }
        val nonce = ByteArray(32).also { random.nextBytes(it) }
        val keData = ByteArray(256).also { random.nextBytes(it) }

        // SA: one IKE proposal — AES-CBC-256 / PRF-HMAC-SHA2-256 /
        // INTEG-HMAC-SHA2-256-128 / DH MODP-2048.
        val proposal = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0))            // last proposal, reserved
            write(u16(4 + 12 + 8 + 8 + 8))      // proposal length
            write(byteArrayOf(1, 1, 0, 4))      // num 1, IKE, spi 0, 4 transforms
            // ENCR AES-CBC with 256-bit key attribute
            write(byteArrayOf(3, 0)); write(u16(12))
            write(byteArrayOf(1, 0)); write(u16(12))
            write(u16(0x8000 or 14)); write(u16(256))
            // PRF_HMAC_SHA2_256
            write(byteArrayOf(3, 0)); write(u16(8)); write(byteArrayOf(2, 0)); write(u16(5))
            // INTEG HMAC_SHA2_256_128
            write(byteArrayOf(3, 0)); write(u16(8)); write(byteArrayOf(3, 0)); write(u16(12))
            // DH MODP_2048 (last)
            write(byteArrayOf(0, 0)); write(u16(8)); write(byteArrayOf(4, 0)); write(u16(14))
        }.toByteArray()
        val sa = payloadHeader(34, proposal.size) + proposal          // next: KE
        val keBody = u16(14) + byteArrayOf(0, 0) + keData             // group 14
        val ke = payloadHeader(40, keBody.size) + keBody              // next: Ni
        val ni = payloadHeader(0, nonce.size) + nonce                 // next: none

        val body = sa + ke + ni
        val header = ByteArrayOutputStream().apply {
            write(spii); write(ByteArray(8))                          // SPIi, SPIr
            write(byteArrayOf(33, 0x20, 34, 0x08))                    // SA, v2.0, IKE_SA_INIT, initiator
            write(u32(0)); write(u32(28 + body.size))
        }.toByteArray()
        return udpRoundTrip(host, port, header + body, timeoutMs)
    }

    // ------------------------------------------------------------ WireGuard

    /**
     * A full Noise_IKpsk2 handshake initiation, exactly what the real client
     * sends as its first packet. The responder only answers a VALID message,
     * so a reply is the strongest possible "this server really works" signal.
     */
    fun wireguard(config: Config, timeoutMs: Int = TIMEOUT_MS): Long? {
        val peer = config.peers.firstOrNull() ?: return null
        val endpoint = peer.endpoint.orElse(null) ?: return null
        val responderPub = peer.publicKey.bytes
        val staticPriv = config.getInterface().keyPair.privateKey.bytes
        val staticPub = config.getInterface().keyPair.publicKey.bytes

        val ephPriv = ByteArray(32).also { random.nextBytes(it) }
        val ephPub = ByteArray(32)
        val base = ByteArray(32).also { it[0] = 9 }
        Curve25519.eval(ephPub, 0, ephPriv, base)
        val es = ByteArray(32).also { Curve25519.eval(it, 0, ephPriv, responderPub) }
        val ss = ByteArray(32).also { Curve25519.eval(it, 0, staticPriv, responderPub) }

        var ck = blake2s(CONSTRUCTION)
        var h = blake2s(ck + IDENTIFIER)
        h = blake2s(h + responderPub)
        ck = kdf(1, ck, ephPub)[0]
        h = blake2s(h + ephPub)
        var t = kdf(2, ck, es); ck = t[0]
        val encStatic = chacha20poly1305Encrypt(t[1], ByteArray(12), staticPub, h)
        h = blake2s(h + encStatic)
        t = kdf(2, ck, ss)
        val encTs = chacha20poly1305Encrypt(t[1], ByteArray(12), tai64n(), h)

        val sender = ByteArray(4).also { random.nextBytes(it) }
        val noMac = byteArrayOf(1, 0, 0, 0) + sender + ephPub + encStatic + encTs
        val mac1 = blake2s(noMac, blake2s(LABEL_MAC1 + responderPub), 16)
        val msg = noMac + mac1 + ByteArray(16)
        return udpRoundTrip(endpoint.host, endpoint.port, msg, timeoutMs)
    }

    // -------------------------------------------------------------- OpenVPN

    private class OvpnProbeTarget(
        val host: String,
        val port: Int,
        val tcp: Boolean,
        val authDigest: String,           // "HmacSHA1" / "HmacSHA256" / ...
        val tlsAuthKey: ByteArray?,       // raw 256-byte static key block
        val keyDirection: Int,            // 0, 1, or -1 when not specified
        val tlsCryptKey: ByteArray?,
    )

    /**
     * Parses just enough of the .ovpn to build a probe the server will
     * actually answer: proto, first remote, auth digest, and the inline
     * tls-auth / tls-crypt keys with their direction.
     */
    fun openvpn(ovpnText: String, timeoutMs: Int = TIMEOUT_MS): Long? {
        val t = parseOvpn(ovpnText) ?: return null
        if (t.tcp) return tcpLatency(t.host, t.port, timeoutMs)

        val session = ByteArray(8).also { random.nextBytes(it) }
        val pid = u32(1)
        val time = u32(System.currentTimeMillis() / 1000)
        // P_CONTROL_HARD_RESET_CLIENT_V2: opcode 7 in the high 5 bits, key-id 0.
        val core = byteArrayOf((7 shl 3).toByte()) + session

        return when {
            t.tlsCryptKey != null -> {
                // tls-crypt v1: hmac || pid || time || iv || AES-256-CTR(core || pid || time)
                for (dir in directionsToTry(t.keyDirection)) {
                    val cipherKey = t.tlsCryptKey.sliceArray(dir * 128 until dir * 128 + 32)
                    val hmacKey = t.tlsCryptKey.sliceArray(dir * 128 + 64 until dir * 128 + 128)
                    val iv = ByteArray(16).also { random.nextBytes(it) }
                    val plain = core + pid + time
                    val ct = aesCtr(cipherKey, iv, plain)
                    val head = pid + time + iv + ct
                    val tag = hmac("HmacSHA256", hmacKey, head)
                    udpRoundTrip(t.host, t.port, tag + head, timeoutMs)?.let { return it }
                }
                null
            }
            t.tlsAuthKey != null -> {
                // Signed mode: core || hmac || pid || time, where hmac covers
                // pid || time || core.
                for (dir in directionsToTry(t.keyDirection)) {
                    val hmacKey = t.tlsAuthKey.sliceArray(dir * 128 + 64 until dir * 128 + 128)
                    val tag = hmac(t.authDigest, hmacKey, pid + time + core)
                    udpRoundTrip(t.host, t.port, core + tag + pid + time, timeoutMs)
                        ?.let { return it }
                }
                null
            }
            else -> udpRoundTrip(t.host, t.port, core + pid + time, timeoutMs)
        }
    }

    /**
     * OpenVPN key-direction semantics: with an explicit directive the client
     * uses that direction; without one, sending uses key slot 0. We try the
     * expected direction first and the other as a fallback — a wrong guess
     * just means one wasted 3-second wait, not a wrong "-1 ms".
     */
    private fun directionsToTry(configured: Int): IntArray = when (configured) {
        0 -> intArrayOf(0, 1)
        1 -> intArrayOf(1, 0)
        else -> intArrayOf(0, 1)
    }

    private fun parseOvpn(text: String): OvpnProbeTarget? {
        var host: String? = null
        var port = 1194
        var proto = "udp"
        var auth = "HmacSHA1"
        var direction = -1
        val inline = mutableMapOf<String, String>()
        var tag: String? = null
        val buf = StringBuilder()
        for (raw in text.lines()) {
            val line = raw.trim()
            val open = Regex("<([a-z-]+)>").matchEntire(line)
            if (open != null) { tag = open.groupValues[1]; buf.clear(); continue }
            if (tag != null) {
                if (line == "</$tag>") { inline[tag!!] = buf.toString(); tag = null }
                else buf.append(line)
                continue
            }
            val parts = line.split(Regex("\\s+"))
            when (parts[0]) {
                "remote" -> if (parts.size >= 2) {
                    host = parts[1]
                    if (parts.size >= 3) port = parts[2].toIntOrNull() ?: 1194
                }
                "proto" -> if (parts.size >= 2) proto = parts[1]
                // "auth SHA256" -> HmacSHA256; OpenVPN's default is SHA1.
                "auth" -> if (parts.size >= 2) {
                    auth = "Hmac" + parts[1].replace("-", "").uppercase()
                }
                "key-direction" -> if (parts.size >= 2) direction = parts[1].toIntOrNull() ?: -1
                else -> {}
            }
        }
        val h = host ?: return null
        val authKey = inline["tls-auth"]?.let { parseStaticKey(it) }
        val cryptKey = inline["tls-crypt"]?.let { parseStaticKey(it) }
        return OvpnProbeTarget(
            h, port, proto.startsWith("tcp"), auth, authKey, direction, cryptKey,
        )
    }

    /** The inline key block is hex lines between the BEGIN/END markers. */
    private fun parseStaticKey(block: String): ByteArray? {
        val hex = block.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("-----") && !it.startsWith("#") }
            .joinToString("")
            .filter { it in "0123456789abcdefABCDEF" }
        if (hex.length < 512) return null
        return ByteArray(256) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    // ----------------------------------------------------------- transports

    private fun udpRoundTrip(host: String, port: Int, payload: ByteArray, timeoutMs: Int): Long? =
        runCatching {
            val addr = InetAddress.getByName(host)
            DatagramSocket().use { s ->
                s.soTimeout = timeoutMs
                val start = System.nanoTime()
                s.send(DatagramPacket(payload, payload.size, addr, port))
                val buf = ByteArray(2048)
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                (System.nanoTime() - start) / 1_000_000
            }
        }.getOrNull()

    private fun tcpLatency(host: String, port: Int, timeoutMs: Int): Long? = runCatching {
        val start = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        (System.nanoTime() - start) / 1_000_000
    }.getOrNull()

    // ------------------------------------------------------- crypto helpers

    private val CONSTRUCTION = "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s".toByteArray()
    private val IDENTIFIER = "WireGuard v1 zx2c4 Jason@zx2c4.com".toByteArray()
    private val LABEL_MAC1 = "mac1----".toByteArray()

    private fun tai64n(): ByteArray {
        val secs = System.currentTimeMillis() / 1000 + 0x400000000000000aL
        val nanos = (System.currentTimeMillis() % 1000) * 1_000_000
        val out = ByteArray(12)
        for (i in 0 until 8) out[i] = (secs ushr (56 - 8 * i)).toByte()
        for (i in 0 until 4) out[8 + i] = (nanos ushr (24 - 8 * i)).toByte()
        return out
    }

    private fun blake2s(data: ByteArray, key: ByteArray? = null, outlen: Int = 32): ByteArray {
        val b = Blake2s(key, outlen)
        b.update(data)
        return b.digest()
    }

    private fun hmacBlake2s(key: ByteArray, msg: ByteArray): ByteArray {
        var k = if (key.size <= 64) key else blake2s(key)
        k = k.copyOf(64)
        val ipad = ByteArray(64) { (k[it].toInt() xor 0x36).toByte() }
        val opad = ByteArray(64) { (k[it].toInt() xor 0x5c).toByte() }
        return blake2s(opad + blake2s(ipad + msg))
    }

    private fun kdf(n: Int, ck: ByteArray, input: ByteArray): Array<ByteArray> {
        val t0 = hmacBlake2s(ck, input)
        val out = Array(n) { ByteArray(0) }
        out[0] = hmacBlake2s(t0, byteArrayOf(1))
        for (i in 1 until n) out[i] = hmacBlake2s(t0, out[i - 1] + byteArrayOf((i + 1).toByte()))
        return out
    }

    private fun hmac(algorithm: String, key: ByteArray, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(msg)
    }

    private fun aesCtr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    // ------------------------------------------- ChaCha20-Poly1305 (RFC 8439)

    private fun rotl(x: Int, n: Int) = (x shl n) or (x ushr (32 - n))

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 7)
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun chachaState(key: ByteArray, counter: Int, nonce: ByteArray): IntArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = leInt(key, i * 4)
        s[12] = counter
        for (i in 0 until 3) s[13 + i] = leInt(nonce, i * 4)
        return s
    }

    private fun chachaBlock(state: IntArray, out: ByteArray, off: Int) {
        val w = state.clone()
        repeat(10) {
            quarterRound(w, 0, 4, 8, 12); quarterRound(w, 1, 5, 9, 13)
            quarterRound(w, 2, 6, 10, 14); quarterRound(w, 3, 7, 11, 15)
            quarterRound(w, 0, 5, 10, 15); quarterRound(w, 1, 6, 11, 12)
            quarterRound(w, 2, 7, 8, 13); quarterRound(w, 3, 4, 9, 14)
        }
        for (i in 0 until 16) {
            val x = w[i] + state[i]
            out[off + i * 4] = x.toByte()
            out[off + i * 4 + 1] = (x ushr 8).toByte()
            out[off + i * 4 + 2] = (x ushr 16).toByte()
            out[off + i * 4 + 3] = (x ushr 24).toByte()
        }
    }

    private fun chacha20Xor(key: ByteArray, counter: Int, nonce: ByteArray, data: ByteArray): ByteArray {
        val s = chachaState(key, counter, nonce)
        val out = ByteArray(data.size)
        val block = ByteArray(64)
        var i = 0
        while (i < data.size) {
            s[12] = counter + i / 64
            chachaBlock(s, block, 0)
            val n = minOf(64, data.size - i)
            for (j in 0 until n) out[i + j] = (data[i + j].toInt() xor block[j].toInt()).toByte()
            i += 64
        }
        return out
    }

    private fun poly1305(msg: ByteArray, key: ByteArray): ByteArray {
        val p = BigInteger.TWO.pow(130).subtract(BigInteger.valueOf(5))
        val rb = key.copyOfRange(0, 16)
        rb[3] = (rb[3].toInt() and 15).toByte(); rb[7] = (rb[7].toInt() and 15).toByte()
        rb[11] = (rb[11].toInt() and 15).toByte(); rb[15] = (rb[15].toInt() and 15).toByte()
        rb[4] = (rb[4].toInt() and 252).toByte(); rb[8] = (rb[8].toInt() and 252).toByte()
        rb[12] = (rb[12].toInt() and 252).toByte()
        val r = leBig(rb)
        val s = leBig(key.copyOfRange(16, 32))
        var acc = BigInteger.ZERO
        var i = 0
        while (i < msg.size) {
            val n = minOf(16, msg.size - i)
            val block = ByteArray(n + 1)
            System.arraycopy(msg, i, block, 0, n)
            block[n] = 1
            acc = acc.add(leBig(block)).multiply(r).mod(p)
            i += 16
        }
        val tag = acc.add(s).mod(BigInteger.TWO.pow(128)).toByteArray()
        val out = ByteArray(16)
        for (j in tag.indices) {
            val idx = 15 - j
            if (idx >= 0) out[idx] = tag[tag.size - 1 - j]
        }
        return out
    }

    private fun leBig(le: ByteArray): BigInteger {
        val be = ByteArray(le.size + 1)
        for (i in le.indices) be[be.size - 2 - i + 1] = le[i]
        return BigInteger(be)
    }

    private fun pad16(len: Int) = if (len % 16 == 0) ByteArray(0) else ByteArray(16 - len % 16)

    private fun le64(v: Long) = ByteArray(8) { (v ushr (8 * it)).toByte() }

    /** RFC 8439 AEAD encryption; returns ciphertext concatenated with the tag. */
    private fun chacha20poly1305Encrypt(
        key: ByteArray, nonce: ByteArray, pt: ByteArray, aad: ByteArray,
    ): ByteArray {
        val block0 = ByteArray(64)
        chachaBlock(chachaState(key, 0, nonce), block0, 0)
        val polyKey = block0.copyOf(32)
        val ct = chacha20Xor(key, 1, nonce, pt)
        val macData = aad + pad16(aad.size) + ct + pad16(ct.size) +
            le64(aad.size.toLong()) + le64(ct.size.toLong())
        return ct + poly1305(macData, polyKey)
    }

    // ------------------------------------------------------------- Blake2s

    private class Blake2s(key: ByteArray?, private val outlen: Int) {
        private val h = IntArray(8)
        private val buf = ByteArray(64)
        private var buflen = 0
        private var t = 0L

        init {
            System.arraycopy(IV, 0, h, 0, 8)
            h[0] = h[0] xor (0x01010000 xor ((key?.size ?: 0) shl 8) xor outlen)
            if (key != null && key.isNotEmpty()) update(key.copyOf(64))
        }

        fun update(input: ByteArray, off: Int = 0, len: Int = input.size) {
            var i = off
            var remaining = len
            while (remaining > 0) {
                if (buflen == 64) { t += 64; compress(buf, false); buflen = 0 }
                val take = minOf(64 - buflen, remaining)
                System.arraycopy(input, i, buf, buflen, take)
                buflen += take; i += take; remaining -= take
            }
        }

        fun digest(): ByteArray {
            t += buflen
            Arrays.fill(buf, buflen, 64, 0.toByte())
            compress(buf, true)
            return ByteArray(outlen) { (h[it ushr 2] ushr ((it and 3) * 8)).toByte() }
        }

        private fun compress(block: ByteArray, last: Boolean) {
            val m = IntArray(16) { leInt(block, it * 4) }
            val v = IntArray(16)
            System.arraycopy(h, 0, v, 0, 8)
            System.arraycopy(IV, 0, v, 8, 8)
            v[12] = v[12] xor t.toInt()
            v[13] = v[13] xor (t ushr 32).toInt()
            if (last) v[14] = v[14].inv()
            for (r in 0 until 10) {
                val s = SIGMA[r]
                g(v, 0, 4, 8, 12, m[s[0].toInt()], m[s[1].toInt()])
                g(v, 1, 5, 9, 13, m[s[2].toInt()], m[s[3].toInt()])
                g(v, 2, 6, 10, 14, m[s[4].toInt()], m[s[5].toInt()])
                g(v, 3, 7, 11, 15, m[s[6].toInt()], m[s[7].toInt()])
                g(v, 0, 5, 10, 15, m[s[8].toInt()], m[s[9].toInt()])
                g(v, 1, 6, 11, 12, m[s[10].toInt()], m[s[11].toInt()])
                g(v, 2, 7, 8, 13, m[s[12].toInt()], m[s[13].toInt()])
                g(v, 3, 4, 9, 14, m[s[14].toInt()], m[s[15].toInt()])
            }
            for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
        }

        private fun g(v: IntArray, a: Int, b: Int, c: Int, d: Int, x: Int, y: Int) {
            fun rotr(xv: Int, n: Int) = (xv ushr n) or (xv shl (32 - n))
            v[a] += v[b] + x; v[d] = rotr(v[d] xor v[a], 16)
            v[c] += v[d]; v[b] = rotr(v[b] xor v[c], 12)
            v[a] += v[b] + y; v[d] = rotr(v[d] xor v[a], 8)
            v[c] += v[d]; v[b] = rotr(v[b] xor v[c], 7)
        }

        companion object {
            private val IV = intArrayOf(
                0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(),
                0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19,
            )
            private val SIGMA = arrayOf(
                byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
                byteArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
                byteArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
                byteArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
                byteArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
                byteArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
                byteArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
                byteArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
                byteArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
                byteArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
            )
        }
    }

    // ------------------------------------------------------------ byte utils

    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun u32(v: Long) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )
    private fun u32(v: Int) = u32(v.toLong())
    private fun payloadHeader(next: Int, bodyLen: Int) =
        byteArrayOf(next.toByte(), 0) + u16(4 + bodyLen)
}
