package ir.swiftvpn

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.swiftvpn.engine.Ikev2Engine
import ir.swiftvpn.engine.Ikev2Profile
import ir.swiftvpn.engine.Profile
import ir.swiftvpn.engine.ProfileSettings
import ir.swiftvpn.engine.ProfileStore
import ir.swiftvpn.engine.Protocol
import ir.swiftvpn.engine.SortMode
import ir.swiftvpn.engine.ThemeMode
import ir.swiftvpn.engine.VpnEngine
import ir.swiftvpn.engine.WireGuardSettings
import ir.swiftvpn.engine.WireGuardStore
import ir.swiftvpn.engine.Subscription
import ir.swiftvpn.engine.XraySettings
import ir.swiftvpn.engine.XrayStore
import ir.swiftvpn.engine.XraySubscriptionStore
import ir.swiftvpn.engine.XrayTester
import ir.swiftvpn.engine.BackupManager
import ir.swiftvpn.engine.formatRate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ProfileStore(app)

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _tileUuid = MutableStateFlow(store.selected()?.uuid)
    val tileUuid: StateFlow<String?> = _tileUuid.asStateFlow()

    private val _themeMode = MutableStateFlow(store.themeMode)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _sortMode = MutableStateFlow(store.sortMode)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    /** Active protocol filter, or null for "*all*". */
    private val _protocolFilter = MutableStateFlow(store.protocolFilter)
    val protocolFilter: StateFlow<Protocol?> = _protocolFilter.asStateFlow()

    /**
     * Active subscription-group filter: a subscription id, or null for the whole
     * list. Each subscription is a separate space — picking one shows exactly its
     * servers, mixed protocols included, under the name the user saved.
     */
    private val _groupFilter = MutableStateFlow<String?>(null)
    val groupFilter: StateFlow<String?> = _groupFilter.asStateFlow()

    /**
     * Latency per profile uuid, in ms. Absent means untested; null value means
     * tested and unreachable — the row needs to tell those apart.
     */
    private val _latency = MutableStateFlow<Map<String, Long?>>(emptyMap())
    val latency: StateFlow<Map<String, Long?>> = _latency.asStateFlow()

    /** Uuids with a test in flight, so their rows can show a spinner. */
    private val _testing = MutableStateFlow<Set<String>>(emptySet())
    val testing: StateFlow<Set<String>> = _testing.asStateFlow()

    /** Subscriptions, for the manage-subscriptions screen. */
    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    /** Share link to render as a QR, or null when that dialog is closed. */
    private val _qrLink = MutableStateFlow<String?>(null)
    val qrLink: StateFlow<String?> = _qrLink.asStateFlow()

    /** One-shot user-facing message (import errors etc.). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Set when VPN permission must be requested from an Activity. */
    private val _permissionIntent = MutableStateFlow<Intent?>(null)
    val permissionIntent: StateFlow<Intent?> = _permissionIntent.asStateFlow()

    /** Set when the battery-optimisation exemption should be requested. */
    private val _batteryIntent = MutableStateFlow<Intent?>(null)
    val batteryIntent: StateFlow<Intent?> = _batteryIntent.asStateFlow()

    fun consumeBatteryIntent() { _batteryIntent.value = null }

    /**
     * Without the battery exemption, OEM power management (Xiaomi, Samsung, …)
     * suspends a backgrounded app's network within seconds — the tunnel shows
     * "connected" while every byte dies. Asked once per install, at the moment
     * the user first connects.
     */
    /**
     * Asked ONCE per install, persisted. Two failure modes forced this:
     * process restarts (every engine switch) reset an in-memory flag, so the
     * dialog reappeared on each connection change; and several OEMs (Xiaomi
     * above all) never report the grant through isIgnoringBatteryOptimizations
     * even after the user accepts, which asked again forever. If the user
     * declines, that is their choice — nagging on every connect is worse.
     */
    private fun ensureBatteryExemption() {
        val app = getApplication<Application>()
        val prefs = app.getSharedPreferences("swiftvpn_misc", 0)
        if (prefs.getBoolean(KEY_BATTERY_ASKED, false)) return
        val pm = app.getSystemService(android.os.PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(app.packageName)) return
        prefs.edit().putBoolean(KEY_BATTERY_ASKED, true).apply()
        runCatching {
            _batteryIntent.value = Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + app.packageName),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Set when a profile needs a username and password before connecting. */
    private val _credentialsFor = MutableStateFlow<Profile?>(null)
    val credentialsFor: StateFlow<Profile?> = _credentialsFor.asStateFlow()

    /** Prefill for the credentials dialog. */
    private val _savedUsername = MutableStateFlow("")
    val savedUsername: StateFlow<String> = _savedUsername.asStateFlow()

    /** Profile the user wants to start once permission is granted. */
    private var pendingConnectUuid: String? = null

    init {
        refresh()
    }

    /**
     * Reloads profiles. ProfileManager reads each profile off disk with an
     * ObjectInputStream, so this must not run on the main thread.
     */
    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                val favourites = store.favourites()
                VpnEngine.profiles(getApplication())
                    .map { it.copy(isFavourite = it.uuid in favourites) }
            }
            // Keep the unfiltered list in memory and apply the filter at read
            // time, so toggling the filter never needs another disk pass.
            allProfiles = list
            // A deleted profile must not stay ticked in selection mode.
            _selected.value = _selected.value.intersect(list.map { it.uuid }.toSet())
            val subs = subStore.all()
            if (_groupFilter.value != null && subs.none { it.id == _groupFilter.value }) {
                _groupFilter.value = null
            }
            applyView()
            _subscriptions.value = subs
        }
    }

    /** Every profile loaded from disk, before sorting or filtering. */
    private var allProfiles: List<Profile> = emptyList()

    private val subStore by lazy { XraySubscriptionStore(getApplication()) }

    private fun applyView() {
        // Type and group are mutually exclusive views: a type shows only
        // HAND-ADDED profiles of that protocol (subscription servers never leak
        // into it), and a group shows exactly the servers that subscription owns.
        val gid = _groupFilter.value
        val proto = _protocolFilter.value
        val filtered = when {
            gid != null -> allProfiles.filter { it.subscriptionId == gid }
            proto != null ->
                allProfiles.filter { it.protocol == proto && it.subscriptionId == null }
            else -> allProfiles
        }
        _profiles.value = sorted(filtered)
        // Types are for hand-added profiles only, so the menu should offer a
        // protocol only when such a profile actually exists.
        _availableProtocols.value =
            Protocol.entries.filter { p ->
                allProfiles.any { it.protocol == p && it.subscriptionId == null }
            }
    }

    // Starred profiles pin to the top, the rest sort by name. There is no
    // manual sort control: favourites-first IS the ordering the user asked for.
    private fun sorted(list: List<Profile>): List<Profile> =
        list.sortedWith(
            compareByDescending<Profile> { it.isFavourite }
                .thenBy { it.name.lowercase() },
        )

    /**
     * Protocols that actually have at least one profile, for the filter menu.
     *
     * A StateFlow rather than a getter over [allProfiles]: Compose cannot observe
     * a plain field, so a getter would only appear to work because the profile
     * list happens to update in the same pass. Any future path that changed
     * profiles without touching that flow would leave the filter menu stale.
     */
    private val _availableProtocols = MutableStateFlow<List<Protocol>>(emptyList())
    val availableProtocols: StateFlow<List<Protocol>> = _availableProtocols.asStateFlow()

    // ------------------------------------------------------------------ actions

    fun setProtocolFilter(protocol: Protocol?) {
        store.protocolFilter = protocol
        _protocolFilter.value = protocol
        // Picking a type leaves any subscription space — the two views are
        // alternatives, never stacked.
        if (protocol != null) _groupFilter.value = null
        applyView()
    }

    fun setGroupFilter(subscriptionId: String?) {
        _groupFilter.value = subscriptionId
        // A subscription space shows every type it contains — entering one must
        // not stay silently narrowed by a type filter chosen earlier.
        if (subscriptionId != null && _protocolFilter.value != null) {
            _protocolFilter.value = null
            store.protocolFilter = null
        }
        applyView()
    }

    fun setTheme(mode: ThemeMode) {
        store.themeMode = mode
        _themeMode.value = mode
    }


    fun toggleFavourite(uuid: String) {
        store.toggleFavourite(uuid)
        refresh()
    }

    fun assignToTile(profile: Profile) {
        store.select(profile)
        _tileUuid.value = profile.uuid
    }

    /**
     * Connect or disconnect [uuid]. If VPN permission is missing, records the
     * intent so the Activity can launch the system dialog first.
     */
    fun toggle(uuid: String) {
        val app = getApplication<Application>()
        ensureBatteryExemption()

        // Stop when this profile is the live one OR the one currently being
        // established — otherwise a second tap during connect would try to
        // start a second tunnel instead of cancelling.
        val isThisProfileBusy = uuid == VpnEngine.connectedUuid.value ||
            uuid == VpnEngine.pendingUuid.value
        if (isThisProfileBusy && VpnEngine.state.value.isActive) {
            VpnEngine.disconnect(app)
            return
        }
        // VpnService.prepare is cheap, but keep the ordering explicit: ask for
        // permission first, then do the disk-backed profile checks.
        val permission = VpnEngine.vpnPermissionIntent(app)
        if (permission != null) {
            pendingConnectUuid = uuid
            _permissionIntent.value = permission
            return
        }
        startTunnel(uuid)
    }

    fun disconnect() = VpnEngine.disconnect(getApplication())

    private fun startTunnel(uuid: String) {
        val app = getApplication<Application>()

        viewModelScope.launch {
            // These read profiles off disk via ProfileManager, so they go to IO.
            val credentials = withContext(Dispatchers.IO) {
                // The engine decides whether credentials are still missing, so
                // saved passwords and certificate-only profiles skip the dialog.
                if (VpnEngine.needsCredentials(app, uuid)) {
                    VpnEngine.savedUsername(app, uuid)
                } else {
                    null
                }
            }

            if (credentials != null) {
                _savedUsername.value = credentials
                _credentialsFor.value = _profiles.value.firstOrNull { it.uuid == uuid }
                return@launch
            }

            // The quick settings tile always targets the profile most recently
            // used. With the manual assign action gone this is the only writer,
            // and it keeps the tile useful without any configuration.
            _profiles.value.firstOrNull { it.uuid == uuid }?.let { assignToTile(it) }

            // connect() is suspending and dispatches to IO itself.
            VpnEngine.connect(app, uuid)
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _permissionIntent.value = null
        val uuid = pendingConnectUuid
        pendingConnectUuid = null
        if (granted && uuid != null) startTunnel(uuid)
    }

    fun submitCredentials(username: String, password: String, remember: Boolean) {
        val profile = _credentialsFor.value ?: return
        _credentialsFor.value = null
        val app = getApplication<Application>()

        assignToTile(profile)

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                VpnEngine.setCredentials(app, profile.uuid, username, password, remember)
            }
            VpnEngine.connect(app, profile.uuid)
        }
    }

    fun dismissCredentials() {
        _credentialsFor.value = null
    }

    // ------------------------------------------------------------------- import

    /**
     * Imports a pasted string — the natural path for an Xray share link, which
     * users copy from a panel rather than receiving as a file. Routes through the
     * same content-sniffing importer, so a pasted wg-quick config works too.
     */
    fun importFromText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                VpnEngine.importConfig(
                    context = app,
                    configText = trimmed,
                    preferredName = "imported",
                    onError = { _message.value = it },
                )
            }
            if (result != null) {
                refresh()
                _message.value = app.getString(R.string.import_success, result.name)
            } else if (VpnEngine.lastBatchImport > 0) {
                refresh()
                _message.value = app.getString(
                    R.string.import_batch_success, VpnEngine.lastBatchImport,
                )
            } else if (_message.value == null) {
                _message.value = app.getString(R.string.import_failed)
            }
        }
    }

    fun importFromUri(uri: Uri, forceZip: Boolean = false) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val name = displayName(uri) ?: "imported"

            // A zip holds many profiles, so it takes the bulk path and reports
            // a count rather than a single name.
            if (forceZip || ir.swiftvpn.engine.ZipImport.looksLikeZip(name)) {
                val count = withContext(Dispatchers.IO) {
                    runCatching {
                        app.contentResolver.openInputStream(uri)?.use { stream ->
                            VpnEngine.importZip(app, stream) { }
                        } ?: 0
                    }.getOrDefault(0)
                }
                if (count > 0) {
                    refresh()
                    // A backup zip carries a manifest with favourites and the
                    // tile selection — restore those too, matched by name.
                    withContext(Dispatchers.IO) {
                        runCatching {
                            app.contentResolver.openInputStream(uri)?.use { stream ->
                                BackupManager.readManifest(stream)
                            }?.let { manifest ->
                                BackupManager.applyManifest(app, manifest, allProfiles)
                                refresh()
                            }
                        }
                    }
                    _message.value = app.getString(R.string.import_zip_success, count)
                    return@launch
                }
                // Not a usable archive: fall through and try as a single config.
            }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = app.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: return@runCatching null

                    VpnEngine.importConfig(
                        context = app,
                        configText = text,
                        preferredName = name.removeSuffix(".ovpn").removeSuffix(".conf"),
                        onError = { _message.value = it },
                    )
                }.getOrNull()
            }

            if (result != null) {
                refresh()
                _message.value = app.getString(R.string.import_success, result.name)
            } else if (VpnEngine.lastBatchImport > 0) {
                refresh()
                _message.value = app.getString(
                    R.string.import_batch_success, VpnEngine.lastBatchImport,
                )
            } else if (_message.value == null) {
                _message.value = app.getString(R.string.import_failed)
            }
        }
    }

    private fun displayName(uri: Uri): String? {
        val app = getApplication<Application>()
        return runCatching {
            app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    fun delete(uuid: String) = deleteAll(setOf(uuid))

    /** Uuids the user has ticked for bulk deletion. Empty means not in that mode. */
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    fun toggleSelected(uuid: String) {
        val current = _selected.value
        _selected.value = if (uuid in current) current - uuid else current + uuid
    }

    fun selectAllVisible() {
        _selected.value = _profiles.value.map { it.uuid }.toSet()
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun deleteAll(uuids: Set<String>) {
        if (uuids.isEmpty()) return
        val app = getApplication<Application>()

        val live = VpnEngine.connectedUuid.value ?: VpnEngine.pendingUuid.value
        if (live != null && live in uuids) VpnEngine.disconnect(app)

        uuids.forEach { uuid ->
            store.clearSelectionIfMatches(uuid)
            if (_tileUuid.value == uuid) _tileUuid.value = null
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                uuids.forEach { VpnEngine.deleteProfile(app, it) }
            }
            _selected.value = emptySet()
            refresh()
            _message.value = app.resources.getQuantityString(
                R.plurals.deleted_count, uuids.size, uuids.size,
            )
        }
    }

    /** A finished backup file waiting to leave through the share sheet. */
    data class ShareRequest(val file: java.io.File, val mime: String, val title: String)
    private val _shareFile = MutableStateFlow<ShareRequest?>(null)
    val shareFile: StateFlow<ShareRequest?> = _shareFile.asStateFlow()
    fun consumeShareFile() { _shareFile.value = null }

    /** Builds a full backup zip and hands it to the Activity for sharing. */
    fun exportAll() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val file = withContext(Dispatchers.IO) {
                BackupManager.exportAll(app, allProfiles)
            }
            if (file == null) {
                _message.value = app.getString(R.string.backup_export_failed)
            } else {
                _shareFile.value = ShareRequest(
                    file = file,
                    mime = "application/zip",
                    title = app.getString(R.string.backup_share_title),
                )
            }
        }
    }

    fun rename(uuid: String, newName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                VpnEngine.renameProfile(getApplication(), uuid, newName)
            }
            refresh()
        }
    }

    // ------------------------------------------------------------- settings

    /** Loaded OpenVPN settings, or null when that editor is closed. */
    private val _settings = MutableStateFlow<ProfileSettings?>(null)
    val settings: StateFlow<ProfileSettings?> = _settings.asStateFlow()

    /** Loaded WireGuard settings, or null when that editor is closed. */
    private val _wgSettings = MutableStateFlow<WireGuardSettings?>(null)
    val wgSettings: StateFlow<WireGuardSettings?> = _wgSettings.asStateFlow()

    /** Loaded Xray settings, or null when that editor is closed. */
    private val _xraySettings = MutableStateFlow<XraySettings?>(null)
    val xraySettings: StateFlow<XraySettings?> = _xraySettings.asStateFlow()

    /** Loaded IKEv2 profile, or null when that editor is closed. */
    private val _ikev2Settings = MutableStateFlow<Ikev2Profile?>(null)
    val ikev2Settings: StateFlow<Ikev2Profile?> = _ikev2Settings.asStateFlow()

    /**
     * Opens the right editor for [uuid].
     *
     * Each protocol has a genuinely different field set, so they get separate
     * screens rather than one screen full of inputs that are inert for whichever
     * protocol happens to be loaded.
     */
    fun openSettings(uuid: String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val protocol = withContext(Dispatchers.IO) { VpnEngine.protocolOf(app, uuid) }
            when (protocol) {
                Protocol.WIREGUARD -> _wgSettings.value = withContext(Dispatchers.IO) {
                    VpnEngine.wireGuardSettings(app, uuid)
                }
                Protocol.XRAY -> _xraySettings.value = withContext(Dispatchers.IO) {
                    VpnEngine.xraySettings(app, uuid)
                }
                Protocol.OPENVPN -> _settings.value = withContext(Dispatchers.IO) {
                    VpnEngine.profileSettings(app, uuid)
                }
                Protocol.IKEV2 -> {
                    // The certificate bits need the strongSwan store ready before
                    // the screen reads aliases, so init happens here, off-thread.
                    withContext(Dispatchers.IO) { Ikev2Engine.init(app) }
                    _ikev2Settings.value = withContext(Dispatchers.IO) {
                        VpnEngine.ikev2Settings(app, uuid)
                    }
                }
            }
        }
    }

    fun closeSettings() {
        _settings.value = null
        _wgSettings.value = null
        _xraySettings.value = null
        _ikev2Settings.value = null
    }

    /** Creates a blank IKEv2 profile and immediately opens its editor. */
    fun createIkev2Profile() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val fresh = Ikev2Profile(
                uuid = java.util.UUID.randomUUID().toString(),
                name = app.getString(R.string.add_ikev2),
                gateway = "",
            )
            withContext(Dispatchers.IO) { VpnEngine.saveIkev2Settings(app, fresh) }
            refresh()
            openSettings(fresh.uuid)
        }
    }

    fun saveIkev2Settings(updated: Ikev2Profile) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            withContext(Dispatchers.IO) { VpnEngine.saveIkev2Settings(app, updated) }
            _ikev2Settings.value = null
            _message.value = app.getString(R.string.settings_saved)
            refresh()
        }
    }

    fun saveXraySettings(updated: XraySettings) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val ok = withContext(Dispatchers.IO) {
                VpnEngine.saveXraySettings(app, updated)
            }
            if (ok) {
                _xraySettings.value = null
                _message.value = app.getString(R.string.settings_saved)
                refresh()
            } else {
                _message.value = app.getString(R.string.xray_invalid_link)
            }
        }
    }

    fun saveWireGuardSettings(updated: WireGuardSettings) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                VpnEngine.saveWireGuardSettings(getApplication(), updated)
            }
            val app = getApplication<Application>()
            if (ok) {
                _wgSettings.value = null
                _message.value = app.getString(R.string.settings_saved)
                refresh()
            } else {
                // Keep the editor open: the text is invalid and closing would
                // throw away the user's edit along with the error.
                _message.value = app.getString(R.string.wg_invalid_config)
            }
        }
    }

    fun saveSettings(updated: ProfileSettings) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                VpnEngine.saveProfileSettings(getApplication(), updated)
            }
            _settings.value = null
            if (ok) {
                _message.value = getApplication<Application>()
                    .getString(R.string.settings_saved)
                refresh()
            }
        }
    }

    // -------------------------------------------------------------- server test

    /**
     * Latency-tests one profile. Cheap enough to run freely: for Xray it spins a
     * throwaway instance without touching the live tunnel, and for the other two
     * it is a TCP handshake.
     */
    fun testLatency(uuid: String) {
        viewModelScope.launch {
            if (VpnEngine.state.value.isActive) {
                _message.value = getApplication<Application>()
                    .getString(R.string.test_disconnect_first)
                return@launch
            }
            val profile = allProfiles.firstOrNull { it.uuid == uuid } ?: return@launch
            _testing.value = _testing.value + uuid
            val ms = XrayTester.latency(getApplication(), profile, xrayStore)
            _latency.value = _latency.value + (uuid to ms)
            _testing.value = _testing.value - uuid
        }
    }

    /**
     * Tests every profile currently visible.
     *
     * Sequential on purpose. Each Xray test starts a private core instance, and
     * running a dozen of those at once would compete for CPU and skew the very
     * numbers being measured — a parallel sweep produces results that rank
     * servers by scheduling luck rather than by network quality.
     */
    fun testAllLatency() {
        viewModelScope.launch {
            // Refuse while connected. Every Xray measurement builds its own core
            // instance, and doing that alongside the live tunnel's core is what
            // took the whole process down with a native fault.
            if (VpnEngine.state.value.isActive) {
                _message.value = getApplication<Application>()
                    .getString(R.string.test_disconnect_first)
                return@launch
            }
            val targets = _profiles.value
            _testing.value = _testing.value + targets.map { it.uuid }
            targets.forEach { profile ->
                val ms = XrayTester.latency(getApplication(), profile, xrayStore)
                _latency.value = _latency.value + (profile.uuid to ms)
                _testing.value = _testing.value - profile.uuid
            }
        }
    }

    /**
     * Full probe of one Xray profile: latency plus egress country and IP, shown
     * as a message like "153ms (DE) 89.58.40.177".
     */
    fun probeXray(uuid: String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            if (VpnEngine.state.value.isActive) {
                _message.value = app.getString(R.string.test_disconnect_first)
                return@launch
            }
            _testing.value = _testing.value + uuid
            val result = XrayTester.probe(getApplication(), uuid, xrayStore)
            result.latencyMs?.let { _latency.value = _latency.value + (uuid to it) }
            _testing.value = _testing.value - uuid
            _message.value = result.summary()
        }
    }

    /** Throughput test on one Xray profile. Moves real data, so it is explicit. */
    fun testDownloadSpeed(uuid: String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            // Refused while connected, for the same TUN-fd reason as probe. Say so
            // up front rather than letting it fail silently.
            if (VpnEngine.state.value.isActive) {
                _message.value = app.getString(R.string.test_disconnect_first)
                return@launch
            }
            _testing.value = _testing.value + uuid
            _message.value = app.getString(R.string.speed_testing)
            val bytesPerSec = XrayTester.downloadSpeed(getApplication(), uuid, xrayStore)
            _testing.value = _testing.value - uuid
            _message.value = if (bytesPerSec == null) {
                app.getString(R.string.speed_failed)
            } else {
                app.getString(R.string.speed_result, formatRate(bytesPerSec))
            }
        }
    }

    private val xrayStore by lazy { XrayStore(getApplication()) }

    // ------------------------------------------------------------ subscriptions

    fun addSubscription(name: String, url: String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val sub = withContext(Dispatchers.IO) { subStore.add(name, url) }
            if (sub == null) {
                _message.value = app.getString(R.string.sub_invalid_url)
                return@launch
            }
            _subscriptions.value = subStore.all()
            // Fetch straight away: adding a subscription and seeing nothing appear
            // would look broken, and the user is right here watching.
            refreshSubscription(sub.id)
        }
    }

    fun refreshSubscription(id: String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val sub = subStore.all().firstOrNull { it.id == id } ?: return@launch
            _message.value = app.getString(R.string.sub_updating, sub.name)
            val result = subStore.refresh(sub)
            _subscriptions.value = subStore.all()
            refresh()
            _message.value = result.error
                ?: app.getString(R.string.sub_updated, result.imported)
        }
    }

    fun removeSubscription(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { subStore.remove(id) }
            _subscriptions.value = subStore.all()
            // A filter pointing at a deleted subscription would pin the list to
            // an empty space with no visible way back.
            if (_groupFilter.value == id) _groupFilter.value = null
            refresh()
        }
    }

    // ------------------------------------------------------------------ qr code

    /** Opens the QR dialog for [uuid], if it has a shareable link. */
    fun showQr(uuid: String) {
        viewModelScope.launch {
            val profile = _profiles.value.firstOrNull { it.uuid == uuid }
            // Xray shares its standard vless://-style link; WireGuard shares
            // the raw wg config — both importable in the mainstream clients.
            val link = withContext(Dispatchers.IO) {
                when (profile?.protocol) {
                    Protocol.WIREGUARD ->
                        WireGuardStore(getApplication()).rawConfig(uuid)
                    else -> xrayStore.link(uuid)
                }
            }
            if (link == null) {
                _message.value = getApplication<Application>()
                    .getString(R.string.qr_not_shareable)
            } else {
                _qrLink.value = link
            }
        }
    }

    fun dismissQr() {
        _qrLink.value = null
    }

    /** The QR dialog's copy button: close and confirm with a toast. */
    fun onQrCopied() {
        _qrLink.value = null
        _message.value = getApplication<Application>().getString(R.string.config_copied)
    }

    /** Handles a scanned QR payload — it is just a share link. */
    fun onQrScanned(text: String) = importFromText(text)

    private companion object {
        const val KEY_BATTERY_ASKED = "battery_asked"
    }

    /** Surfaces a message from the Activity layer (permission refusals etc.). */
    fun reportMessage(text: String) {
        _message.value = text
    }

    fun consumeMessage() {
        _message.value = null
    }
}
