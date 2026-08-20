package ir.swiftvpn.engine.xray

/**
 * A normalised proxy server, parsed from a share link.
 *
 * Flat by design: it holds the union of fields across vless/vmess/trojan/ss so
 * the parser has one target and the config builder has one source. Fields that
 * do not apply to a given protocol stay at their defaults and are simply not
 * emitted into the JSON.
 */
data class XrayOutbound(
    val protocol: String,          // vless | vmess | trojan | shadowsocks
    val name: String,
    val address: String,
    val port: Int,
    // vless / vmess
    val uuid: String = "",
    val flow: String = "",         // vless xtls flow, e.g. xtls-rprx-vision
    val encryption: String = "none", // vless
    val alterId: Int = 0,          // vmess
    val security: String = "auto", // vmess cipher
    // trojan / shadowsocks
    val password: String = "",
    val method: String = "",       // shadowsocks cipher
    // Shadowsocks 2022 (Xray 24.10+): requires a uot version marker on UDP.
    val uotVersion: Int = 0,       // 0 = off (legacy SS), 2 = SS2022 UoT
    val stream: XrayStream = XrayStream(),
) {
    /** "vless · tls · ws" style summary line for the profile detail screen. */
    val summary: String
        get() = buildList {
            add(protocol)
            if (stream.security.isNotBlank() && stream.security != "none") add(stream.security)
            add(stream.network)
        }.joinToString(" · ")
}

/**
 * Transport + security settings, shared across vless/vmess/trojan. Shadowsocks
 * ignores all of this (it is raw TCP), which is why the builder gates on
 * protocol before emitting streamSettings.
 */
data class XrayStream(
    val network: String = "tcp",      // tcp | ws | grpc | http | kcp | quic
    val security: String = "none",    // none | tls | reality
    val sni: String = "",
    val fingerprint: String = "",     // uTLS fingerprint, e.g. chrome
    val alpn: String = "",
    val host: String = "",            // ws/http Host header
    val path: String = "/",           // ws/http path
    val headerType: String = "none",  // tcp http obfs
    val serviceName: String = "",     // grpc
    // Reality
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    // mldsa65Verify — post-quantum Reality verification seed (Xray 25.8+).
    val pqv: String = "",
    // xhttp / splithttp transport (Xray 24.11+): mode and extra sub-settings.
    val mode: String = "",            // xhttp mode: auto | packet-up | stream-up | stream-one
    val extra: String = "",           // xhttp "extra" JSON, passed through verbatim
)
