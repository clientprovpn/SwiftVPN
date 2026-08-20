package ir.swiftvpn.engine.xray

import org.json.JSONArray
import org.json.JSONObject

/**
 * Assembles the full Xray JSON config from a parsed [XrayOutbound].
 *
 * The shape here is deliberately the minimum that makes TUN mode work on
 * Android, and every block earns its place:
 *
 *  - **stats + policy** — without `statsOutboundUplink/Downlink` the counters
 *    `outbound>>>proxy>>>traffic>>>*` never get created, and the speedometer
 *    reads zero. This is the one non-obvious requirement.
 *  - **tun inbound** — xray-core's own gvisor L3 stack, reading the fd we pass
 *    via `xray.tun.fd`. `sniffing` recovers the hostname from TLS SNI / HTTP
 *    Host so routing and the server see domains, not just IPs.
 *  - **routing** — everything from the tun inbound goes to the proxy outbound.
 *    The app's own sockets never reach here: the VpnService excludes this
 *    package from the tunnel, which is what stops Xray's own uplink from looping
 *    back through the TUN.
 *  - **freedom/blackhole** — standard direct and block outbounds, present so
 *    future routing rules have somewhere to send traffic.
 *
 * Numbers are produced with org.json, so quoting and escaping are handled for us
 * rather than by string interpolation.
 */
object XrayConfig {

    const val PROXY_TAG = "proxy"

    fun build(outbound: XrayOutbound, mtu: Int = 1500): String {
        val root = JSONObject()

        root.put("log", JSONObject().put("loglevel", "warning"))

        // Enable per-outbound traffic counters.
        root.put("stats", JSONObject())
        root.put(
            "policy",
            JSONObject().put(
                "system",
                JSONObject()
                    .put("statsOutboundUplink", true)
                    .put("statsOutboundDownlink", true)
                    // Inbound counters too: the stall watchdog compares what
                    // apps push INTO the tunnel against what the proxy returns,
                    // and that needs the tun-in counters to exist.
                    .put("statsInboundUplink", true)
                    .put("statsInboundDownlink", true),
            ),
        )

        // DNS through the proxy keeps lookups from leaking to the local network.
        root.put(
            "dns",
            JSONObject().put(
                "servers",
                JSONArray().put("1.1.1.1").put("8.8.8.8"),
            ),
        )

        root.put("inbounds", JSONArray().put(tunInbound(mtu)))
        root.put(
            "outbounds",
            JSONArray()
                .put(proxyOutbound(outbound))
                .put(JSONObject().put("protocol", "freedom").put("tag", "direct"))
                .put(JSONObject().put("protocol", "blackhole").put("tag", "block")),
        )
        root.put("routing", routing())

        return root.toString()
    }

    // ------------------------------------------------------------- inbound

    private fun tunInbound(mtu: Int): JSONObject = JSONObject()
        .put("tag", "tun-in")
        // The tun inbound never binds a port; 0 is required by the schema.
        .put("port", 0)
        .put("protocol", "tun")
        .put(
            "settings",
            // Lowercase "mtu": that is the json tag on infra/conf.TunConfig. The
            // protobuf field is spelled MTU, which is a trap — feeding "MTU" here
            // is silently ignored and the core falls back to its own 1500 default.
            JSONObject()
                .put("name", "swiftxray")
                .put("mtu", mtu),
        )
        .put(
            "sniffing",
            JSONObject()
                .put("enabled", true)
                .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                // routeOnly false so the sniffed domain is used for the actual
                // connection, not just routing decisions.
                .put("routeOnly", false),
        )

    // ------------------------------------------------------------- outbound

    /**
     * The proxy outbound as JSON, exposed so the probe config
     * ([XrayProbeConfig]) can reuse the exact same protocol/transport mapping
     * instead of duplicating it. Duplication here would be a real hazard: a
     * latency test that built its outbound slightly differently from the live
     * connection would be measuring something the user never actually uses.
     */
    fun outboundJson(o: XrayOutbound, tag: String = PROXY_TAG): JSONObject =
        proxyOutbound(o).put("tag", tag)

