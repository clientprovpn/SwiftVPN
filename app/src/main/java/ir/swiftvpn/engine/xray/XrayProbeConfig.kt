package ir.swiftvpn.engine.xray

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a THROWAWAY Xray config used only for probing a server.
 *
 * Different from [XrayConfig] in one decisive way: instead of a `tun` inbound it
 * exposes a local **SOCKS5** listener and no TUN at all. That matters because of
 * how the probe has to work:
 *
 *  * `Libv2ray.measureOutboundDelay()` gives a latency number and nothing else —
 *    no response body, so it cannot report the egress IP or country.
 *  * We cannot simply issue an HTTP request from the app while the real tunnel is
 *    up either: [ir.swiftvpn.xray.XrayVpnService] calls
 *    `addDisallowedApplication(ourPackage)` to break the routing loop, so our own
 *    traffic deliberately bypasses the TUN — we would measure OUR connection and
 *    read OUR IP, not the server's.
 *
 * So the probe starts a second, private Xray instance with `StartLoop(cfg, 0)`
 * (fd 0 means "no TUN", per the wrapper's own contract), then drives ordinary
 * Java networking through `Proxy(SOCKS, 127.0.0.1:port)`. That routes the probe —
 * and only the probe — through the server under test, which is exactly what makes
 * the country and throughput readings real.
 *
 * No `stats`/`policy` blocks here: the probe times bytes itself and the counters
 * would be dead weight.
 */
object XrayProbeConfig {

    /** Tag used for the outbound so routing has something to name. */
    private const val PROXY_TAG = "proxy"

    /**
     * A config whose only inbound is SOCKS5 on 127.0.0.1:[socksPort].
     *
     * [socksPort] must be a port the caller has already confirmed free —
     * see [ir.swiftvpn.engine.XrayTester.freePort].
     */
    fun build(outbound: XrayOutbound, socksPort: Int): String {
        val root = JSONObject()

        // Silence the log: a probe that spams the shared log tab would drown the
        // real connection's output.
        root.put("log", JSONObject().put("loglevel", "none"))

        root.put(
            "inbounds",
            JSONArray().put(
                JSONObject()
                    .put("tag", "socks-in")
                    .put("listen", "127.0.0.1")
                    .put("port", socksPort)
                    .put("protocol", "socks")
                    .put(
                        "settings",
                        JSONObject()
                            // No auth: it is bound to loopback for a few seconds.
                            .put("auth", "noauth")
                            // UDP off — the probe only makes TCP requests, and
                            // enabling it would need an extra local port.
                            .put("udp", false),
                    )
            )
        )

        root.put(
            "outbounds",
            JSONArray()
                .put(XrayConfig.outboundJson(outbound, PROXY_TAG))
                .put(JSONObject().put("protocol", "freedom").put("tag", "direct"))
        )

        root.put(
            "routing",
            JSONObject().put(
                "rules",
                JSONArray().put(
                    JSONObject()
                        .put("type", "field")
                        .put("inboundTag", JSONArray().put("socks-in"))
                        .put("outboundTag", PROXY_TAG)
                )
            )
        )

        return root.toString()
    }

    /**
     * A config with NO inbound at all, for `measureOutboundDelay`.
     *
     * That API strips inbounds itself, but handing it a config with a `tun`
     * inbound would still make it parse a TUN section it cannot use — and on
     * Android that means touching `xray.tun.fd`. Giving it an outbound-only
     * config keeps the probe completely independent of the real tunnel.
     */
    fun buildOutboundOnly(outbound: XrayOutbound): String = JSONObject()
        .put("log", JSONObject().put("loglevel", "none"))
        .put("outbounds", JSONArray().put(XrayConfig.outboundJson(outbound, PROXY_TAG)))
        .toString()
}
