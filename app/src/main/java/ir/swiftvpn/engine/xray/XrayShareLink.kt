package ir.swiftvpn.engine.xray

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Parses the share-link formats a user pastes — vless://, vmess://,
 * trojan://, ss://, hysteria2:// (hy2://) — into a normalised
 * [XrayOutbound].
 *
 * Why a hand-written parser: there is no canonical library for these formats,
 * they are defined by convention (the XTLS discussion #716 for VLESS, the
 * v2rayN base64-JSON blob for VMess, SIP002 for Shadowsocks), and every panel
 * emits slight variations. So this is deliberately lenient — it fills sensible
 * defaults and never throws on a missing optional field, returning null only
 * when the link is genuinely unusable.
 *
 * The parsed form is intentionally flat and protocol-tagged; [XrayConfig] turns
 * it into the actual Xray outbound + streamSettings JSON. Keeping parse and
 * build separate means the tricky base64/URL-decoding lives in one place and the
 * JSON shape in another.
 */
object XrayShareLink {

    /**
     * True when [text] looks like an Xray share link, used to route a pasted
     * string or imported file to this engine rather than OpenVPN/WireGuard.
     */
    fun looksLikeShareLink(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("vless://", true) ||
            t.startsWith("vmess://", true) ||
            t.startsWith("trojan://", true) ||
            t.startsWith("ss://", true) ||
            t.startsWith("socks://", true) ||
            t.startsWith("hysteria2://", true) ||
            t.startsWith("hy2://", true)
    }

    /**
     * Every share link in [text], one per entry. Handles:
     *  - several links pasted together (newline/whitespace separated)
     *  - a base64 blob wrapping that list (the subscription-export format)
     *  - a single link (returns a one-element list)
     */
    fun extractLinks(text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()

        fun linksIn(blob: String): List<String> =
            blob.split(Regex("\\s+")).map { it.trim() }
                .filter { looksLikeShareLink(it) }

        val direct = linksIn(t)
        if (direct.isNotEmpty()) return direct

        // Whole clipboard might be base64 of a newline-separated link list.
        val decoded = runCatching {
            String(decodeBase64(t), StandardCharsets.UTF_8)
        }.getOrNull() ?: return emptyList()
        return linksIn(decoded)
    }

    /** Parses a single link, or null if it cannot be understood. */
    fun parse(raw: String): XrayOutbound? {
        val link = raw.trim()
        // Panels and exports are inconsistent about URL-encoding: passwords,
        // names and SS userinfo may arrive percent-encoded or with the whole
        // link double-encoded. Try the raw form first, then the decoded form,
        // then the raw form with a decoded fragment — the first that yields a
        // usable outbound wins.
        return sequenceOf(link, urlDecode(link), link.withDecodedFragment())
            .distinct()
            .mapNotNull { parseOnce(it) }
            .firstOrNull()
    }

    private fun parseOnce(link: String): XrayOutbound? = runCatching {
        when {
            link.startsWith("vless://", true) -> parseVless(link)
            link.startsWith("vmess://", true) -> parseVmess(link)
            link.startsWith("trojan://", true) -> parseTrojan(link)
            link.startsWith("ss://", true) -> parseShadowsocks(link)
            link.startsWith("hysteria2://", true) -> parseHysteria2(link)
            link.startsWith("hy2://", true) -> parseHysteria2(link)
            link.startsWith("socks://", true) -> parseSocksHttp(link, "socks")
            // http:// is deliberately NOT advertised by looksLikeShareLink (a
            // pasted subscription URL must never be mistaken for a proxy), but
            // the manual-create flow stores this form, so it must parse.
            link.startsWith("http://", true) -> parseSocksHttp(link, "http")
            else -> null
        }
    }.getOrNull()

    /** Same link with only the #fragment percent-decoded. */
    private fun String.withDecodedFragment(): String {
        val i = indexOf('#')
        return if (i < 0) this else substring(0, i + 1) + urlDecode(substring(i + 1))
    }