    private fun proxyOutbound(o: XrayOutbound): JSONObject {
        val outbound = JSONObject()
            .put("tag", PROXY_TAG)
            // The core registers Hysteria2 under the name "hysteria".
            .put("protocol", if (o.protocol == "hysteria2") "hysteria" else o.protocol)

        val settings = when (o.protocol) {
            "vless" -> vlessSettings(o)
            "vmess" -> vmessSettings(o)
            "trojan" -> trojanSettings(o)
            "shadowsocks" -> shadowsocksSettings(o)
            "hysteria2" -> hysteriaSettings(o)
            else -> JSONObject()
        }
        outbound.put("settings", settings)

        // Shadowsocks/Hysteria2/TUIC own their whole stack; the others carry a
        // separate transport/security layer.
        when (o.protocol) {
            "shadowsocks" -> Unit // raw TCP, no transport layer
            "hysteria2" -> outbound.put("streamSettings", hysteriaStream(o))
            else -> outbound.put("streamSettings", streamSettings(o))
        }
        return outbound
    }

    /**
     * xray-core registers this protocol as "hysteria": the outbound settings
     * carry only the server endpoint, while the password and congestion live
     * on the hysteria TRANSPORT (streamSettings.hysteriaSettings).
     */
    private fun hysteriaSettings(o: XrayOutbound): JSONObject =
        JSONObject()
            .put("version", 2)
            .put("address", o.address)
            .put("port", o.port)

