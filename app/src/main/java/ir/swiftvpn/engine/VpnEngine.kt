package ir.swiftvpn.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.util.Log
import de.blinkt.openvpn.VpnProfile
import android.os.IBinder
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.LogItem
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import ir.swiftvpn.notification.WireGuardNotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.StringReader

/**
 * Single source of truth for VPN state, traffic and logs — across BOTH engines.
 *
 * This object owns the state the UI observes and routes every command to
 * whichever driver owns the target profile. Nothing above this layer (the
 * screens, the graphs, the Quick Settings tile) knows which protocol is running.
 *
 * **OpenVPN** runs in a separate process (`:openvpn`), so the static VpnStatus in
 * this process is a different object from the one the tunnel updates. Bridging
 * that gap is handled by the engine's own StatusListener, which
 * ICSOpenVPNApplication.onCreate starts — see SwiftVpnApp. Because that bridge
 * pumps everything into VpnStatus HERE, plain static listeners are all we need.
 *
 * **WireGuard** runs in-process via [WireGuardEngine], which pushes into the same
 * flows through callbacks. Its statistics are polled and diffed there, so by the
 * time values arrive here both protocols look identical.
 *
 * One hard constraint shapes the whole design: Android allows exactly one active
 * VpnService per device. Starting either engine therefore stops the other first,
 * and [activeProtocol] records which one currently holds the tunnel.
 */
object VpnEngine {

    private const val TAG = "VpnEngine"

    /** How many samples the 60-second graphs hold. */
    const val HISTORY_SECONDS = 60

    private val _state = MutableStateFlow(VpnState.DISCONNECTED)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _stateMessage = MutableStateFlow<String?>(null)
    val stateMessage: StateFlow<String?> = _stateMessage.asStateFlow()

    private val _connectedUuid = MutableStateFlow<String?>(null)
    val connectedUuid: StateFlow<String?> = _connectedUuid.asStateFlow()

    /**
     * The profile the user just asked to start, before the engine has reported
     * it as connected.
     *
     * Without this the UI looks dead on tap: `connectedUuid` only arrives from
     * the engine's setConnectedVPN callback, seconds later, so a control driven
     * purely by it would not move at all.
     */
    private val _pendingUuid = MutableStateFlow<String?>(null)
    val pendingUuid: StateFlow<String?> = _pendingUuid.asStateFlow()

    /**
     * State for one profile row: the live state when this profile is the active
     * or pending one, otherwise disconnected.
     */
    fun stateFor(uuid: String): VpnState = when (uuid) {
        _connectedUuid.value -> _state.value
        _pendingUuid.value -> if (_state.value.isActive) _state.value else VpnState.CONNECTING
        else -> VpnState.DISCONNECTED
    }

    private val _traffic = MutableStateFlow(TrafficStats())
    val traffic: StateFlow<TrafficStats> = _traffic.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    private val _tunnelInfo = MutableStateFlow(TunnelInfo())
    val tunnelInfo: StateFlow<TunnelInfo> = _tunnelInfo.asStateFlow()

    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince: StateFlow<Long?> = _connectedSince.asStateFlow()

    /**
     * Which driver owns the live (or pending) tunnel.
     *
     * Needed because the OpenVPN status bridge is always listening: without
     * this, a WireGuard connection could be clobbered by a stale OpenVPN
     * "not connected" callback arriving from the other process. Every OpenVPN
     * callback is gated on this being OPENVPN.
     */
    private val _activeProtocol = MutableStateFlow(Protocol.OPENVPN)
    val activeProtocol: StateFlow<Protocol> = _activeProtocol.asStateFlow()

    // Rolling graph windows.
    private val downWindow = ArrayDeque<Long>()
    private val upWindow = ArrayDeque<Long>()

    private var initialised = false
    private lateinit var appContext: Context

    /**
     * Binding to the tunnel service itself, used only to stop the VPN.
     *
     * This is SEPARATE from the OpenVPNStatusService binding that the engine's
     * own StatusListener owns. Disconnecting cannot be done with an Intent:
     * OpenVPNService.onStartCommand only handles PAUSE_VPN, RESUME_VPN,
     * START_SERVICE and START_SERVICE_STICKY — DISCONNECT_VPN is never read
     * there, so an intent carrying it is silently dropped. Upstream's own
     * DisconnectVPN activity binds and calls stopVPN(false); so do we.
     */
    private var tunnelService: IOpenVPNServiceInternal? = null

    /** Set when disconnect() is called before the binder has arrived. */
    private var disconnectPending = false

