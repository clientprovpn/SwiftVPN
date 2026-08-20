package ir.swiftvpn

import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.hazeSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.swiftvpn.engine.ThemeMode
import ir.swiftvpn.engine.VpnEngine
import ir.swiftvpn.ui.SwiftVpnTheme
import ir.swiftvpn.ui.isDarkTheme
import ir.swiftvpn.ui.screens.ProfileDetailScreen
import ir.swiftvpn.ui.screens.ProfileListScreen

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val vm: MainViewModel = viewModel()

            val profiles by vm.profiles.collectAsStateWithLifecycle()
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val message by vm.message.collectAsStateWithLifecycle()
            val permissionIntent by vm.permissionIntent.collectAsStateWithLifecycle()
            val batteryIntent by vm.batteryIntent.collectAsStateWithLifecycle()
            val credentialsFor by vm.credentialsFor.collectAsStateWithLifecycle()
            val savedUsername by vm.savedUsername.collectAsStateWithLifecycle()
            val settings by vm.settings.collectAsStateWithLifecycle()
            val wgSettings by vm.wgSettings.collectAsStateWithLifecycle()
            val xraySettings by vm.xraySettings.collectAsStateWithLifecycle()
            val ikev2Settings by vm.ikev2Settings.collectAsStateWithLifecycle()
            val protocolFilter by vm.protocolFilter.collectAsStateWithLifecycle()
            val groupFilter by vm.groupFilter.collectAsStateWithLifecycle()
            val availableProtocols by vm.availableProtocols.collectAsStateWithLifecycle()
            val latency by vm.latency.collectAsStateWithLifecycle()
            val testing by vm.testing.collectAsStateWithLifecycle()
            val subscriptions by vm.subscriptions.collectAsStateWithLifecycle()
            val qrLink by vm.qrLink.collectAsStateWithLifecycle()
            val selected by vm.selected.collectAsStateWithLifecycle()
            val shareFile by vm.shareFile.collectAsStateWithLifecycle()
            var confirmDelete by rememberSaveable { mutableStateOf(false) }
            var showAddDialog by rememberSaveable { mutableStateOf(false) }
            var showSubscriptions by rememberSaveable { mutableStateOf(false) }
            var showLog by rememberSaveable { mutableStateOf(false) }

            val vpnState by VpnEngine.state.collectAsStateWithLifecycle()
            val stateMessage by VpnEngine.stateMessage.collectAsStateWithLifecycle()
            val connectedUuid by VpnEngine.connectedUuid.collectAsStateWithLifecycle()
            val pendingUuid by VpnEngine.pendingUuid.collectAsStateWithLifecycle()
            val traffic by VpnEngine.traffic.collectAsStateWithLifecycle()
            val tunnelInfo by VpnEngine.tunnelInfo.collectAsStateWithLifecycle()
            val logs by VpnEngine.logs.collectAsStateWithLifecycle()
            val connectedSince by VpnEngine.connectedSince.collectAsStateWithLifecycle()

            var openUuid by rememberSaveable { mutableStateOf<String?>(null) }
            val snackbar = remember { SnackbarHostState() }

            // Keep the phone's status-bar icons readable. Without this the
            // system infers icon contrast from the window (which never changes,
            // because the theme is applied inside Compose), so switching the
            // app theme could leave dark icons on a dark shade.
            val dark = isDarkTheme(themeMode)
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightStatusBars = !dark
            }

            // System back: unwind our own screens instead of finishing the
            // Activity. Navigation here is hand-rolled, so nothing is
            // registered with the back dispatcher unless we do it explicitly.
            // Deepest layer wins: settings editor sits above the detail screen.
            BackHandler(
                enabled = settings != null || wgSettings != null ||
                    xraySettings != null || ikev2Settings != null ||
                    showSubscriptions || showLog ||
                    openUuid != null
            ) {
                when {
                    settings != null || wgSettings != null ||
                        xraySettings != null || ikev2Settings != null ->
                        vm.closeSettings()
                    showLog -> showLog = false
                    showSubscriptions -> showSubscriptions = false
                    else -> openUuid = null
                }
            }

            // System VPN consent dialog. A TileService cannot do this, so the
            // Activity owns the grant and the tile defers to it.
            val vpnPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                vm.onPermissionResult(result.resultCode == Activity.RESULT_OK)
            }

            LaunchedEffect(batteryIntent) {
                batteryIntent?.let {
                    runCatching { startActivity(it) }
                    vm.consumeBatteryIntent()
                }
            }
            LaunchedEffect(permissionIntent) {
                permissionIntent?.let { vpnPermissionLauncher.launch(it) }
            }

            // Android 13+ needs this granted for the ongoing connection
            // notification — and therefore the speedometer — to be visible at
            // all. The service still starts without it; the notification is just
            // silently dropped, which looks exactly like a broken feature.
            //
            // Asked on EVERY resume while still denied, not once at first launch.
            // This app is built around the Quick Settings tile, so the usual
            // journey is: open the app once to import a profile, then never open
            // it again. A single prompt that the user swipes away on day one
            // would leave them permanently without a notification, with no way
            // back — a TileService cannot request permissions.
            val notificationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { /* nothing to do; the next resume asks again if still denied */ }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                LaunchedEffect(granted) {
                    if (!granted) {
                        notificationLauncher.launch(
                            android.Manifest.permission.POST_NOTIFICATIONS
                        )
                    }
                }
            }

            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> uri?.let(vm::importFromUri) }

            // A SEPARATE launcher for archives, filtered to zip MIME types so
            // the zip button behaves differently from the file button.
            val zipLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> uri?.let { vm.importFromUri(it, forceZip = true) } }

            // QR scanning. ZXing's contract returns the decoded string, or null
            // when the user backs out of the capture screen.
            val scanLauncher = rememberLauncherForActivityResult(
                com.journeyapps.barcodescanner.ScanContract()
            ) { result ->
                result?.contents?.let(vm::onQrScanned)
            }

            fun launchScan() {
                scanLauncher.launch(
                    com.journeyapps.barcodescanner.ScanOptions().apply {
                        setDesiredBarcodeFormats(
                            com.journeyapps.barcodescanner.ScanOptions.QR_CODE
                        )
                        setPrompt(getString(R.string.qr_prompt))
                        setBeepEnabled(false)
                        // Portrait lock: the capture activity defaults to sensor
                        // orientation, which makes it rotate mid-scan.
                        setOrientationLocked(true)
                    }
                )
            }

            // CAMERA is requested at the moment of use, never at launch. If it is
            // refused the scan simply does not open and the user is told why —
            // pasting a link still works, so this is a soft failure.
            val cameraLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    launchScan()
                } else {
                    vm.reportMessage(getString(R.string.qr_camera_denied))
                }
            }

            fun startScan() {
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.CAMERA,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted) launchScan()
                else cameraLauncher.launch(android.Manifest.permission.CAMERA)
            }

            LaunchedEffect(message) {
                message?.let {
                    snackbar.showSnackbar(it)
                    vm.consumeMessage()
                }
            }

            // A backup zip is ready: hand it to the share sheet through our
            // FileProvider, the same pattern as the log share.
            LaunchedEffect(shareFile) {
                shareFile?.let { req ->
                    runCatching {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            this@MainActivity,
                            "$packageName.fileprovider",
                            req.file,
                        )
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = req.mime
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            putExtra(android.content.Intent.EXTRA_SUBJECT, req.title)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(android.content.Intent.createChooser(send, req.title))
                    }
                    vm.consumeShareFile()
                }
            }

            SwiftVpnTheme(mode = themeMode) {
                val hazeState = remember { dev.chrisbanes.haze.HazeState() }
                androidx.compose.runtime.CompositionLocalProvider(
                    ir.swiftvpn.ui.components.LocalHazeState provides hazeState,
                ) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxSize()
                        .background(ir.swiftvpn.ui.GlassTokens.backdropBrush())
                        // Everything visible (gradient + scrolling cards) must be
                        // INSIDE the source for panes to blur it.
                        .hazeSource(hazeState),
                ) {
                // Transparent colour BUT an explicit content colour: with the
                // scaffold transparent, text would otherwise fall back to a
                // default that is dark even in the dark theme — unreadable on
                // glass. Surface republishes onBackground as LocalContentColor.
                androidx.compose.material3.Surface(
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                ) {
                // Surface a crash from the previous run — including one from
                // the engine's separate process, which otherwise leaves no
                // trace inside the app.
                var crash by remember {
                    // A Java crash report if there is one; otherwise the
                    // readable summary of the last NATIVE crash's tombstone;
                    // otherwise a leftover breadcrumb — the coarsest trace.
                    mutableStateOf(
                        ir.swiftvpn.engine.CrashReporter.lastCrash(this@MainActivity)
                            ?: ir.swiftvpn.engine.CrashReporter.lastTombstoneSummary(this@MainActivity)
                                ?.let { "Native crash (system tombstone):\n\n$it" }
                            ?: ir.swiftvpn.engine.CrashReporter.lastBreadcrumb(this@MainActivity)
                                ?.let { "Native crash during: $it" }
                    )
                }
                crash?.let { report ->
                    ir.swiftvpn.ui.components.CrashDialog(
                        report = report,
                        onDismiss = {
                            ir.swiftvpn.engine.CrashReporter.clear(this@MainActivity)
                            ir.swiftvpn.engine.CrashReporter.breadcrumb(this@MainActivity, null)
                            crash = null
                        },
                    )
                }

                credentialsFor?.let { profile ->
                    ir.swiftvpn.ui.components.CredentialsDialog(
                        profileName = profile.name,
                        initialUsername = savedUsername,
                        onDismiss = vm::dismissCredentials,
                        onConfirm = vm::submitCredentials,
                    )
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    // Transparent: the glass backdrop gradient shows through and
                    // every pane floats above it.
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                ) { _ ->
                    val current = openUuid?.let { uuid ->
                        profiles.firstOrNull { it.uuid == uuid }
                    }

                    // A settings editor takes over the whole screen when open.
                    // Which one depends on the profile's protocol; the two field
                    // sets are too different to share a screen.
                    val editing = settings
                    if (editing != null) {
                        ir.swiftvpn.ui.screens.ProfileSettingsScreen(
                            initial = editing,
                            onBack = vm::closeSettings,
                            onSave = vm::saveSettings,
                        )
                        return@Scaffold
                    }

                    val editingWg = wgSettings
                    if (editingWg != null) {
                        ir.swiftvpn.ui.screens.WireGuardSettingsScreen(
                            initial = editingWg,
                            onBack = vm::closeSettings,
                            onSave = vm::saveWireGuardSettings,
                        )
                        return@Scaffold
                    }

                    val editingXray = xraySettings
                    if (editingXray != null) {
                        ir.swiftvpn.ui.screens.XraySettingsScreen(
                            initial = editingXray,
                            onBack = vm::closeSettings,
                            onSave = vm::saveXraySettings,
                        )
                        return@Scaffold
                    }

                    val editingIkev2 = ikev2Settings
                    if (editingIkev2 != null) {
                        ir.swiftvpn.ui.screens.Ikev2SettingsScreen(
                            initial = editingIkev2,
                            onBack = vm::closeSettings,
                            onSave = vm::saveIkev2Settings,
                            onPickClientCert = { callback ->
                                // The system KeyChain picker needs a live Activity;
                                // the alias comes back on this callback.
                                android.security.KeyChain.choosePrivateKeyAlias(
                                    this@MainActivity,
                                    { alias -> callback(alias) },
                                    null, // keyTypes — any private key works
                                    null, // issuers
                                    null, // host
                                    -1,   // port
                                    null, // alias preselection
                                )
                            },
                        )
                        return@Scaffold
                    }

                    if (showLog) {
                        ir.swiftvpn.ui.screens.DiagnosticLogScreen(
                            onBack = { showLog = false },
                            onShare = { shareLog() },
                            onCopy = { copyLog() },
                            onClear = {
                                ir.swiftvpn.engine.DiagnosticLog.clear(this@MainActivity)
                                vm.reportMessage(getString(R.string.log_cleared))
                            },
                        )
                        return@Scaffold
                    }

                    if (showSubscriptions) {
                        ir.swiftvpn.ui.screens.SubscriptionsScreen(
                            subscriptions = subscriptions,
                            onBack = { showSubscriptions = false },
                            onAdd = vm::addSubscription,
                            onRefresh = vm::refreshSubscription,
                            onRemove = vm::removeSubscription,
                            onOpenGroup = { id ->
                                vm.setGroupFilter(id)
                                showSubscriptions = false
                            },
                        )
                        return@Scaffold
                    }

                    if (current == null) {
                        ProfileListScreen(
                            profiles = profiles,
                            stateFor = { uuid ->
                                // Recomputed on every state change because both
                                // flows below are observed in this composition.
                                if (uuid == connectedUuid) vpnState
                                else if (uuid == pendingUuid) {
                                    if (vpnState.isActive) vpnState
                                    else ir.swiftvpn.engine.VpnState.CONNECTING
                                } else ir.swiftvpn.engine.VpnState.DISCONNECTED
                            },
                            themeMode = themeMode,
                            protocolFilter = protocolFilter,
                            availableProtocols = availableProtocols,
                            subscriptions = subscriptions,
                            groupFilter = groupFilter,
                            onSetGroupFilter = vm::setGroupFilter,
                            latency = latency,
                            testing = testing,
                            onToggleProfile = vm::toggle,
                            onOpenProfile = { openUuid = it },
                            onToggleFavourite = vm::toggleFavourite,
                            onSetProtocolFilter = vm::setProtocolFilter,
                            onCycleTheme = { vm.setTheme(themeMode.next()) },
                            onImport = { showAddDialog = true },
                            onOpenSubscriptions = { showSubscriptions = true },
                            onOpenLog = { showLog = true },
                            onBackup = vm::exportAll,
                            selected = selected,
                            onToggleSelected = vm::toggleSelected,
                            onSelectAll = vm::selectAllVisible,
                            onClearSelection = vm::clearSelection,
                            onDeleteSelected = { confirmDelete = true },
                        )
                    } else {
                        ProfileDetailScreen(
                            profile = current,
                            vpnState = when (current.uuid) {
                                connectedUuid -> vpnState
                                pendingUuid -> if (vpnState.isActive) vpnState
                                    else ir.swiftvpn.engine.VpnState.CONNECTING
                                else -> ir.swiftvpn.engine.VpnState.DISCONNECTED
                            },
                            stateMessage = stateMessage,
                            traffic = traffic,
                            tunnelInfo = tunnelInfo,
                            logs = logs,
                            connectedSince = connectedSince,
                            onBack = { openUuid = null },
                            onToggle = { vm.toggle(current.uuid) },
                            onOpenSettings = { vm.openSettings(current.uuid) },
                            onDelete = {
                                vm.delete(current.uuid)
                                openUuid = null
                            },
                            onClearLog = VpnEngine::clearLogs,
                            onShowQr = { vm.showQr(current.uuid) },
                        )
                    }

                    if (showAddDialog) {
                        ir.swiftvpn.ui.components.AddProfileDialog(
                            onDismiss = { showAddDialog = false },
                            onPasteLink = { text ->
                                showAddDialog = false
                                vm.importFromText(text)
                            },
                            onPickFile = {
                                showAddDialog = false
                                // .ovpn has no registered MIME type on most
                                // devices, so accept anything and let the parser
                                // reject bad input.
                                importLauncher.launch(arrayOf("*/*"))
                            },
                            onPickZip = {
                                showAddDialog = false
                                zipLauncher.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/x-zip-compressed",
                                        "application/octet-stream",
                                    )
                                )
                            },
                            onScanQr = {
                                showAddDialog = false
                                startScan()
                            },
                            onAddIkev2 = {
                                showAddDialog = false
                                vm.createIkev2Profile()
                            },
                        )
                    }

                    if (confirmDelete && selected.isNotEmpty()) {
                        val live = connectedUuid ?: pendingUuid
                        ir.swiftvpn.ui.components.DeleteConfirmDialog(
                            count = selected.size,
                            singleName = selected.singleOrNull()?.let { uuid ->
                                profiles.firstOrNull { it.uuid == uuid }?.name
                            },
                            includesLive = live != null && live in selected,
                            onDismiss = { confirmDelete = false },
                            onConfirm = {
                                confirmDelete = false
                                vm.deleteAll(selected)
                            },
                        )
                    }

                    qrLink?.let { link ->
                        ir.swiftvpn.ui.components.QrDialog(
                            link = link,
                            onDismiss = vm::dismissQr,
                            onCopied = vm::onQrCopied,
                        )
                    }
                }
                }
                }
                }
            }
        }
    }

    /** Puts the whole log on the clipboard, for pasting into a chat. */
    private fun copyLog() {
        val text = ir.swiftvpn.engine.DiagnosticLog.exportText(this)
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(
            android.content.ClipData.newPlainText(getString(R.string.log_title), text)
        )
        // Android 13+ shows its own copy confirmation, so a second toast there
        // would be duplicate noise.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            android.widget.Toast.makeText(
                this,
                getString(R.string.log_copied),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /**
     * Shares the log as a file attachment.
     *
     * A file rather than plain text: logs run to thousands of lines, and an
     * Intent extra that large is silently truncated or throws
     * TransactionTooLargeException. The file goes out through our FileProvider as
     * a content:// URI with a one-shot read grant — passing a file:// URI would
     * throw FileUriExposedException on anything modern.
     */
    private fun shareLog() {
        val file = ir.swiftvpn.engine.DiagnosticLog.exportToFile(this)
        if (file == null) {
            android.widget.Toast.makeText(
                this,
                getString(R.string.log_share_failed),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file,
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(
                    android.content.Intent.EXTRA_SUBJECT,
                    getString(R.string.log_share_title),
                )
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(
                android.content.Intent.createChooser(
                    send,
                    getString(R.string.log_share),
                )
            )
        }.onFailure {
            android.widget.Toast.makeText(
                this,
                getString(R.string.log_share_failed),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Profiles can change from the tile or another entry point.
        // Cheap enough to re-read on every resume.
    }
}

/** Two states only, so one tap always visibly changes the theme. */
private fun ThemeMode.next(): ThemeMode =
    if (this == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK

