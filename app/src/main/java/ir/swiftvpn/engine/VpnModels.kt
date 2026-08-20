package ir.swiftvpn.engine

import de.blinkt.openvpn.core.ConnectionStatus

/**
 * Which tunnel engine owns a profile.
 *
 * Stored on every Profile so the router can dispatch without guessing. The UI
 * shows it as a badge; nothing else in the UI branches on it.
 */
enum class Protocol(val label: String) {
    OPENVPN("OpenVPN"),
    WIREGUARD("WireGuard"),
    XRAY("Xray"),
    IKEV2("IKEv2"),
}

/** Normalised connection state for the UI, tile and notification. */
enum class VpnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    PAUSED,
    AUTH_FAILED,
    NO_NETWORK,
    WAITING_FOR_INPUT,
    UNKNOWN;

    /** True while the tunnel is up or coming up. */
    val isActive: Boolean
        get() = this == CONNECTED || this == CONNECTING || this == PAUSED

    companion object {
        fun from(level: ConnectionStatus?): VpnState = when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> CONNECTED
            ConnectionStatus.LEVEL_START,
            ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET -> CONNECTING
            ConnectionStatus.LEVEL_VPNPAUSED -> PAUSED
            ConnectionStatus.LEVEL_AUTH_FAILED -> AUTH_FAILED
            ConnectionStatus.LEVEL_NONETWORK -> NO_NETWORK
            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> WAITING_FOR_INPUT
            ConnectionStatus.LEVEL_NOTCONNECTED -> DISCONNECTED
            else -> UNKNOWN
        }
    }
}

/** A VPN profile, flattened for the UI. */
data class Profile(
    val uuid: String,
    val name: String,
    val server: String,
    val port: String,
    val useUdp: Boolean,
    val authTypeLabel: String,
    val isFavourite: Boolean = false,
    val protocol: Protocol = Protocol.OPENVPN,
    /** Owning subscription id, or null for hand-added profiles. */
    val subscriptionId: String? = null,
) {
    /**
     * e.g. "185.216.35.67:443 UDP" — the profile list subtitle.
     *
     * Only OpenVPN prints a transport suffix: WireGuard is UDP-only and Xray's
     * transport lives in its own summary line, so a "UDP" tag would be noise for
     * both.
     */
    val endpoint: String
        get() = when (protocol) {
            Protocol.OPENVPN -> "$server:$port ${if (useUdp) "UDP" else "TCP"}"
            Protocol.WIREGUARD, Protocol.XRAY, Protocol.IKEV2 -> "$server:$port"
        }
}

/**
 * Everything the settings screen can edit on a profile.
 *
 * Field names mirror the engine's VpnProfile so the mapping stays obvious.
 */
data class ProfileSettings(
    val uuid: String,
    val name: String,
    val server: String,
    val port: String,
    val useUdp: Boolean,
    val username: String,
    val hasPassword: Boolean,
    val usePull: Boolean,
    val useLzo: Boolean,
    val useDefaultRoute: Boolean,
    val useDefaultRoute6: Boolean,
    val customRoutes: String,
    val overrideDns: Boolean,
    val dns1: String,
    val dns2: String,
    val searchDomain: String,
    val mssFix: Int,
    val tunMtu: Int,
    val connectTimeout: Int,
    val persistTun: Boolean,
    val authTypeLabel: String,
    // Encryption / TLS — empty or defaults mean "as the config file says".
    val cipher: String,
    val auth: String,
    val dataCiphers: String,
    val tlsAuthDirection: String,
    val hasTlsAuthKey: Boolean,
    val checkRemoteCN: Boolean,
    val expectTLSCert: Boolean,
    val remoteCN: String,
    val useCustomConfig: Boolean,
    val customConfigOptions: String,
    val allowLocalLAN: Boolean,
    val blockUnusedAF: Boolean,
)

/**
 * Editable view of a WireGuard profile.
 *
 * Deliberately NOT folded into [ProfileSettings]. WireGuard has no username,
 * no LZO, no pull, no TCP; it has AllowedIPs and a keepalive that OpenVPN has
 * no equivalent for. Sharing one struct would mean a screen full of fields that
 * are inert for whichever protocol is loaded.
 *
 * [rawConfig] is the source of truth. The parsed fields below are for display
 * and light editing; on save they are written back into the text, because the
 * library only accepts a wg-quick document and round-tripping through
 * Config.toWgQuickString() would silently drop comments and unknown keys.
 */
data class WireGuardSettings(
    val uuid: String,
    val name: String,
    val endpoint: String,
    val allowedIps: String,
    val dnsServers: String,
    val mtu: String,
    val persistentKeepalive: String,
    val addresses: String,
    val publicKey: String,
    val rawConfig: String,
)

/**
 * Editable view of an Xray profile.
 *
 * Like [WireGuardSettings], the raw form — here the share link — is the source
 * of truth, and the parsed fields above it are read-only summary. Xray links
 * pack a dozen transport/security options into a URL; re-deriving that URL from
 * individual fields would be lossy, so the user edits the link directly and it
 * is validated by re-parsing on save.
 */
data class XraySettings(
    val uuid: String,
    val name: String,
    val protocolLabel: String,
    val server: String,
    val port: String,
    val transport: String,
    val security: String,
    val sni: String,
    val rawLink: String,
)

/** One sample of cumulative traffic counters. */
data class TrafficSample(
    val timestamp: Long,
    val bytesIn: Long,
    val bytesOut: Long,
    val diffIn: Long,
    val diffOut: Long,
)

/**
 * Live traffic figures, derived from the engine's byte counters.
 *
 * [downBytesPerSec] / [upBytesPerSec] are instantaneous rates;
 * [downHistory] / [upHistory] are the rolling 60-second windows the
 * graphs render.
 */
data class TrafficStats(
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val downBytesPerSec: Long = 0,
    val upBytesPerSec: Long = 0,
    val downHistory: List<Long> = emptyList(),
    val upHistory: List<Long> = emptyList(),
) {
    val peakDown: Long get() = downHistory.maxOrNull() ?: 0
    val peakUp: Long get() = upHistory.maxOrNull() ?: 0
}

/** A single log line from the engine. */
data class LogLine(
    val timestamp: Long,
    val message: String,
    val level: Int,
)

/** Routing / connection info shown on the Routing tab. */
data class TunnelInfo(
    val localIPv4: String? = null,
    val localIPv6: String? = null,
    val remoteServer: String? = null,
    val proxy: String? = null,
    val mtu: Int? = null,
    val dnsServers: List<String> = emptyList(),
    val routes: List<String> = emptyList(),
)