    /** The hysteria transport: password as auth, BBR congestion by default. */
    private fun hysteriaStream(o: XrayOutbound): JSONObject {
        val stream = JSONObject()
            .put("network", "hysteria")
            .put(
                "hysteriaSettings",
                JSONObject()
                    .put("version", 2)
                    .put("auth", o.password)
                    .put("congestion", "bbr"),
            )
        // Salamander UDP obfuscation (stream.path = obfs type, stream.host =
        // its password — see XrayShareLink.parseHysteria2). In the new core
        // this is a finalmask entry, not a protocol field.
        if (o.stream.path.equals("salamander", true) && o.stream.host.isNotBlank()) {
            stream.put(
                "finalmask",
                JSONObject().put(
                    "udp",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "salamander")
                            .put(
                                "settings",
                                JSONObject().put("password", o.stream.host),
                            ),
                    ),
                ),
            )
        }
        return stream
    }

    private fun vlessSettings(o: XrayOutbound): JSONObject {
        val user = JSONObject()
            .put("id", o.uuid)
            .put("encryption", o.encryption.ifBlank { "none" })
        if (o.flow.isNotBlank()) user.put("flow", o.flow)

        return JSONObject().put(
            "vnext",
            JSONArray().put(
                JSONObject()
                    .put("address", o.address)
                    .put("port", o.port)
                    .put("users", JSONArray().put(user)),
            ),
        )
    }

    private fun vmessSettings(o: XrayOutbound): JSONObject {
        val user = JSONObject()
            .put("id", o.uuid)
            .put("alterId", o.alterId)
            .put("security", o.security.ifBlank { "auto" })

        return JSONObject().put(
            "vnext",
            JSONArray().put(
                JSONObject()
                    .put("address", o.address)
                    .put("port", o.port)
                    .put("users", JSONArray().put(user)),
            ),
        )
    }

    private fun trojanSettings(o: XrayOutbound): JSONObject {
        val server = JSONObject()
            .put("address", o.address)
            .put("port", o.port)
            .put("password", o.password)
        if (o.flow.isNotBlank()) server.put("flow", o.flow)
        return JSONObject().put("servers", JSONArray().put(server))
    }

    private fun shadowsocksSettings(o: XrayOutbound): JSONObject {
        val server = JSONObject()
            .put("address", o.address)
            .put("port", o.port)
            .put("method", o.method)
            .put("password", o.password)
        // Shadowsocks 2022 ciphers carry their key schedule differently and
        // need the UoT marker for UDP to work (Xray 24.10+).
        if (o.method.startsWith("2022-") && o.uotVersion > 0) {
            server.put("uot", true).put("uotVersion", o.uotVersion)
        }
        return JSONObject().put("servers", JSONArray().put(server))
    }

    // ------------------------------------------------------------- stream

    private fun streamSettings(o: XrayOutbound): JSONObject {
        val s = o.stream
        val stream = JSONObject()
            .put("network", s.network.ifBlank { "tcp" })
            .put("security", s.security.ifBlank { "none" })

        when (s.security) {
            "tls" -> stream.put("tlsSettings", tlsSettings(o))
            "reality" -> stream.put("realitySettings", realitySettings(s))
        }

        when (s.network) {
            "ws" -> stream.put(
                "wsSettings",
                JSONObject()
                    .put("path", s.path.ifBlank { "/" })
                    .apply { if (s.host.isNotBlank()) put("host", s.host) },
            )
            "grpc" -> stream.put(
                "grpcSettings",
                JSONObject().put("serviceName", s.serviceName),
            )
            "xhttp", "splithttp" -> stream.put(
                "xhttpSettings",
                JSONObject()
                    .put("path", s.path.ifBlank { "/" })
                    .apply {
                        if (s.host.isNotBlank()) put("host", s.host)
                        if (s.mode.isNotBlank()) put("mode", s.mode)
                        // "extra" is a passthrough JSON blob from the link.
                        if (s.extra.isNotBlank()) {
                            runCatching { put("extra", JSONObject(s.extra)) }
                        }
                    },
            )
            "http", "h2" -> stream.put(
                "httpSettings",
                JSONObject()
                    .put("path", s.path.ifBlank { "/" })
                    .apply {
                        if (s.host.isNotBlank()) {
                            put("host", JSONArray().put(s.host))
                        }
                    },
            )
            "tcp" -> if (s.headerType == "http") {
                stream.put(
                    "tcpSettings",
                    JSONObject().put(
                        "header",
                        JSONObject()
                            .put("type", "http")
                            .apply {
                                if (s.host.isNotBlank()) {
                                    put(
                                        "request",
                                        JSONObject().put(
                                            "headers",
                                            JSONObject().put(
                                                "Host",
                                                JSONArray().put(s.host),
                                            ),
                                        ),
                                    )
                                }
                            },
                    ),
                )
            }
        }
        return stream
    }

    private fun tlsSettings(o: XrayOutbound): JSONObject {
        val s = o.stream
        val tls = JSONObject()
        val serverName = s.sni.ifBlank { s.host.ifBlank { o.address } }
        tls.put("serverName", serverName)
        if (s.fingerprint.isNotBlank()) tls.put("fingerprint", s.fingerprint)
        if (s.alpn.isNotBlank()) {
            tls.put("alpn", JSONArray().apply { s.alpn.split(',').forEach { put(it.trim()) } })
        }
        return tls
    }

    private fun realitySettings(s: XrayStream): JSONObject {
        val reality = JSONObject()
            .put("serverName", s.sni)
            .put("publicKey", s.publicKey)
        if (s.fingerprint.isNotBlank()) reality.put("fingerprint", s.fingerprint)
        if (s.shortId.isNotBlank()) reality.put("shortId", s.shortId)
        if (s.spiderX.isNotBlank()) reality.put("spiderX", s.spiderX)
        // Post-quantum verification seed (mldsa65Verify, Xray 25.8+).
        if (s.pqv.isNotBlank()) reality.put("mldsa65Verify", s.pqv)
        return reality
    }

    // ------------------------------------------------------------- routing

    private fun routing(): JSONObject = JSONObject()
        .put("domainStrategy", "IPIfNonMatch")
        .put(
            "rules",
            JSONArray()
                // Everything arriving on the TUN goes out through the proxy.
                .put(
                    JSONObject()
                        .put("type", "field")
                        .put("inboundTag", JSONArray().put("tun-in"))
                        .put("outboundTag", PROXY_TAG),
                ),
        )
}