    // -------------------------------------------------------------- VLESS

    /**
     * vless://{uuid}@{host}:{port}?{query}#{name}
     *
     * The query carries everything about transport and security: type, security,
     * sni, fp, pbk/sid/spx (Reality), flow, host, path, serviceName, headerType.
     */
    private fun parseVless(link: String): XrayOutbound? {
        val uri = URI(link)
        val uuid = uri.userInfo ?: return null
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val q = queryMap(uri.rawQuery)

        return XrayOutbound(
            protocol = "vless",
            uuid = urlDecode(uuid),
            name = fragmentName(uri, "$host:$port"),
            address = host,
            port = port,
            flow = q["flow"].orEmpty(),
            encryption = q["encryption"].ifNullOrBlank("none"),
            stream = streamFrom(q),
        )
    }

    // -------------------------------------------------------------- VMess

    /**
     * vmess://{base64(json)} — the v2rayN blob. Fields: add, port, id, aid, scy,
     * net, type, host, path, tls, sni, alpn, fp, ps.
     *
     * A newer query-string VMess form exists too; it mirrors the VLESS layout,
     * so it is handled by falling through to the VLESS-style parser when the body
     * is not valid base64 JSON.
     */
    private fun parseVmess(link: String): XrayOutbound? {
        val body = link.removePrefix("vmess://").removePrefix("VMESS://").trim()

        val json = runCatching {
            JSONObject(String(decodeBase64(body), StandardCharsets.UTF_8))
        }.getOrNull()

        if (json == null) {
            // Query-string variant: vmess://{uuid}@host:port?...  Treat like VLESS
            // but with protocol vmess.
            return runCatching {
                val uri = URI("vmess://$body")
                val uuid = uri.userInfo ?: return null
                val host = uri.host ?: return null
                val port = uri.port.takeIf { it > 0 } ?: 443
                val q = queryMap(uri.rawQuery)
                XrayOutbound(
                    protocol = "vmess",
                    name = fragmentName(uri, "$host:$port"),
                    address = host,
                    port = port,
                    uuid = urlDecode(uuid),
                    security = q["scy"].ifNullOrBlank("auto"),
                    stream = streamFrom(q),
                )
            }.getOrNull()
        }

        val host = json.optString("add").ifBlank { return null }
        val port = json.optString("port").toIntOrNull() ?: 443
        val net = json.optString("net", "tcp")
        val tls = json.optString("tls")

        val stream = XrayStream(
            network = net.ifBlank { "tcp" },
            security = if (tls.equals("tls", true) || tls.equals("reality", true)) tls else "none",
            sni = json.optString("sni").ifBlank { json.optString("host") },
            fingerprint = json.optString("fp"),
            alpn = json.optString("alpn"),
            host = json.optString("host"),
            path = json.optString("path"),
            headerType = json.optString("type", "none"),
            serviceName = json.optString("path"),
        )

        return XrayOutbound(
            protocol = "vmess",
            name = json.optString("ps").ifBlank { "$host:$port" },
            address = host,
            port = port,
            uuid = json.optString("id"),
            alterId = json.optString("aid").toIntOrNull() ?: 0,
            security = json.optString("scy").ifBlank { "auto" },
            stream = stream,
        )
    }

    // -------------------------------------------------------------- Trojan

    /** trojan://{password}@{host}:{port}?{query}#{name} */
    private fun parseTrojan(link: String): XrayOutbound? {
        val uri = URI(link)
        val password = urlDecode(uri.userInfo ?: return null)
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val q = queryMap(uri.rawQuery)

        // Trojan implies TLS unless the query says otherwise.
        val stream = streamFrom(q).let {
            if (it.security.isBlank() || it.security == "none") it.copy(security = "tls") else it
        }

        return XrayOutbound(
            protocol = "trojan",
            name = fragmentName(uri, "$host:$port"),
            address = host,
            port = port,
            password = password,
            flow = q["flow"].orEmpty(),
            stream = stream,
        )
    }

