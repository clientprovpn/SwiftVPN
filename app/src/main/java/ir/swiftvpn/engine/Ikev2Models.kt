package ir.swiftvpn.engine

import org.strongswan.android.data.VpnProfile
import org.strongswan.android.data.VpnType
import java.util.UUID

/**
 * App-side view of an IKEv2 profile. Mirrors the fields the vendored
 * strongSwan [VpnProfile] actually consumes — nothing here is decorative.
 */
data class Ikev2Profile(
    val uuid: String,
    val name: String,
    val gateway: String,
    val port: Int = 500,
    /** "ikev2-eap" | "ikev2-cert" | "ikev2-cert-eap" | "ikev2-eap-tls" */
    val vpnType: String = "ikev2-eap",
    val username: String = "",
    val password: String = "",
    /** TrustedCertificateManager alias of the CA cert; null = system default set. */
    val caAlias: String = "",
    /** Android KeyChain alias of the client certificate (cert auth). */
    val userCertAlias: String = "",
    val localId: String = "",
    val remoteId: String = "",
    val mtu: Int = 0,
    val natKeepalive: Int = 0,
    val ikeProposal: String = "",
    val espProposal: String = "",
    /** Preferred EAP method: "", "mschapv2", "peap" or "ttls" ("" = server picks). */
    val eapType: String = "",
    val dnsServers: String = "",
    val suppressCertReqs: Boolean = false,
    val disableCrl: Boolean = false,
    val disableOcsp: Boolean = false,
    val strictRevocation: Boolean = false,
    val rsaPss: Boolean = false,
    val ipv6Transport: Boolean = false,
) {
    val needsUserPass: Boolean
        get() = VpnType.fromIdentifier(vpnType).has(VpnType.VpnTypeFeature.USER_PASS)

    val needsCertificate: Boolean
        get() = VpnType.fromIdentifier(vpnType).has(VpnType.VpnTypeFeature.CERTIFICATE)

    fun toVpnProfile(): VpnProfile {
        val p = VpnProfile()
        p.setUUID(UUID.fromString(uuid))
        p.name = name
        p.gateway = gateway
        p.vpnType = VpnType.fromIdentifier(vpnType)
        p.username = username.ifBlank { null }
        p.password = password.ifBlank { null }
        p.certificateAlias = caAlias.ifBlank { null }
        p.userCertificateAlias = userCertAlias.ifBlank { null }
        p.localId = localId.ifBlank { null }
        // If no remote identity is configured, defaulting to the gateway hostname
        // makes strongSwan reject servers whose certificate CN/SAN differs from the
        // gateway (very common with commercial VPNs, e.g. Windscribe). "%any" keeps
        // full CA chain validation but skips the hostname identity constraint.
        p.remoteId = remoteId.ifBlank { "%any" }
        p.setMTU(mtu.takeIf { it > 0 })
        p.setPort(port.takeIf { it > 0 })
        p.setNATKeepAlive(natKeepalive.takeIf { it > 0 })
        p.ikeProposal = ikeProposal.ifBlank { null }
        p.espProposal = espProposal.ifBlank { null }
        p.eapType = eapType.ifBlank { null }
        p.dnsServers = dnsServers.ifBlank { null }
        var flags = 0
        if (suppressCertReqs) flags = flags or VpnProfile.FLAGS_SUPPRESS_CERT_REQS
        if (disableCrl) flags = flags or VpnProfile.FLAGS_DISABLE_CRL
        if (disableOcsp) flags = flags or VpnProfile.FLAGS_DISABLE_OCSP
        if (strictRevocation) flags = flags or VpnProfile.FLAGS_STRICT_REVOCATION
        if (rsaPss) flags = flags or VpnProfile.FLAGS_RSA_PSS
        if (ipv6Transport) flags = flags or VpnProfile.FLAGS_IPv6_TRANSPORT
        p.setFlags(flags)
        return p
    }

    companion object {
        fun fromVpnProfile(p: VpnProfile): Ikev2Profile {
            val flags = p.getFlags() ?: 0
            return Ikev2Profile(
                uuid = p.getUUID().toString(),
                name = p.name ?: "",
                gateway = p.gateway ?: "",
                port = p.getPort() ?: 500,
                vpnType = p.vpnType?.identifier ?: "ikev2-eap",
                username = p.username ?: "",
                password = p.password ?: "",
                caAlias = p.certificateAlias ?: "",
                userCertAlias = p.userCertificateAlias ?: "",
                localId = p.localId ?: "",
                remoteId = p.remoteId ?: "",
                mtu = p.getMTU() ?: 0,
                natKeepalive = p.getNATKeepAlive() ?: 0,
                ikeProposal = p.ikeProposal ?: "",
                espProposal = p.espProposal ?: "",
                eapType = p.eapType ?: "",
                dnsServers = p.dnsServers ?: "",
                suppressCertReqs = flags and VpnProfile.FLAGS_SUPPRESS_CERT_REQS != 0,
                disableCrl = flags and VpnProfile.FLAGS_DISABLE_CRL != 0,
                disableOcsp = flags and VpnProfile.FLAGS_DISABLE_OCSP != 0,
                strictRevocation = flags and VpnProfile.FLAGS_STRICT_REVOCATION != 0,
                rsaPss = flags and VpnProfile.FLAGS_RSA_PSS != 0,
                ipv6Transport = flags and VpnProfile.FLAGS_IPv6_TRANSPORT != 0,
            )
        }
    }
}