    private val tunnelConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = IOpenVPNServiceInternal.Stub.asInterface(binder)
            tunnelService = svc
            // A stop requested before the binding completed must still happen.
            if (disconnectPending) {
                disconnectPending = false
                stopTunnel(svc)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            tunnelService = null
        }
    }

    // ---------------------------------------------------------------- listeners

    private val stateListener = object : VpnStatus.StateListener {
        override fun updateState(
            state: String?,
            logmessage: String?,
            localizedResId: Int,
            level: ConnectionStatus?,
            intent: Intent?,
        ) {
            // The OpenVPN bridge keeps reporting even while WireGuard holds the
            // tunnel. Ignoring it here is what stops a stale "not connected"
            // from the :openvpn process tearing down a live WireGuard session.
            if (_activeProtocol.value != Protocol.OPENVPN) return

            applyState(VpnState.from(level), logmessage?.takeIf { it.isNotBlank() } ?: state)
        }

        override fun setConnectedVPN(uuid: String?) {
            if (_activeProtocol.value != Protocol.OPENVPN) return
            _connectedUuid.value = uuid
            // The engine has confirmed; the optimistic value is redundant.
            if (uuid != null) _pendingUuid.value = null
        }
    }

    /**
     * Applies a state transition from either driver.
     *
     * Shared so the CONNECTED-edge and teardown bookkeeping cannot drift apart
     * between the two protocols.
     */
    private fun applyState(mapped: VpnState, message: String?) {
        val previous = _state.value
        _state.value = mapped
        _stateMessage.value = message

        // Every transition, with the protocol that owns it. This is the spine of
        // a bug report: it shows what the app believed was happening and when,
        // which is what the raw engine output on its own never makes clear.
        if (mapped != previous) {
            DiagnosticLog.write(
                DiagnosticLog.APP,
                "state ${previous.name} -> ${mapped.name} (${_activeProtocol.value.label})" +
                    (message?.let { " :: $it" } ?: ""),
            )
        }

        when {
            mapped == VpnState.CONNECTED && previous != VpnState.CONNECTED ->
                _connectedSince.value = System.currentTimeMillis()

            !mapped.isActive -> {
                _connectedSince.value = null
                resetTraffic()

                // Only a clean stop clears the profile association.
                //
                // AUTH_FAILED and NO_NETWORK are also "not active", but the UI
                // resolves a row's state by matching its uuid against these two
                // flows — so clearing them here would make the row fall back to
                // plain "Disconnected" and the user would never see WHY the
                // attempt failed. Keeping the uuid is what lets the error stay
                // attached to the profile that produced it.
                if (mapped == VpnState.DISCONNECTED) {
                    _pendingUuid.value = null
                    _connectedUuid.value = null
                }
            }
        }
    }

    /**
     * The engine hands us both cumulative totals and the per-interval deltas it
     * already computed, so rates come straight from diffIn/diffOut.
     */
    private val byteCountListener =
        VpnStatus.ByteCountListener { bytesIn, bytesOut, diffIn, diffOut ->
            if (_activeProtocol.value != Protocol.OPENVPN) return@ByteCountListener
            applyBytes(bytesIn, bytesOut, diffIn, diffOut)
        }

    /**
     * Feeds the graphs. Both drivers land here with the same tuple: WireGuard's
     * poll loop does its own diffing so the shapes match.
     */
    private fun applyBytes(bytesIn: Long, bytesOut: Long, diffIn: Long, diffOut: Long) {
        push(downWindow, diffIn)
        push(upWindow, diffOut)

        _traffic.value = TrafficStats(
            bytesIn = bytesIn,
            bytesOut = bytesOut,
            downBytesPerSec = diffIn,
            upBytesPerSec = diffOut,
            downHistory = downWindow.toList(),
            upHistory = upWindow.toList(),
        )
    }

    private val logListener = VpnStatus.LogListener { item ->
        if (item == null) return@LogListener
        if (_activeProtocol.value != Protocol.OPENVPN) return@LogListener
        val line = item.toLogLine()
        _logs.value = (_logs.value + line).takeLast(500)
        // Only OpenVPN needs scraping — WireGuard reports its config directly.
        harvestTunnelInfo(line.message)
    }

    /** Appends a line to the shared log from a non-OpenVPN driver. */
    private fun appendLog(message: String) {
        _logs.value = (_logs.value + LogLine(
            timestamp = System.currentTimeMillis(),
            message = message,
            level = 1,
        )).takeLast(500)
        // Mirror to the durable log, tagged by whichever engine is live, so the
        // line survives a crash that wipes the in-memory list.
        DiagnosticLog.write(
            when (_activeProtocol.value) {
                Protocol.WIREGUARD -> DiagnosticLog.WIREGUARD
                Protocol.XRAY -> DiagnosticLog.XRAY
                Protocol.OPENVPN -> DiagnosticLog.OPENVPN
                Protocol.IKEV2 -> DiagnosticLog.IKEV2
            },
            message,
        )
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Call once from Application.onCreate, AFTER super.onCreate() so the
     * engine's StatusListener is already feeding VpnStatus.
     */
    fun init(context: Context) {
        if (initialised) return
        appContext = context.applicationContext
        initialised = true

        VpnStatus.addStateListener(stateListener)
        VpnStatus.addByteCountListener(byteCountListener)
        VpnStatus.addLogListener(logListener)

        // Gated exactly like the OpenVPN listeners above, and for the same
        // reason: a late callback from a torn-down tunnel must not touch state
        // that now belongs to the other engine. GoBackend can deliver
        // onStateChange(DOWN) asynchronously AFTER we have already flipped to
        // OpenVPN, which without this gate would wipe the pending/connected
        // uuids of a connection that is actually still coming up.
        wireGuard = WireGuardEngine(appContext).apply {
            onState = { state, message ->
                if (_activeProtocol.value == Protocol.WIREGUARD) applyState(state, message)
            }
            onBytes = { inB, outB, dIn, dOut ->
                if (_activeProtocol.value == Protocol.WIREGUARD) {
                    applyBytes(inB, outB, dIn, dOut)
                }
            }
            onLog = {
                if (_activeProtocol.value == Protocol.WIREGUARD) appendLog(it)
            }
            onTunnelInfo = {
                if (_activeProtocol.value == Protocol.WIREGUARD) _tunnelInfo.value = it
            }
        }

        // Xray, same gated wiring. Its VpnService reports through XrayEngine.
        XrayEngine.onState = { state, message ->
            if (_activeProtocol.value == Protocol.XRAY) applyState(state, message)
        }
        XrayEngine.onBytes = { inB, outB, dIn, dOut ->
            if (_activeProtocol.value == Protocol.XRAY) applyBytes(inB, outB, dIn, dOut)
        }
        XrayEngine.onLog = {
            if (_activeProtocol.value == Protocol.XRAY) appendLog(it)
        }
        XrayEngine.onTunnelInfo = {
            if (_activeProtocol.value == Protocol.XRAY) _tunnelInfo.value = it
        }

        // IKEv2 traffic counters, sampled from the tun interface by Ikev2Engine.
        Ikev2Engine.onBytes = { inB, outB, dIn, dOut ->
            if (_activeProtocol.value == Protocol.IKEV2) applyBytes(inB, outB, dIn, dOut)
        }
    }

    // ---------------------------------------------------------------- wireguard

    private lateinit var wireGuard: WireGuardEngine

    /** Lazily created; safe to touch from any thread after [init]. */
    private fun wgStore(context: Context) = WireGuardStore(context)

    private fun xrayStore(context: Context) = XrayStore(context)

    // ------------------------------------------------------------------ profiles

    /**
     * Every profile from both engines, OpenVPN first.
     *
     * Reads from disk (ObjectInputStream for OpenVPN, file reads for WireGuard),
     * so callers must stay off the main thread.
     */
    fun profiles(context: Context): List<Profile> {
        val openVpn = runCatching {
            ProfileManager.getInstance(context).profiles.map { it.toProfile() }
        }.getOrDefault(emptyList())

        val wg = runCatching { wgStore(context).profiles() }.getOrDefault(emptyList())
        val xray = runCatching { xrayStore(context).profiles() }.getOrDefault(emptyList())
        val ikev2 = runCatching {
            ikev2Store(context).all().map {
                Profile(
                    uuid = it.uuid,
                    name = it.name,
                    server = it.gateway,
                    port = it.port.toString(),
                    useUdp = true,
                    authTypeLabel = "",
                    protocol = Protocol.IKEV2,
                )
            }
        }.getOrDefault(emptyList())

        return openVpn + wg + xray + ikev2
    }

    /**
     * Which engine owns [uuid].
     *
     * Determined by which store has the file on disk, so the answer cannot drift
     * out of sync with what is actually stored. OpenVPN is the fallback because
     * its profiles live in the engine's ProfileManager, not a file we probe.
     */
    fun protocolOf(context: Context, uuid: String): Protocol = when {
        wgStore(context).exists(uuid) -> Protocol.WIREGUARD
        xrayStore(context).exists(uuid) -> Protocol.XRAY
        ikev2Store(context).exists(uuid) -> Protocol.IKEV2
        else -> Protocol.OPENVPN
    }

    private fun ikev2Store(context: Context) = Ikev2Store(context)

    /**
     * Imports a config, choosing the engine by INSPECTING THE CONTENT.
     *
     * Extension-sniffing is not reliable here: document pickers hand back
     * arbitrary display names, and users rename files freely. A wg-quick file is
     * unmistakable from its `[Interface]` section, so that is the test.
     */
    /**
     * Number of profiles imported by the most recent multi-link paste, or 0
     * when the last import was a single config. Lets the UI show "N imported"
     * for a batch paste even though [importConfig] returns a single Profile.
     */
    @Volatile
    var lastBatchImport: Int = 0
        private set

    fun importConfig(
        context: Context,
        configText: String,
        preferredName: String,
        onError: (String) -> Unit = {},
    ): Profile? {
        val store = wgStore(context)

        // A pasted clipboard may hold SEVERAL share links (one per line) or a
        // base64-encoded list of them — the format subscription exports and
        // panel "copy all" buttons produce. Import them as a batch; the
        // per-link count is published on [lastBatchImport] for the caller.
        val pasted = ir.swiftvpn.engine.xray.XrayShareLink.extractLinks(configText)
        if (pasted.size > 1) {
            val imported = xrayStore(context).importMany(pasted.joinToString("\n"), null)
            lastBatchImport = imported
            if (imported <= 0) onError(context.getString(ir.swiftvpn.R.string.import_failed))
            return null
        }
        lastBatchImport = 0

        // Xray share links are unambiguous — they start with a scheme no other
        // format uses (vless://, vmess://, trojan://, ss://) — so this is a
        // definite match, not a guess, and short-circuits ahead of the others.
        if (ir.swiftvpn.engine.xray.XrayShareLink.looksLikeShareLink(configText)) {
            val imported = xrayStore(context).import(configText, preferredName)
            if (imported == null) {
                onError(context.getString(ir.swiftvpn.R.string.import_failed))
            }
            return imported
        }

        // The sniff picks the ORDER to try, not the final answer. Committing to
        // one parser would make a single misclassification fatal: an .ovpn whose
        // comments happen to mention "[interface]" and "privatekey" would be
        // handed to the WireGuard parser, fail, and be rejected outright even
        // though the OpenVPN parser would have accepted it. Trying both means
        // the sniff only has to be a good guess.
        val wireGuardFirst = store.looksLikeWireGuard(configText)

        if (wireGuardFirst) {
            store.import(configText, preferredName)?.let { return it }
        }

        val openVpn = runCatching {
            val parser = de.blinkt.openvpn.core.ConfigParser()
            parser.parseConfig(StringReader(configText))
            val profile = parser.convertProfile()
            profile.mName = uniqueName(context, preferredName)

            val pm = ProfileManager.getInstance(context)
            pm.addProfile(profile)
            ProfileManager.saveProfile(context, profile)
            pm.saveProfileList(context)

            // Keep the ORIGINAL .ovpn text: ics-openvpn stores only its parsed
            // form, which cannot be exported back out. Without this copy, a
            // full backup could cover WireGuard/Xray but lose OpenVPN servers.
            runCatching {
                val dir = java.io.File(context.filesDir, "openvpn_src").apply { mkdirs() }
                java.io.File(dir, "${profile.getUUIDString()}.ovpn").writeText(configText)
            }.onFailure { Log.w(TAG, "could not save OpenVPN source", it) }

            profile.toProfile()
        }.onFailure { Log.w(TAG, "OpenVPN parse failed", it) }.getOrNull()

        if (openVpn != null) return openVpn

        // Fall back the other way when the sniff said "not WireGuard" but the
        // OpenVPN parser rejected it too.
        if (!wireGuardFirst) {
            store.import(configText, preferredName)?.let { return it }
        }

        onError(context.getString(ir.swiftvpn.R.string.import_failed))
        return null
    }

    /**
     * Imports every config found in a zip — WireGuard bundles, OpenVPN bundles,
     * and files of Xray share links (one link per line, or one per file).
     * Returns how many were accepted; partial results are deliberate.
     */
    fun importZip(
        context: Context,
        input: java.io.InputStream,
        onError: (String) -> Unit = {},
    ): Int {
        val entries = ZipImport.readConfigs(input)
        if (entries.isEmpty()) {
            onError(context.getString(ir.swiftvpn.R.string.import_zip_empty))
            return 0
        }
        var count = 0
        for (entry in entries) {
            // SwiftVPN's own IKEv2 backup entries restore through their own
            // path (profile JSON + embedded certificate material).
            if (entry.isIkev2Backup) {
                if (importIkev2Backup(context, entry.text)) count++
                continue
            }
            // A file may hold several xray share links, one per line — the
            // single-config path would reject that as malformed.
            val lines = entry.text.lines().map { it.trim() }
                .filter { ir.swiftvpn.engine.xray.XrayShareLink.looksLikeShareLink(it) }
            if (lines.size > 1) {
                for ((i, link) in lines.withIndex()) {
                    if (importConfig(context, link, "${entry.name}-${i + 1}") { } != null) count++
                }
                continue
            }
            if (importConfig(context, entry.text, entry.name) { } != null) count++
        }
        return count
    }

    fun deleteProfile(context: Context, uuid: String) {
        // Drop the exportable source copy too — a leftover would resurrect
        // in the next backup.
        java.io.File(java.io.File(context.filesDir, "openvpn_src"), "$uuid.ovpn").delete()
        when (protocolOf(context, uuid)) {
            Protocol.WIREGUARD -> wgStore(context).delete(uuid)
            Protocol.XRAY -> xrayStore(context).delete(uuid)
            Protocol.IKEV2 -> ikev2Store(context).delete(uuid)
            Protocol.OPENVPN -> {
                val pm = ProfileManager.getInstance(context)
                ProfileManager.get(context, uuid)?.let { pm.removeProfile(context, it) }
            }
        }
    }

    fun renameProfile(context: Context, uuid: String, newName: String) {
        when (protocolOf(context, uuid)) {
            Protocol.WIREGUARD -> wgStore(context).rename(uuid, newName)
            Protocol.XRAY -> xrayStore(context).rename(uuid, newName)
            Protocol.IKEV2 -> ikev2Store(context).profile(uuid)
                ?.let { ikev2Store(context).save(it.copy(name = newName)) }
            Protocol.OPENVPN -> {
                val pm = ProfileManager.getInstance(context)
                ProfileManager.get(context, uuid)?.let {
                    it.mName = newName
                    ProfileManager.saveProfile(context, it)
                    pm.saveProfileList(context)
                }
            }
        }
    }

    // ------------------------------------------------------------ xray settings

    /** Editable view of an Xray profile, or null when it is not one. */
    fun xraySettings(context: Context, uuid: String): XraySettings? {
        val store = xrayStore(context)
        val link = store.link(uuid) ?: return null
        val o = store.outbound(uuid) ?: return null
        return XraySettings(
            uuid = uuid,
            name = store.name(uuid),
            protocolLabel = o.protocol,
            server = o.address,
            port = o.port.toString(),
            transport = o.stream.network,
            security = o.stream.security,
            sni = o.stream.sni,
            rawLink = link,
        )
    }

    /** Saves an edited Xray profile. The link is validated by re-parsing. */
    fun saveXraySettings(context: Context, settings: XraySettings): Boolean {
        val store = xrayStore(context)
        if (!store.saveLink(settings.uuid, settings.rawLink)) return false
        store.rename(settings.uuid, settings.name)
        return true
    }

    // --------------------------------------------------------- ikev2 settings

    fun ikev2Settings(context: Context, uuid: String): Ikev2Profile? =
        Ikev2Store(context).profile(uuid)

    fun saveIkev2Settings(context: Context, profile: Ikev2Profile): Boolean =
        runCatching {
            Ikev2Store(context).save(profile)
        }.isSuccess

    /**
     * Restores one IKEv2 profile from a SwiftVPN backup entry (ikev2 folder, JSON),
     * including the certificate material embedded at export time:
     *  - the CA certificate is reinstalled into the LocalCertificateStore
     *    silently (its alias is a SHA-1 of the public key, so the restored
     *    alias matches the stored one automatically),
     *  - the client certificate + private key (when they were exportable) are
     *    offered to the system KeyChain via the standard install prompt, one
     *    tap for the user, and the profile points at that label.
     */
    private fun importIkev2Backup(context: Context, text: String): Boolean =
        runCatching {
            val o = org.json.JSONObject(text)
            val gateway = o.optString("gateway")
            if (gateway.isBlank()) return@runCatching false

            var caAlias = o.optString("caAlias")
            var userCertAlias = o.optString("userCertAlias")

            // CA certificate material embedded in the backup wins over a bare
            // alias reference: reinstall it and re-resolve the alias.
            val caDerB64 = o.optString("caCertDerB64")
            if (caDerB64.isNotBlank()) {
                runCatching {
                    Ikev2Engine.init(context)
                    val cert = java.security.cert.CertificateFactory.getInstance("X.509")
                        .generateCertificate(
                            android.util.Base64.decode(caDerB64, android.util.Base64.DEFAULT)
                                .inputStream(),
                        ) as java.security.cert.X509Certificate
                    val store = java.security.KeyStore.getInstance("LocalCertificateStore")
                    store.load(null, null)
                    store.setCertificateEntry(null, cert)
                    org.strongswan.android.logic.TrustedCertificateManager.getInstance().reset()
                    store.getCertificateAlias(cert)?.let { caAlias = it }
                }
            }

            // Client certificate + key: rebuild a PKCS#12 and hand it to the
            // system installer (Android requires one user confirmation).
            val keyB64 = o.optString("userKeyPkcs8B64")
            val chainArr = o.optJSONArray("userCertChainDerB64")
            if (keyB64.isNotBlank() && chainArr != null && chainArr.length() > 0) {
                runCatching {
                    val keyDer = android.util.Base64.decode(keyB64, android.util.Base64.DEFAULT)
                    val key = java.security.KeyFactory.getInstance("RSA")
                        .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyDer))
                    val chain = Array(chainArr.length()) { i ->
                        java.security.cert.CertificateFactory.getInstance("X.509")
                            .generateCertificate(
                                android.util.Base64.decode(
                                    chainArr.getString(i), android.util.Base64.DEFAULT,
                                ).inputStream(),
                            ) as java.security.cert.X509Certificate
                    }
                    val label = userCertAlias.ifBlank { "swiftvpn-client" }
                    val p12 = java.security.KeyStore.getInstance("PKCS12")
                    p12.load(null, null)
                    p12.setKeyEntry(label, key, CharArray(0), chain)
                    val buf = java.io.ByteArrayOutputStream()
                    p12.store(buf, CharArray(0))
                    val intent = android.security.KeyChain.createInstallIntent()
                    intent.putExtra(android.security.KeyChain.EXTRA_PKCS12, buf.toByteArray())
                    intent.putExtra(android.security.KeyChain.EXTRA_NAME, label)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }

            val profile = Ikev2Profile(
                uuid = java.util.UUID.randomUUID().toString(),
                name = o.optString("name").ifBlank { gateway },
                gateway = gateway,
                port = o.optInt("port", 500),
                vpnType = o.optString("vpnType", "ikev2-eap"),
                username = o.optString("username"),
                password = o.optString("password"),
                caAlias = caAlias,
                userCertAlias = userCertAlias,
                localId = o.optString("localId"),
                remoteId = o.optString("remoteId"),
                mtu = o.optInt("mtu"),
                natKeepalive = o.optInt("natKeepalive"),
                ikeProposal = o.optString("ikeProposal"),
                espProposal = o.optString("espProposal"),
                dnsServers = o.optString("dnsServers"),
                suppressCertReqs = o.optBoolean("suppressCertReqs"),
                disableCrl = o.optBoolean("disableCrl"),
                disableOcsp = o.optBoolean("disableOcsp"),
                strictRevocation = o.optBoolean("strictRevocation"),
                rsaPss = o.optBoolean("rsaPss"),
                ipv6Transport = o.optBoolean("ipv6Transport"),
            )
            ikev2Store(context).save(profile)
            true
        }.getOrElse {
            Log.w(TAG, "IKEv2 backup entry rejected", it)
            false
        }

    // ------------------------------------------------------- wireguard settings

    /** Editable view of a WireGuard profile, or null when it is not one. */
    fun wireGuardSettings(context: Context, uuid: String): WireGuardSettings? {
        val store = wgStore(context)
        val raw = store.rawConfig(uuid) ?: return null
        val config = store.config(uuid) ?: return null
        val iface = config.getInterface()
        val peer = config.peers.firstOrNull()

        return WireGuardSettings(
            uuid = uuid,
            name = store.name(uuid),
            endpoint = peer?.endpoint?.orElse(null)?.let { "${it.host}:${it.port}" } ?: "",
            allowedIps = peer?.allowedIps?.joinToString(", ") ?: "",
            dnsServers = iface.dnsServers.joinToString(", ") {
                it.hostAddress ?: it.toString()
            },
            mtu = iface.mtu.orElse(null)?.toString() ?: "",
            persistentKeepalive = peer?.persistentKeepalive?.orElse(null)?.toString() ?: "",
            addresses = iface.addresses.joinToString(", "),
            publicKey = iface.keyPair.publicKey.toBase64(),
            rawConfig = raw,
        )
    }

    /**
     * Saves an edited WireGuard config.
     *
     * The raw text is authoritative: it is validated by re-parsing before being
     * written, so an invalid edit is rejected instead of leaving a profile that
     * cannot start.
     */
    fun saveWireGuardSettings(context: Context, settings: WireGuardSettings): Boolean {
        val store = wgStore(context)
        if (!store.saveConfig(settings.uuid, settings.rawConfig)) return false
        store.rename(settings.uuid, settings.name)
        return true
    }

    /**
     * Full editable view of an OpenVPN profile, for the settings screen.
     *
     * Returns null for WireGuard profiles — they have a different field set and
     * their own accessor, [wireGuardSettings].
     */
    fun profileSettings(context: Context, uuid: String): ProfileSettings? {
        // Only OpenVPN uses this editor; WireGuard and Xray have their own.
        if (protocolOf(context, uuid) != Protocol.OPENVPN) return null
        val profile = ProfileManager.get(context, uuid) ?: return null
        val connection = profile.primaryConnection()
        return ProfileSettings(
            uuid = uuid,
            name = profile.name ?: "",
            server = connection?.mServerName ?: profile.mServerName ?: "",
            port = connection?.mServerPort ?: profile.mServerPort ?: "1194",
            useUdp = connection?.mUseUdp ?: profile.mUseUdp,
            username = profile.mUsername ?: "",
            hasPassword = !profile.mPassword.isNullOrEmpty(),
            usePull = profile.mUsePull,
            useLzo = profile.mUseLzo,
            useDefaultRoute = profile.mUseDefaultRoute,
            useDefaultRoute6 = profile.mUseDefaultRoutev6,
            customRoutes = profile.mCustomRoutes ?: "",
            overrideDns = profile.mOverrideDNS,
            dns1 = profile.mDNS1 ?: "",
            dns2 = profile.mDNS2 ?: "",
            searchDomain = profile.mSearchDomain ?: "",
            mssFix = profile.mMssFix,
            tunMtu = profile.mTunMtu,
            connectTimeout = connection?.mConnectTimeout ?: 0,
            persistTun = profile.mPersistTun,
            authTypeLabel = authTypeLabel(profile.mAuthenticationType),
            cipher = profile.mCipher ?: "",
            auth = profile.mAuth ?: "",
            dataCiphers = profile.mDataCiphers ?: "",
            tlsAuthDirection = profile.mTLSAuthDirection ?: "",
            hasTlsAuthKey = !profile.mTLSAuthFilename.isNullOrEmpty(),
            checkRemoteCN = profile.mCheckRemoteCN,
            expectTLSCert = profile.mExpectTLSCert,
            remoteCN = profile.mRemoteCN ?: "",
            useCustomConfig = profile.mUseCustomConfig,
            customConfigOptions = profile.mCustomConfigOptions ?: "",
            allowLocalLAN = profile.mAllowLocalLAN,
            blockUnusedAF = profile.mBlockUnusedAddressFamilies,
        )
    }

    /** Applies edits from the settings screen. */
    fun saveProfileSettings(context: Context, settings: ProfileSettings): Boolean {
        val profile = ProfileManager.get(context, settings.uuid) ?: return false
        return runCatching {
            profile.mName = settings.name.ifBlank { profile.mName }

            // The endpoint lives on the Connection entries, not the legacy
            // fields, so write there when the profile has any.
            val connection = profile.primaryConnection()
            if (connection != null) {
                connection.mServerName = settings.server
                connection.mServerPort = settings.port
                connection.mUseUdp = settings.useUdp
                if (settings.connectTimeout > 0) {
                    connection.mConnectTimeout = settings.connectTimeout
                }
            }
            // Keep the legacy fields in step for older code paths.
            profile.mServerName = settings.server
            profile.mServerPort = settings.port
            profile.mUseUdp = settings.useUdp

            profile.mUsername = settings.username
            profile.mUsePull = settings.usePull
            profile.mUseLzo = settings.useLzo
            profile.mUseDefaultRoute = settings.useDefaultRoute
            profile.mUseDefaultRoutev6 = settings.useDefaultRoute6
            profile.mCustomRoutes = settings.customRoutes.ifBlank { null }
            profile.mOverrideDNS = settings.overrideDns
            profile.mDNS1 = settings.dns1
            profile.mDNS2 = settings.dns2
            profile.mSearchDomain = settings.searchDomain
            profile.mMssFix = settings.mssFix
            profile.mTunMtu = settings.tunMtu
            profile.mPersistTun = settings.persistTun

            // Encryption / TLS
            profile.mCipher = settings.cipher
            profile.mAuth = settings.auth
            profile.mDataCiphers = settings.dataCiphers
            // The tls-auth/crypt direction only takes effect when the profile
            // actually embeds a key; write it either way so it is ready if a
            // key appears, but never invent one.
            profile.mTLSAuthDirection = settings.tlsAuthDirection
            profile.mCheckRemoteCN = settings.checkRemoteCN
            profile.mExpectTLSCert = settings.expectTLSCert
            profile.mRemoteCN = settings.remoteCN
            profile.mUseCustomConfig = settings.useCustomConfig
            profile.mCustomConfigOptions = settings.customConfigOptions
            profile.mAllowLocalLAN = settings.allowLocalLAN
            profile.mBlockUnusedAddressFamilies = settings.blockUnusedAF

            ProfileManager.saveProfile(context, profile)
            ProfileManager.getInstance(context).saveProfileList(context)
            true
        }.getOrElse {
            Log.w(TAG, "saveProfileSettings failed", it)
            false
        }
    }

    private fun uniqueName(context: Context, wanted: String): String {
        val pm = ProfileManager.getInstance(context)
        val base = wanted.ifBlank { "profile" }
        if (pm.getProfileByName(base) == null) return base
        var i = 2
        while (pm.getProfileByName("$base ($i)") != null) i++
        return "$base ($i)"
    }

    // ------------------------------------------------------------------- control

    /**
     * Intent to request VPN permission, or null when already granted.
     * Must be launched from an Activity.
     */
    fun vpnPermissionIntent(context: Context): Intent? = VpnService.prepare(context)

    /**
     * True when [uuid] cannot start yet because it needs a username/password.
     * The engine decides this, so saved credentials and certificate-only
     * profiles correctly report false.
     */
    fun needsCredentials(context: Context, uuid: String): Boolean {
        // Only OpenVPN has an interactive credential step. WireGuard uses keys
        // in the config; Xray carries its auth in the share link.
        if (protocolOf(context, uuid) != Protocol.OPENVPN) return false
        val profile = ProfileManager.get(context, uuid) ?: return false
        return runCatching { profile.needUserPWInput(null, null) != 0 }
            .getOrDefault(false)
    }

    fun savedUsername(context: Context, uuid: String): String =
        ProfileManager.get(context, uuid)?.mUsername.orEmpty()

    /**
     * Stores credentials on the profile. When [persist] is false the password
     * is held only for this process via the engine's PasswordCache.
     */
    fun setCredentials(
        context: Context,
        uuid: String,
        username: String,
        password: String,
        persist: Boolean,
    ) {
        val profile = ProfileManager.get(context, uuid) ?: return
        profile.mUsername = username
        if (persist) {
            profile.mPassword = password
        } else {
            profile.mPassword = ""
            runCatching {
                de.blinkt.openvpn.core.PasswordCache.setCachedPassword(
                    uuid,
                    de.blinkt.openvpn.core.PasswordCache.AUTHPASSWORD,
                    password,
                )
            }.onFailure { Log.w(TAG, "password cache failed", it) }
        }
        ProfileManager.saveProfile(context, profile)
        ProfileManager.getInstance(context).saveProfileList(context)
    }

    /**
     * Starts [uuid] on whichever engine owns it, stopping the other first.
     *
     * Suspending rather than blocking: the WireGuard path can sit in DNS
     * resolution for ten seconds, and making that a suspension point means the
     * compiler enforces what a comment used to only ask for. Dispatch is handled
     * internally, so callers do not need their own withContext.
     */
    suspend fun connect(context: Context, uuid: String): Boolean =
        withContext(Dispatchers.IO) {
            val protocol = protocolOf(context, uuid)
            DiagnosticLog.write(
                DiagnosticLog.APP,
                "connect requested: ${protocol.label} (was ${_activeProtocol.value.label})",
            )

            // Android permits one VpnService at a time. Tear down the incumbent
            // before claiming the tunnel, otherwise the system revokes it from
            // under us mid-handshake and the failure looks like a config error.
            stopOtherEngine(protocol)

            resetTraffic()
            // Reflect the intent immediately so the UI responds to the tap.
            _pendingUuid.value = uuid
            _activeProtocol.value = protocol

            when (protocol) {
                Protocol.WIREGUARD -> connectWireGuard(context, uuid)
                Protocol.OPENVPN -> connectOpenVpn(context, uuid)
                Protocol.XRAY -> connectXray(context, uuid)
                Protocol.IKEV2 -> connectIkev2(context, uuid)
            }
        }

    /**
     * Starts Xray. The service does the TUN + core work and reports back through
     * XrayEngine; here we just hand off. connectedUuid is set optimistically
     * because Xray, like WireGuard, has no per-profile callback of its own — the
     * service confirms CONNECTED a moment later, and a failure flips the row to
     * an error state via the gated XrayEngine.onState.
     */
    private fun connectXray(context: Context, uuid: String): Boolean {
        applyState(VpnState.CONNECTING, null)
        _connectedUuid.value = uuid
        XrayEngine.connect(context, uuid)
        return true
    }

    private fun connectOpenVpn(context: Context, uuid: String): Boolean {
        val profile = ProfileManager.get(context, uuid) ?: run {
            _pendingUuid.value = null
            return false
        }

        // Bind now so a subsequent disconnect has a live binder to talk to.
        bindTunnelService()

        return runCatching {
            VPNLaunchHelper.startOpenVpn(profile, context, "SwiftVPN", true)
            true
        }.getOrElse {
            Log.w(TAG, "connect failed", it)
            _pendingUuid.value = null
            false
        }
    }

    private suspend fun connectWireGuard(context: Context, uuid: String): Boolean {
        // Publish CONNECTING before the service starts, and do it HERE rather
        // than relying on the driver to do it a moment later.
        //
        // The notification service self-stops when it observes an inactive
        // state, and it reads the current state on its very first emission. If
        // that read lands while _state is still DISCONNECTED — which it is until
        // the driver's own onState(CONNECTING) arrives from the IO thread — the
        // service kills itself immediately and no notification is ever shown.
        // That race is why the ordering is explicit instead of incidental.
        applyState(VpnState.CONNECTING, null)

        // Start the notification service BEFORE the handshake, not after.
        //
        // GoBackend posts no notification and never calls startForeground, so
        // until this service is up nothing is keeping the process in the
        // foreground — and setState() can sit in DNS retries for ten seconds.
        // Starting first means the tunnel is protected for that whole window and
        // the user sees "connecting" rather than silence.
        WireGuardNotificationService.start(context)

        // WireGuard reports no per-profile identity of its own, so the router
        // sets the association itself once the tunnel is actually up. Setting it
        // before would leave a profile marked connected during a ten-second DNS
        // retry that may still fail.
        val error = wireGuard.connect(uuid, wgStore(context))
        if (error != null) {
            // Leave the uuid in place on failure: applyState has already moved
            // to an error state, and the row needs the association to show it.
            WireGuardNotificationService.stop(context)
            return false
        }
        _connectedUuid.value = uuid
        return true
    }

    private fun connectIkev2(context: Context, uuid: String): Boolean {
        val profile = ikev2Store(context).profile(uuid)
        // Starting charon without a gateway is pointless and only produces a
        // confusing generic error — refuse early with a clear reason.
        if (profile == null || profile.gateway.isBlank()) {
            appendLog("IKEv2 connect refused: no gateway configured")
            _pendingUuid.value = null
            applyState(VpnState.UNKNOWN, "IKEv2 profile has no server address — open its settings first")
            return false
        }
        applyState(VpnState.CONNECTING, null)
        _connectedUuid.value = uuid
        Ikev2Engine.onState = callback@{ state, error ->
            // Only consume callbacks while IKEv2 owns the tunnel.
            if (_activeProtocol.value != Protocol.IKEV2) return@callback
            when (state) {
                VpnState.CONNECTED -> applyState(VpnState.CONNECTED, null)
                VpnState.DISCONNECTED -> {
                    if (error != null) {
                        applyState(VpnState.UNKNOWN, error)
                    } else {
                        applyState(VpnState.DISCONNECTED, null)
                        _connectedUuid.value = null
                    }
                }
                else -> if (error != null) applyState(VpnState.UNKNOWN, error)
            }
        }
        Ikev2Engine.connect(context, uuid, profile.password)
        return true
    }

    /**
     * Stops whichever engine is NOT [keeping].
     *
     * Called on every connect. Deliberately fire-and-forget for OpenVPN: its
     * teardown is asynchronous across a process boundary, and blocking on it
     * would stall the tap.
     */
    private fun stopOtherEngine(keeping: Protocol) {
        if (keeping != Protocol.OPENVPN && _activeProtocol.value == Protocol.OPENVPN) {
            tunnelService?.let { stopTunnel(it) }
        }
        if (keeping != Protocol.WIREGUARD) {
            // Guarded on "did we start a tunnel", NOT on "is it confirmed UP".
            // A WireGuard tunnel still mid-handshake reports state != UP, so an
            // isRunning check here would skip the teardown and leave two
            // VpnServices racing — Android then revokes one, and the failure
            // surfaces as a bogus config error. disconnectBlocking no-ops
            // safely when nothing is active, so calling it unconditionally is
            // both simpler and more correct.
            runCatching { wireGuard.disconnectBlocking() }
                .onFailure { Log.w(TAG, "stopping WireGuard failed", it) }
            WireGuardNotificationService.stop(appContext)
        }
        if (keeping != Protocol.IKEV2 && _activeProtocol.value == Protocol.IKEV2) {
            runCatching { Ikev2Engine.disconnect(appContext) }
                .onFailure { Log.w(TAG, "stopping IKEv2 failed", it) }
        }
        if (keeping != Protocol.XRAY) {
            // Synchronous: releases the fd before the incoming engine establishes
            // its own TUN, so Android does not revoke one mid-handshake. Safe
            // here because connect() runs this on an IO dispatcher.
            runCatching { XrayEngine.forceStop() }
                .onFailure { Log.w(TAG, "stopping Xray failed", it) }
        }
    }

    /**
     * Stops the live tunnel, whichever engine owns it.
     *
     * For OpenVPN this goes through the engine's internal AIDL. Sending an
     * Intent with DISCONNECT_VPN does nothing — the service never reads that
     * action. See [tunnelConnection].
     */
    fun disconnect(context: Context) {
        DiagnosticLog.write(
            DiagnosticLog.APP,
            "disconnect requested (${_activeProtocol.value.label})",
        )
        _pendingUuid.value = null

        if (_activeProtocol.value == Protocol.WIREGUARD) {
            // Async: this is a user tap, arriving on the main thread.
            wireGuard.disconnectAsync()
            // The service also self-stops when it observes an inactive state, so
            // this is belt-and-braces — but stopping it here makes the shade
            // entry disappear on the tap rather than one flow emission later.
            WireGuardNotificationService.stop(context)
            return
        }

        if (_activeProtocol.value == Protocol.XRAY) {
            // The service stops its core, closes the fd, and reports back.
            XrayEngine.disconnect(context)
            return
        }

        if (_activeProtocol.value == Protocol.IKEV2) {
            Ikev2Engine.disconnect(context)
            return
        }

        val svc = tunnelService
        if (svc != null) {
            stopTunnel(svc)
            return
        }

        // Not bound yet: remember the request and bind. onServiceConnected
        // will carry it out.
        disconnectPending = true
        bindTunnelService()
    }

    private fun stopTunnel(svc: IOpenVPNServiceInternal) {
        // Binder call — keep it off the main thread.
        Thread({
            runCatching { svc.stopVPN(false) }
                .onFailure { Log.w(TAG, "stopVPN failed", it) }
        }, "swiftvpn-stop").apply { isDaemon = true }.start()
    }

    private fun bindTunnelService() {
        val intent = Intent(appContext, OpenVPNService::class.java).apply {
            action = OpenVPNService.START_SERVICE
        }
        runCatching {
            appContext.bindService(intent, tunnelConnection, Context.BIND_AUTO_CREATE)
        }.onFailure { Log.w(TAG, "bindService(OpenVPNService) failed", it) }
    }

    fun clearLogs() {
        runCatching { VpnStatus.clearLog() }
        _logs.value = emptyList()
    }

    // -------------------------------------------------------------------- helpers

    private fun LogItem.toLogLine() = LogLine(
        timestamp = logtime,
        message = runCatching { getString(appContext) }.getOrDefault(""),
        level = runCatching { logLevel?.int ?: 0 }.getOrDefault(0),
    )

    /**
     * Extracts routing details from engine log lines. The engine does not
     * expose the negotiated tunnel config directly, but it logs it.
     */
    private fun harvestTunnelInfo(message: String) {
        if (message.isBlank()) return
        var info = _tunnelInfo.value
        var changed = false

        Regex("""Local IPv4:\s*(\S+)""").find(message)?.let {
            info = info.copy(localIPv4 = it.groupValues[1]); changed = true
        }
        Regex("""Local IPv6:\s*(\S+)""").find(message)?.let {
            info = info.copy(localIPv6 = it.groupValues[1]); changed = true
        }
        Regex("""MTU:\s*(\d+)""").find(message)?.let {
            info = info.copy(mtu = it.groupValues[1].toIntOrNull()); changed = true
        }
        Regex("""Peer Connection Initiated with \[[^]]+]([\d.]+:\d+)""")
            .find(message)?.let {
                info = info.copy(remoteServer = it.groupValues[1]); changed = true
            }
        Regex("""dhcp-option DNS6?\s+(\S+)""").find(message)?.let {
            val dns = it.groupValues[1]
            if (dns !in info.dnsServers) {
                info = info.copy(dnsServers = info.dnsServers + dns); changed = true
            }
        }
        Regex("""Routes:\s*(.+)""").find(message)?.let {
            val routes = it.groupValues[1]
                .split(',')
                .map(String::trim)
                .filter { r -> r.isNotEmpty() }
            if (routes.isNotEmpty() && routes != info.routes) {
                info = info.copy(routes = routes); changed = true
            }
        }

        if (changed) _tunnelInfo.value = info
    }

    private fun push(window: ArrayDeque<Long>, value: Long) {
        window.addLast(value)
        while (window.size > HISTORY_SECONDS) window.removeFirst()
    }

    private fun resetTraffic() {
        downWindow.clear()
        upWindow.clear()
        _traffic.value = TrafficStats()
        _tunnelInfo.value = TunnelInfo()
    }
}