    // -------------------------------------------------------------- Shadowsocks

    /**
     * SIP002: ss://{base64(method:password)}@{host}:{port}#{name}
     * Legacy:  ss://{base64(method:password@host:port)}#{name}
     */
    private fun parseShadowsocks(link: String): XrayOutbound? {
        // SIP003 plugins (v2ray-plugin, obfs, …) need a separate binary we do
        // not ship, so a plugin link would parse into a plain SS outbound that
        // silently fails to pass traffic. Reject it outright instead — a clear
        // import error beats a profile that connects but does nothing.
        if (link.contains("plugin=", ignoreCase = true)) return null

        val afterScheme = link.removePrefix("ss://").removePrefix("SS://")
        val hashIdx = afterScheme.indexOf('#')
        val name = if (hashIdx >= 0) urlDecode(afterScheme.substring(hashIdx + 1)) else ""
        val core = if (hashIdx >= 0) afterScheme.substring(0, hashIdx) else afterScheme

        val atIdx = core.lastIndexOf('@')
        if (atIdx >= 0) {
            // SIP002: userinfo is base64(method:password), then host:port
            val userInfo = core.substring(0, atIdx)
            val hostPort = urlDecode(core.substring(atIdx + 1).substringBefore('?'))
            val methodPass = String(decodeBase64(userInfo), StandardCharsets.UTF_8)
            val method = methodPass.substringBefore(':')
            val password = methodPass.substringAfter(':', "")
            // SS2022 over UDP needs the version marker; some links say so.
            val uot = if (core.contains("uot=2", true) || method.startsWith("2022-")) 2 else 0
            val host = hostPort.substringBeforeLast(':')
            val port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
            return XrayOutbound(
                protocol = "shadowsocks",
                name = name.ifBlank { "$host:$port" },
                address = host,
                port = port,
                method = method,
                password = password,
                uotVersion = uot,
            )
        }

        // Legacy: whole thing is base64(method:password@host:port)
        val decoded = String(decodeBase64(core.substringBefore('#')), StandardCharsets.UTF_8)
        val credAt = decoded.lastIndexOf('@')
        if (credAt < 0) return null
        val methodPass = decoded.substring(0, credAt)
        val hostPort = decoded.substring(credAt + 1)
        return XrayOutbound(
            protocol = "shadowsocks",
            name = name.ifBlank { hostPort },
            address = hostPort.substringBeforeLast(':'),
            port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null,
            method = methodPass.substringBefore(':'),
            password = methodPass.substringAfter(':', ""),
        )
    }

    // -------------------------------------------------------------- Hysteria2

    /**
     * hysteria2://{password}@{host}:{port}?{query}#{name}   (hy2:// alias)
     *
     * Supported since xray-core 24.9. Query carries sni, insecure, obfs
     * (salamander) + obfs-password, pinSHA256. The stream security is always
     * TLS underneath — Hysteria2 has no plaintext mode.
     */
    private fun parseHysteria2(link: String): XrayOutbound? {
        val uri = URI(link)
        val password = uri.userInfo ?: return null
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val q = queryMap(uri.rawQuery)

        return XrayOutbound(
            protocol = "hysteria2",
            name = fragmentName(uri, "$host:$port"),
            address = host,
            port = port,
            password = urlDecode(password),
            stream = XrayStream(
                network = "hysteria2",
                security = "tls",
                sni = q["sni"].orEmpty(),
                fingerprint = q["fp"].orEmpty(),
                alpn = q["alpn"].orEmpty(),
                host = q["obfs-password"].orEmpty(), // reused: obfs password
                path = q["obfs"].orEmpty(),          // reused: obfs type (salamander)
                headerType = q["insecure"].orEmpty(), // reused: "1" = skip verify
            ),
        )
    }

    // -------------------------------------------------------------- helpers

    /**
     * socks://[user:pass@]host:port[#name]  and the http:// twin.
     *
     * No single RFC owns these; the plain-URI form is used because it
     * round-trips through [toLink] losslessly and stays readable when the user
     * opens the raw view. Login is optional on both.
     */
    private fun parseSocksHttp(link: String, protocol: String): XrayOutbound? {
        val uri = URI(link)
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: if (protocol == "socks") 1080 else 8080
        val userInfo = uri.userInfo?.let { urlDecode(it) }.orEmpty()
        return XrayOutbound(
            protocol = protocol,
            name = uri.fragment?.let { urlDecode(it) }?.ifBlank { null } ?: "$host:$port",
            address = host,
            port = port,
            username = userInfo.substringBefore(':', ""),
            password = userInfo.substringAfter(':', ""),
        )
    }

    /** Builds a [XrayStream] from a VLESS/Trojan-style query map. */
    private fun streamFrom(q: Map<String, String>): XrayStream {
        val net = q["type"].ifNullOrBlank("tcp")
        return XrayStream(
            network = net,
            security = q["security"].ifNullOrBlank("none"),
            sni = q["sni"].ifNullOrBlank(q["peer"].orEmpty()),
            fingerprint = q["fp"].orEmpty(),
            alpn = q["alpn"].orEmpty(),
            host = q["host"].orEmpty(),
            path = q["path"].ifNullOrBlank("/"),
            headerType = q["headerType"].ifNullOrBlank("none"),
            serviceName = q["serviceName"].orEmpty(),
            // Reality
            publicKey = q["pbk"].orEmpty(),
            shortId = q["sid"].orEmpty(),
            spiderX = q["spx"].orEmpty(),
            // Post-quantum Reality (Xray 25.8+)
            pqv = q["pqv"].orEmpty(),
            // xhttp / splithttp (Xray 24.11+)
            mode = q["mode"].orEmpty(),
            extra = q["extra"].orEmpty(),
        )
    }

    private fun fragmentName(uri: URI, fallback: String): String =
        uri.fragment?.let { urlDecode(it) }?.ifBlank { fallback } ?: fallback

    private fun queryMap(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i < 0) return@mapNotNull null
            urlDecode(pair.substring(0, i)) to urlDecode(pair.substring(i + 1))
        }.toMap()
    }

    private fun urlDecode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /**
     * Decodes base64 in whichever flavour the link used: standard or URL-safe,
     * padded or not. Panels are inconsistent, so all four combinations are
     * accepted.
     */
    private fun decodeBase64(input: String): ByteArray {
        val s = input.trim().replace('-', '+').replace('_', '/')
        val padded = when (s.length % 4) {
            2 -> "$s=="
            3 -> "$s="
            else -> s
        }
        return Base64.decode(padded, Base64.DEFAULT)
    }

    private fun String?.ifNullOrBlank(default: String): String =
        if (this.isNullOrBlank()) default else this
    // ------------------------------------------------------------ serializer

    /**
     * Rebuilds a standards-compliant share link from an [XrayOutbound] — the
     * inverse of [parse], powering the structured profile editor. Keys match
     * [streamFrom] exactly so a saved link re-parses to the same outbound; the
     * editor proves that round-trip before storing anything.
     */
    fun toLink(o: XrayOutbound): String? = runCatching {
        require(o.address.isNotBlank() && o.port > 0)
        val frag = "#" + urlEncode(o.name.ifBlank { "${o.address}:${o.port}" })
        when (o.protocol) {
            "vless" -> {
                val q = linkedMapOf("encryption" to o.encryption.ifBlank { "none" })
                if (o.flow.isNotBlank()) q["flow"] = o.flow
                q += streamQuery(o.stream)
                "vless://${urlEncode(o.uuid)}@${o.address}:${o.port}?${renderQuery(q)}$frag"
            }
            "trojan" -> {
                val qs = renderQuery(streamQuery(o.stream))
                "trojan://${urlEncode(o.password)}@${o.address}:${o.port}" +
                    (if (qs.isEmpty()) "" else "?$qs") + frag
            }
            "hysteria2" -> {
                val q = linkedMapOf<String, String>()
                if (o.stream.sni.isNotBlank()) q["sni"] = o.stream.sni
                if (o.stream.headerType == "1") q["insecure"] = "1"  // reused slot
                if (o.stream.path.isNotBlank() && o.stream.path != "/") {
                    q["obfs"] = o.stream.path                        // reused slot
                    if (o.stream.host.isNotBlank()) q["obfs-password"] = o.stream.host
                }
                val qs = if (q.isEmpty()) "" else "?" + renderQuery(q)
                "hysteria2://${urlEncode(o.password)}@${o.address}:${o.port}$qs$frag"
            }
            "shadowsocks" -> {
                // SIP002: userinfo is base64url(method:password), no plugin.
                val user = Base64.encodeToString(
                    "${o.method}:${o.password}".toByteArray(StandardCharsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                )
                "ss://$user@${o.address}:${o.port}$frag"
            }
            "socks", "http" -> {
                val creds = when {
                    o.username.isBlank() -> ""
                    o.password.isBlank() -> urlEncode(o.username) + "@"
                    else -> urlEncode(o.username) + ":" + urlEncode(o.password) + "@"
                }
                "${o.protocol}://$creds${o.address}:${o.port}$frag"
            }
            "vmess" -> {
                val st = o.stream
                val json = JSONObject()
                    .put("v", "2")
                    .put("ps", o.name.ifBlank { "${o.address}:${o.port}" })
                    .put("add", o.address)
                    .put("port", o.port.toString())
                    .put("id", o.uuid)
                    .put("aid", o.alterId.toString())
                    .put("scy", o.security.ifBlank { "auto" })
                    .put("net", st.network.ifBlank { "tcp" })
                    .put("type", st.headerType.ifBlank { "none" })
                    .put("host", st.host)
                    .put("path", st.path.ifBlank { "/" })
                    .put("tls", if (st.security == "tls" || st.security == "reality") "tls" else "")
                    .put("sni", st.sni)
                    .put("alpn", st.alpn)
                    .put("fp", st.fingerprint)
                "vmess://" + Base64.encodeToString(
                    json.toString().toByteArray(StandardCharsets.UTF_8),
                    Base64.NO_WRAP,
                )
            }
            else -> null
        }
    }.getOrNull()

    /** Query keys exactly as [streamFrom] expects to read them back. */
    private fun streamQuery(st: XrayStream): LinkedHashMap<String, String> {
        val q = linkedMapOf<String, String>()
        val net = st.network.ifBlank { "tcp" }
        if (net != "tcp") q["type"] = net
        if (st.security.isNotBlank() && st.security != "none") q["security"] = st.security
        if (st.sni.isNotBlank()) q["sni"] = st.sni
        if (st.fingerprint.isNotBlank()) q["fp"] = st.fingerprint
        if (st.alpn.isNotBlank()) q["alpn"] = st.alpn
        when (net) {
            "ws", "xhttp", "splithttp", "http" -> {
                q["path"] = st.path.ifBlank { "/" }
                if (st.host.isNotBlank()) q["host"] = st.host
            }
            "grpc" -> if (st.serviceName.isNotBlank()) q["serviceName"] = st.serviceName
            "tcp" -> if (st.headerType.isNotBlank() && st.headerType != "none") {
                q["headerType"] = st.headerType
            }
        }
        if (st.security == "reality") {
            if (st.publicKey.isNotBlank()) q["pbk"] = st.publicKey
            if (st.shortId.isNotBlank()) q["sid"] = st.shortId
            if (st.spiderX.isNotBlank()) q["spx"] = st.spiderX
            if (st.pqv.isNotBlank()) q["pqv"] = st.pqv
        }
        if (net == "xhttp" || net == "splithttp") {
            if (st.mode.isNotBlank()) q["mode"] = st.mode
            if (st.extra.isNotBlank()) q["extra"] = st.extra
        }
        return q
    }

    private fun renderQuery(q: Map<String, String>): String =
        q.entries.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }

    private fun urlEncode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

}