/** The enabled connection entry, where the real endpoint lives. */
private fun VpnProfile.primaryConnection() =
    mConnections?.firstOrNull { it != null && it.mEnabled }
        ?: mConnections?.firstOrNull { it != null }

/**
 * Maps the engine's profile object onto our flattened UI model.
 *
 * The endpoint comes from mConnections — clearDefaults() leaves the legacy
 * mServerName as the literal string "unknown".
 */
private fun VpnProfile.toProfile(): Profile {
    val connection = primaryConnection()
    return Profile(
        uuid = uuidString,
        name = name ?: "",
        server = connection?.mServerName ?: mServerName ?: "",
        port = connection?.mServerPort ?: mServerPort ?: "",
        useUdp = connection?.mUseUdp ?: mUseUdp,
        authTypeLabel = authTypeLabel(mAuthenticationType),
        protocol = Protocol.OPENVPN,
    )
}

/** Short label like the "OVPN TLS PWD" line in the profile list. */
private fun authTypeLabel(type: Int): String = when (type) {
    VpnProfile.TYPE_CERTIFICATES -> "OVPN TLS CERT"
    VpnProfile.TYPE_PKCS12 -> "OVPN PKCS12"
    VpnProfile.TYPE_KEYSTORE -> "OVPN KEYSTORE"
    VpnProfile.TYPE_USERPASS -> "OVPN PWD"
    VpnProfile.TYPE_STATICKEYS -> "OVPN STATIC"
    VpnProfile.TYPE_USERPASS_CERTIFICATES -> "OVPN TLS PWD"
    VpnProfile.TYPE_USERPASS_PKCS12 -> "OVPN PKCS12 PWD"
    VpnProfile.TYPE_USERPASS_KEYSTORE -> "OVPN KEYSTORE PWD"
    else -> "OVPN"
}
