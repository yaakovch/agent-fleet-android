package com.termux.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.termux.app.fleet.FleetHost
import com.termux.app.fleet.AgentFleetContract
import com.termux.app.fleet.FleetAttention
import com.termux.app.fleet.FleetDirectoryListing
import com.termux.app.fleet.FleetDownloadCancellation
import com.termux.app.fleet.FleetDownloadState
import com.termux.app.fleet.FleetRepositoryEntry
import com.termux.app.fleet.FleetRepositoryPage
import com.termux.app.fleet.isRetryableRepositoryFailure
import com.termux.app.fleet.FleetLoadState
import com.termux.app.fleet.FleetLimit
import com.termux.app.fleet.FleetLimitWindow
import com.termux.app.fleet.FleetRuntime
import com.termux.app.fleet.FleetSnapshotStore
import com.termux.app.fleet.defaultLimitScheduleTime
import com.termux.app.fleet.showLimitDateTimePicker
import com.termux.app.fleet.FleetSession
import com.termux.app.fleet.FleetSchedule
import com.termux.app.fleet.FleetSnapshot
import com.termux.app.fleet.FleetAlert
import com.termux.app.fleet.FleetAlertCategory
import com.termux.app.fleet.FleetAlertSettings
import com.termux.app.fleet.FleetAlertSettingsStore
import com.termux.app.fleet.FleetAlertTarget
import com.termux.app.fleet.FleetAlertTracker
import com.termux.app.fleet.FleetPairingRequest
import com.termux.app.fleet.FleetPairingReview
import com.termux.app.fleet.NativeSessionSettings
import com.termux.app.fleet.NativeSessionRegistry
import com.termux.app.fleet.AutomaticSessionTitleSettings
import com.termux.app.fleet.sessionIdentityPresentation
import com.termux.app.fleet.transportHostId
import com.termux.app.fleet.physicalHostRecoveryDetail
import com.termux.app.fleet.selectedTransportEndpoint
import com.termux.app.fleet.transportEndpointLabel
import com.termux.app.fleet.LocalModelUiState
import com.termux.app.fleet.LocalSuggestionModel
import com.termux.app.fleet.LocalSuggestionModelManager
import com.termux.app.fleet.LocalSuggestionMode
import com.termux.app.fleet.LayeredDiagnostics
import com.termux.app.fleet.LocalSuggestionRuntime
import com.termux.app.fleet.formatModelBytes
import com.termux.app.fleet.RecentSessionStore
import com.termux.app.fleet.DrawerSessionStore
import com.termux.app.fleet.RecentLocationStore
import com.termux.app.fleet.AgentFleetUpdate
import com.termux.app.fleet.AgentFleetUpdateManager
import com.termux.app.fleet.ClientPolicyStore
import com.termux.app.fleet.EmbeddedRuntimeManager
import com.termux.app.fleet.EmbeddedRuntimeStatus
import com.termux.app.fleet.RuntimeUpdateManager
import com.termux.app.fleet.RuntimeUpdateResult
import com.termux.app.fleet.supportsEmbeddedRuntime
import com.termux.app.fleet.shouldInstallEmbeddedBaseline
import com.termux.app.fleet.UpdateUiState
import com.termux.app.migration.AgentFleetMigrationArchive
import com.termux.app.migration.AgentFleetMigrationPeer
import com.termux.app.fleet.AgentFleetDiagnosticJournal
import com.termux.app.fleet.AgentFleetDiagnosticReport
import com.termux.app.fleet.AgentFleetDiagnosticEvent
import com.termux.app.fleet.AgentFleetDiagnosticsRunner
import com.termux.app.fleet.DiagnosticsUiState
import com.termux.app.fleet.AndroidWorkspaceStore
import com.termux.app.fleet.isFleetSessionAvailable
import com.termux.app.fleet.WorkspacePresentationMode
import com.termux.app.fleet.WorkspacePresentationStore
import com.termux.app.fleet.WorkspaceReducer
import com.termux.app.fleet.WorkspaceTerminalBroker
import com.termux.app.fleet.isDesktopPresentation
import com.termux.app.fleet.workspacePanes
import com.termux.app.fleet.TransportContract
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal fun ExecutorService.executeLifecycleTask(task: () -> Unit): Boolean {
    if (isShutdown) return false
    return try {
        execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }
}

class AgentFleetActivity : ComponentActivity() {
    private val fleetState = mutableStateOf<FleetLoadState>(FleetLoadState.Loading)
    private val recentSessions = mutableStateOf<List<FleetSession>>(emptyList())
    private val pendingPairInvitation = mutableStateOf<String?>(null)
    private val pendingSharedImages = mutableStateOf<List<String>>(emptyList())
    private val focusedSession = mutableStateOf<String?>(null)
    private val focusedSessionSerial = mutableStateOf(0L)
    private val updateState = mutableStateOf<UpdateUiState>(UpdateUiState.Idle)
    private val updateManifestUrl = mutableStateOf("")
    private val runtimeUi = mutableStateOf(RuntimeUiState())
    private val diagnosticsUi = mutableStateOf(DiagnosticsUiState())
    private val diagnosticError = mutableStateOf<AgentFleetDiagnosticEvent?>(null)
    private val localModelUi = mutableStateOf(LocalModelUiState())
    private val fleetAlerts = mutableStateOf<List<FleetAlert>>(emptyList())
    private val fleetAlertSettings = mutableStateOf(FleetAlertSettings())
    private val pairingReviewUi = mutableStateOf<PairingReviewUiState?>(null)
    private var pairingReviewGeneration = 0L
    private var fleetAlertTracker = FleetAlertTracker()
    private val fleetExecutor = Executors.newSingleThreadExecutor()
    private val updateExecutor = Executors.newSingleThreadExecutor()
    private val runtimeExecutor = Executors.newSingleThreadExecutor()
    private val fileExecutor = Executors.newFixedThreadPool(2)
    private val diagnosticsExecutor = Executors.newSingleThreadExecutor()
    private val fileDownloads = ConcurrentHashMap.newKeySet<FleetDownloadCancellation>()
    private lateinit var fleetRuntime: FleetRuntime
    private lateinit var recentSessionStore: RecentSessionStore
    private lateinit var drawerSessionStore: DrawerSessionStore
    private lateinit var updateManager: AgentFleetUpdateManager
    private lateinit var embeddedRuntime: EmbeddedRuntimeManager
    private lateinit var runtimeUpdateManager: RuntimeUpdateManager
    private lateinit var clientPolicyStore: ClientPolicyStore
    private lateinit var diagnosticJournal: AgentFleetDiagnosticJournal
    private lateinit var diagnosticsRunner: AgentFleetDiagnosticsRunner
    private lateinit var workspaceTerminalBroker: WorkspaceTerminalBroker
    private lateinit var workspaceNativeSessionRegistry: NativeSessionRegistry<WorkspaceRetainedNativeSession>
    private lateinit var localSuggestionModelManager: LocalSuggestionModelManager
    private lateinit var fleetAlertSettingsStore: FleetAlertSettingsStore
    private val localModelImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && ::localSuggestionModelManager.isInitialized) localSuggestionModelManager.import(uri)
    }
    private val migrationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null) return@registerForActivityResult
        fileExecutor.submit {
            runCatching { AgentFleetMigrationArchive.import(applicationContext, uri) }
                .onSuccess { imported ->
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Imported ${imported.preferenceStores} settings groups and ${imported.files} Fleet files. Restarting Agent Fleet…",
                            Toast.LENGTH_LONG
                        ).show()
                        recreate()
                    }
                }
                .onFailure { error ->
                    runOnUiThread { Toast.makeText(this, error.message ?: "Migration import failed", Toast.LENGTH_LONG).show() }
                }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fleetRuntime = FleetRuntime(applicationContext)
        recentSessionStore = RecentSessionStore(applicationContext)
        drawerSessionStore = DrawerSessionStore(applicationContext)
        updateManager = AgentFleetUpdateManager(applicationContext)
        embeddedRuntime = EmbeddedRuntimeManager(applicationContext)
        runtimeUpdateManager = RuntimeUpdateManager(applicationContext, embeddedRuntime)
        fleetAlertSettingsStore = FleetAlertSettingsStore(applicationContext)
        fleetAlertSettings.value = fleetAlertSettingsStore.load()
        clientPolicyStore = ClientPolicyStore(applicationContext)
        diagnosticJournal = AgentFleetDiagnosticJournal(applicationContext)
        localSuggestionModelManager = LocalSuggestionModelManager(applicationContext) { localModelUi.value = it }
        runCatching { AgentFleetMigrationArchive.repairMigratedPrivateRoots(applicationContext) }
            .onSuccess { repaired ->
                if (repaired > 0) diagnosticJournal.record(
                    "migration.root_repair", "success", message = "Repaired migrated private paths"
                )
            }
            .onFailure {
                diagnosticJournal.record(
                    "migration.root_repair", "failure", code = "migration_root_repair_failed",
                    message = "Migrated private paths could not be repaired"
                )
            }
        diagnosticsRunner = AgentFleetDiagnosticsRunner(
            applicationContext, embeddedRuntime, clientPolicyStore, fleetRuntime, diagnosticJournal
        )
        workspaceTerminalBroker = WorkspaceTerminalBroker(applicationContext).also { it.start() }
        workspaceNativeSessionRegistry = NativeSessionRegistry(applicationContext)
        refreshUpdatePolicy()
        recentSessions.value = recentSessionStore.load()
        val cleanTerminal = !File(filesDir, "usr/bin/bash").canExecute()
        val offlineRuntimeSupported = runCatching {
            supportsEmbeddedRuntime(android.os.Build.SUPPORTED_ABIS.firstOrNull(), embeddedRuntime.descriptor().supportedAbis)
        }.getOrDefault(false)
        val automaticPreparation = cleanTerminal && offlineRuntimeSupported
        if (automaticPreparation) runtimeUi.value = RuntimeUiState(
            busy = true, blocking = true, detail = "Installing the built-in terminal…"
        )
        acceptPairingIntent(intent)
        acceptSharedImages(intent)
        acceptFocusedSession(intent)
        setContent {
            AgentFleetTheme {
                CompositionLocalProvider(
                    LocalWorkspaceNativeSessionRegistry provides workspaceNativeSessionRegistry
                ) {
                    AgentFleetApp(
                    fleetState = fleetState.value,
                    recentSessions = recentSessions.value,
                    pendingPairInvitation = pendingPairInvitation.value,
                    onPairInvitationHandled = { pendingPairInvitation.value = null },
                    pendingSharedImages = pendingSharedImages.value,
                    updateState = updateState.value,
                    updateManifestUrl = updateManifestUrl.value,
                    runtimeUi = runtimeUi.value,
                    diagnosticsUi = diagnosticsUi.value,
                    diagnosticError = diagnosticError.value,
                    localModelUi = localModelUi.value,
                    onSharedImagesHandled = { pendingSharedImages.value = emptyList() },
                    onRefresh = ::reconnectFleet,
                    onOpenSession = ::openFleetSession,
                    onOpenSessionWithImages = ::openFleetSessionWithImages,
                    onCreateSession = ::createFleetSession,
                    onListDirectory = ::listFleetDirectory,
                    onCreateDirectory = ::createFleetDirectory,
                    onListRepository = ::listFleetRepository,
                    onSearchRepository = ::searchFleetRepository,
                    onDownloadRepository = ::downloadFleetRepository,
                    onCloseRepository = fleetRuntime::closeRepositoryBrowser,
                    onOpenDownload = ::openFleetDownload,
                    onRenameSession = ::renameFleetSession,
                    onResetSessionName = ::resetFleetSessionName,
                    onScheduleContinue = ::scheduleContinue,
                    onScheduleAttention = ::scheduleAttention,
                    onDismissAttention = ::dismissAttention,
                    onCancelSchedule = ::cancelSchedule,
                    onKillSession = ::killFleetSession,
                    onCopyAttachCommand = ::copyAttachCommand,
                    onPairInvitation = ::openPairing,
                    onCheckUpdate = ::checkForUpdate,
                    onInstallUpdate = ::installUpdate,
                    onRepairRuntime = { prepareEmbeddedRuntime(autoRepair = true, blocking = runtimeUi.value.blocking) },
                    onCheckRuntime = { checkRuntimeUpdate(manual = true) },
                    onRollbackRuntime = ::rollbackRuntime,
                    onRestoreBaseline = ::restoreBaseline,
                    onOpenAppearance = {
                        startActivity(Intent(this, TerminalAppearanceActivity::class.java))
                    },
                    onMigrateFleetState = ::migrateFleetState,
                    onSetLocalSuggestions = localSuggestionModelManager::setMode,
                    onDownloadLocalModel = localSuggestionModelManager::download,
                    onImportLocalModel = { localModelImportLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
                    onCancelLocalModel = localSuggestionModelManager::cancel,
                    onRemoveLocalModel = localSuggestionModelManager::remove,
                    onRunDiagnostics = ::runDiagnostics,
                    onCopyDiagnostics = ::copyDiagnostics,
                    onExportDiagnostics = ::exportDiagnostics,
                    onCopyDiagnosticError = ::copyDiagnosticError,
                    onDiagnosticErrorHandled = { diagnosticError.value = null },
                    workspaceTerminalBroker = workspaceTerminalBroker,
                    initialActionSession = focusedSession.value,
                    initialActionSerial = focusedSessionSerial.value,
                    fleetAlerts = fleetAlerts.value,
                    fleetAlertSettings = fleetAlertSettings.value,
                    onDismissFleetAlert = {
                        if (fleetAlerts.value.isNotEmpty()) fleetAlerts.value = fleetAlerts.value.drop(1)
                    },
                    onFleetAlertSettings = ::saveFleetAlertSettings,
                    onPauseFleetAlerts = {
                        fleetAlertSettings.value = fleetAlertSettingsStore.pauseForOneHour(System.currentTimeMillis())
                    },
                    onResumeFleetAlerts = {
                        fleetAlertSettings.value = fleetAlertSettingsStore.resume()
                    },
                    pairingReviewUi = pairingReviewUi.value,
                    onReviewPairingRequest = ::reviewPairingRequest,
                    onDismissPairingReview = ::dismissPairingReview,
                    onDecidePairingRequest = ::decidePairingRequest
                    )
                }
            }
        }
        TermuxInstaller.setupBootstrapIfNeeded(this) {
            if (!isFinishing && !isDestroyed) {
                prepareEmbeddedRuntime(autoRepair = automaticPreparation, blocking = automaticPreparation)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LocalSuggestionRuntime.onSurfaceStarted(this)
        if (::workspaceNativeSessionRegistry.isInitialized) workspaceNativeSessionRegistry.onActivityStart()
        fleetAlertTracker = FleetAlertTracker()
        if (::workspaceTerminalBroker.isInitialized) workspaceTerminalBroker.refreshAttachments()
        refreshUpdatePolicy()
        FleetSnapshotStore.observe(applicationContext, this) { state ->
            fleetState.value = state
            val snapshot = (state as? FleetLoadState.Ready)?.snapshot ?: return@observe
            val alerts = fleetAlertTracker.process(
                snapshot = snapshot,
                settings = fleetAlertSettings.value,
                nowEpochMillis = System.currentTimeMillis(),
                expectedHostRuntimeVersion = runtimeUpdateManager.verifiedExpectedHostRuntimeVersion()
            )
            if (alerts.isNotEmpty()) {
                fleetAlerts.value = enqueueFleetAlerts(fleetAlerts.value, alerts)
            }
        }
        if (
            ::runtimeUpdateManager.isInitialized && !runtimeUi.value.busy && runtimeUi.value.status?.usable == true &&
            runtimeUpdateManager.shouldCheck()
        ) checkRuntimeUpdate(manual = false)
    }

    private fun migrateFleetState() {
        runCatching { AgentFleetMigrationPeer.exportIntent(this) }
            .onSuccess(migrationLauncher::launch)
            .onFailure { Toast.makeText(this, it.message ?: "The other Agent Fleet app is unavailable", Toast.LENGTH_LONG).show() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptPairingIntent(intent)
        acceptSharedImages(intent)
        acceptFocusedSession(intent)
    }

    override fun onStop() {
        fleetAlerts.value = emptyList()
        if (::fleetRuntime.isInitialized) fleetRuntime.closeRepositoryBrowser()
        FleetSnapshotStore.removeObserver(this)
        if (::workspaceNativeSessionRegistry.isInitialized) workspaceNativeSessionRegistry.onActivityStop()
        LocalSuggestionRuntime.onSurfaceStopped(applicationContext, this)
        super.onStop()
    }

    private fun saveFleetAlertSettings(value: FleetAlertSettings) {
        fleetAlertSettingsStore.save(value)
        fleetAlertSettings.value = value
    }

    private fun reviewPairingRequest(requestId: String) {
        val snapshot = (fleetState.value as? FleetLoadState.Ready)?.snapshot ?: return refreshFleet()
        if (snapshot.pairingRequests.none { it.id == requestId && it.status == "awaiting-review" }) {
            Toast.makeText(this, "Pairing request is no longer awaiting review.", Toast.LENGTH_SHORT).show()
            return
        }
        val generation = Math.addExact(pairingReviewGeneration, 1L)
        pairingReviewGeneration = generation
        pairingReviewUi.value = PairingReviewUiState(requestId, loading = true)
        fleetExecutor.execute {
            val result = runCatching { fleetRuntime.reviewPairing(snapshot, requestId) }
            runOnUiThread {
                if (generation != pairingReviewGeneration) return@runOnUiThread
                result.onSuccess { reviewed ->
                    FleetSnapshotStore.publish(reviewed.snapshot)
                    pairingReviewUi.value = PairingReviewUiState(
                        requestId,
                        review = reviewed.review,
                        snapshotRevision = reviewed.snapshot.revision
                    )
                }.onFailure { error ->
                    pairingReviewUi.value = PairingReviewUiState(
                        requestId,
                        error = error.message ?: "Pairing proposal could not be reviewed."
                    )
                    refreshFleet()
                }
            }
        }
    }

    private fun decidePairingRequest(requestId: String, approve: Boolean) {
        val snapshot = (fleetState.value as? FleetLoadState.Ready)?.snapshot ?: return refreshFleet()
        val reviewed = pairingReviewUi.value
        when (pairingDecisionReadiness(reviewed, requestId, snapshot.revision)) {
            PairingDecisionReadiness.Busy -> return
            PairingDecisionReadiness.NeedsReview -> return reviewPairingRequest(requestId)
            PairingDecisionReadiness.Ready -> Unit
        }
        val generation = Math.addExact(pairingReviewGeneration, 1L)
        pairingReviewGeneration = generation
        pairingReviewUi.value = requireNotNull(reviewed).copy(loading = true, error = "")
        fleetExecutor.execute {
            val result = runCatching { fleetRuntime.decidePairing(snapshot, requestId, approve) }
            runOnUiThread {
                if (generation != pairingReviewGeneration) return@runOnUiThread
                result.onSuccess { updated ->
                    FleetSnapshotStore.publish(updated)
                    pairingReviewUi.value = null
                    Toast.makeText(
                        this,
                        if (approve) "Pairing approved" else "Pairing rejected",
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure { error ->
                    pairingReviewUi.value = PairingReviewUiState(
                        requestId = requestId,
                        error = error.message ?: "Pairing decision failed. Refresh and review again."
                    )
                    reportDiagnosticError("pairing.decision", error)
                    refreshFleet()
                }
            }
        }
    }

    private fun dismissPairingReview() {
        pairingReviewGeneration = Math.addExact(pairingReviewGeneration, 1L)
        pairingReviewUi.value = null
    }

    override fun onDestroy() {
        LocalSuggestionRuntime.onSurfaceStopped(applicationContext, this)
        fileDownloads.forEach { it.cancel() }
        fileDownloads.clear()
        if (::workspaceNativeSessionRegistry.isInitialized) workspaceNativeSessionRegistry.destroy()
        fleetExecutor.shutdownNow()
        updateExecutor.shutdownNow()
        runtimeExecutor.shutdownNow()
        fileExecutor.shutdownNow()
        diagnosticsExecutor.shutdownNow()
        if (::workspaceTerminalBroker.isInitialized) workspaceTerminalBroker.close()
        if (::localSuggestionModelManager.isInitialized) localSuggestionModelManager.close()
        if (::fleetRuntime.isInitialized) fleetRuntime.shutdown()
        super.onDestroy()
    }

    private fun refreshFleet() {
        FleetSnapshotStore.refresh(showLoading = true)
    }

    private fun reconnectFleet() {
        FleetSnapshotStore.refresh(showLoading = true, reconnect = true)
    }

    private fun runDiagnostics() {
        if (diagnosticsUi.value.running) return
        diagnosticsUi.value = diagnosticsUi.value.copy(running = true, error = "")
        val snapshot = (fleetState.value as? FleetLoadState.Ready)?.snapshot
        diagnosticsExecutor.execute {
            val result = runCatching { diagnosticsRunner.run(snapshot) }
            runOnUiThread {
                diagnosticsUi.value = result.fold(
                    onSuccess = { DiagnosticsUiState(report = it) },
                    onFailure = {
                        diagnosticJournal.record("diagnostics.run", "failure", code = "run_failed", message = it.message.orEmpty())
                        DiagnosticsUiState(error = it.message ?: "Diagnostics could not finish")
                    }
                )
            }
        }
    }

    private fun copyDiagnostics(report: AgentFleetDiagnosticReport) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Agent Fleet diagnostics", report.diagnosticsJson()))
        diagnosticJournal.record("diagnostics.copy", "healthy", message = "Redacted report copied")
        Toast.makeText(this, "Redacted diagnostic report copied", Toast.LENGTH_SHORT).show()
    }

    private fun copyDiagnosticError(event: AgentFleetDiagnosticEvent) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Agent Fleet error", "${event.id} · ${event.message}"))
        Toast.makeText(this, "Error details copied", Toast.LENGTH_SHORT).show()
    }

    private fun reportDiagnosticError(
        operation: String,
        error: Throwable,
        hostId: String? = null,
        sessionId: String? = null,
        show: Boolean = true
    ): AgentFleetDiagnosticEvent {
        val event = diagnosticJournal.record(
            operation = operation,
            status = "failure",
            code = (error as? com.termux.app.fleet.FleetUnavailableException)?.code.orEmpty().ifBlank { "operation_failed" },
            message = error.message ?: "Operation failed",
            hostId = hostId,
            sessionId = sessionId
        )
        if (show) diagnosticError.value = event
        return event
    }

    private fun exportDiagnostics(report: AgentFleetDiagnosticReport) {
        diagnosticsExecutor.execute {
            val result = runCatching { diagnosticsRunner.export(report) }
            runOnUiThread {
                result.onSuccess { file ->
                    val uri = FileProvider.getUriForFile(this, "$packageName.agentfleet.images", file)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(send, "Share diagnostics"))
                }.onFailure {
                    diagnosticJournal.record("diagnostics.export", "failure", code = "export_failed", message = it.message.orEmpty())
                    Toast.makeText(this, it.message ?: "Diagnostics export failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openFleetSession(session: FleetSession) {
        try {
            recentSessionStore.record(session)
            drawerSessionStore.recordOpened(session)
            recentSessions.value = recentSessionStore.load()
            workspaceTerminalBroker.openFullscreen(session)
        } catch (error: Exception) {
            reportDiagnosticError("session.open", error, session.hostId, session.id)
            Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun openFleetSessionWithImages(session: FleetSession, images: List<String>) {
        try {
            recentSessionStore.record(session)
            drawerSessionStore.recordOpened(session)
            recentSessions.value = recentSessionStore.load()
            workspaceTerminalBroker.openFullscreen(session, images)
        } catch (error: Exception) {
            reportDiagnosticError("session.open_with_images", error, session.hostId, session.id)
            Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun renameFleetSession(session: FleetSession, name: String) = mutateFleet("Session renamed") { snapshot ->
        fleetRuntime.renameSession(snapshot, session, name)
    }

    private fun resetFleetSessionName(session: FleetSession) = mutateFleet("Automatic session title restored") { snapshot ->
        fleetRuntime.resetSessionName(snapshot, session)
    }

    private fun createFleetSession(hostId: String, project: String, backend: String, tool: String, path: String, locationKind: String) = mutateFleet("Session created") { snapshot ->
        fleetRuntime.createSession(snapshot, hostId, project, backend, tool, path, locationKind)
    }

    private fun listFleetDirectory(hostId: String, backend: String, path: String, callback: (Result<FleetDirectoryListing>) -> Unit) {
        val snapshot = (fleetState.value as? FleetLoadState.Ready)?.snapshot
            ?: return callback(Result.failure(IllegalStateException("Fleet is still loading.")))
        fleetExecutor.execute {
            val result = runCatching { fleetRuntime.listDirectory(snapshot, hostId, backend, path) }
            result.exceptionOrNull()?.let { reportDiagnosticError("directory.list", it, hostId = hostId, show = false) }
            runOnUiThread { callback(result) }
        }
    }

    private fun createFleetDirectory(hostId: String, backend: String, parentPath: String, name: String, callback: (Result<String>) -> Unit) {
        val snapshot = (fleetState.value as? FleetLoadState.Ready)?.snapshot
            ?: return callback(Result.failure(IllegalStateException("Fleet is still loading.")))
        fleetExecutor.execute {
            val result = runCatching { fleetRuntime.createDirectory(snapshot, hostId, backend, parentPath, name) }
            result.exceptionOrNull()?.let { reportDiagnosticError("directory.create", it, hostId = hostId, show = false) }
            runOnUiThread { callback(result) }
        }
    }

    private fun listFleetRepository(
        session: FleetSession,
        relativePath: String,
        includeHidden: Boolean,
        cursor: String,
        callback: (Result<FleetRepositoryPage>) -> Unit
    ) {
        val snapshot = (fleetState.value as? FleetLoadState.Ready)?.snapshot
            ?: return callback(Result.failure(IllegalStateException("Fleet is still loading.")))
        fleetExecutor.execute {
            val result = runCatching { fleetRuntime.listRepository(snapshot, session, relativePath, includeHidden, cursor) }
            result.exceptionOrNull()?.let { reportDiagnosticError("repository.list", it, session.hostId, session.id, show = false) }
            runOnUiThread { callback(result) }
        }
    }

    private fun searchFleetRepository(
        session: FleetSession,
        query: String,
        includeHidden: Boolean,
        callback: (Result<FleetRepositoryPage>) -> Unit
    ) {
        val snapshot = (fleetState.value as? FleetLoadState.Ready)?.snapshot
            ?: return callback(Result.failure(IllegalStateException("Fleet is still loading.")))
        fleetExecutor.execute {
            val result = runCatching { fleetRuntime.searchRepository(snapshot, session, query, includeHidden) }
            result.exceptionOrNull()?.let { reportDiagnosticError("repository.search", it, session.hostId, session.id, show = false) }
            runOnUiThread { callback(result) }
        }
    }

    private fun downloadFleetRepository(
        session: FleetSession,
        entry: FleetRepositoryEntry,
        callback: (FleetDownloadState) -> Unit
    ): FleetDownloadCancellation {
        val cancellation = FleetDownloadCancellation()
        fileDownloads += cancellation
        fileExecutor.execute {
            val result = runCatching {
                fleetRuntime.downloadRepositoryFile(session, entry, cancellation) { progress ->
                    runOnUiThread { callback(progress) }
                }
            }
            result.exceptionOrNull()?.let { reportDiagnosticError("repository.download", it, session.hostId, session.id, show = false) }
            runOnUiThread {
                callback(result.getOrElse { error ->
                    FleetDownloadState(
                        entry.name, entry.relativePath,
                        if (cancellation.isCancelledForUi()) "cancelled" else "failed",
                        0, entry.size ?: 0, message = error.message ?: "Download failed"
                    )
                })
            }
            fileDownloads -= cancellation
        }
        return cancellation
    }

    private fun openFleetDownload(state: FleetDownloadState) {
        val path = state.path ?: return
        try {
            val parsed = Uri.parse(path)
            val file = if (parsed.scheme.isNullOrBlank()) File(path) else null
            val uri = file?.let { FileProvider.getUriForFile(this, packageName + ".agentfleet.images", it) } ?: parsed
            val extension = state.name.substringAfterLast('.', "").lowercase(Locale.US)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (error: Exception) {
            reportDiagnosticError("repository.open_download", error)
            Toast.makeText(this, error.message ?: "No app can open this file", Toast.LENGTH_LONG).show()
        }
    }

    private fun scheduleContinue(session: FleetSession, delayMs: Long) = mutateFleet("Continue scheduled") { snapshot ->
        fleetRuntime.scheduleContinue(snapshot, session, System.currentTimeMillis() + delayMs)
    }

    private fun scheduleAttention(session: FleetSession, attention: FleetAttention, deliverAtEpochMs: Long) =
        mutateFleet("Continue scheduled") { snapshot ->
            fleetRuntime.scheduleContinue(snapshot, session, deliverAtEpochMs, attention.id)
        }

    private fun dismissAttention(attention: FleetAttention) = mutateFleet("Limit dismissed") { snapshot ->
        fleetRuntime.dismissAttention(snapshot, attention)
    }

    private fun killFleetSession(session: FleetSession) = mutateFleet(
        "Session stopped",
        afterSuccess = {
            workspaceTerminalBroker.detach(session.id)
            recentSessionStore.remove(session.id)
            drawerSessionStore.remove(session.id)
            recentSessions.value = recentSessionStore.load()
        }
    ) { snapshot -> fleetRuntime.killSession(snapshot, session) }

    private fun cancelSchedule(schedule: FleetSchedule) = mutateFleet("Schedule cancelled") { snapshot ->
        fleetRuntime.cancelSchedule(snapshot, schedule)
    }

    private fun copyAttachCommand(session: FleetSession) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("wtmux attach command", fleetRuntime.attachCommand(session)))
        Toast.makeText(this, "Attach command copied", Toast.LENGTH_SHORT).show()
    }

    private fun openPairing(invitation: String) {
        try {
            fleetRuntime.openPairing(invitation.trim())
        } catch (error: Exception) {
            reportDiagnosticError("pairing.open", error)
            Toast.makeText(this, error.message ?: "Pairing could not start", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshUpdatePolicy() {
        val paired = runCatching { clientPolicyStore.load()?.apkManifestUrls.orEmpty() }.getOrDefault(emptyList())
        val legacy = getSharedPreferences("agent-fleet-updates", Context.MODE_PRIVATE)
            .getString("manifest-url", "").orEmpty().takeIf { it.isNotBlank() }
        updateManifestUrl.value = paired.firstOrNull() ?: legacy.orEmpty()
    }

    private fun apkUpdateSources(): List<String> {
        val paired = runCatching { clientPolicyStore.load()?.apkManifestUrls.orEmpty() }.getOrDefault(emptyList())
        if (paired.isNotEmpty()) return paired
        return listOfNotNull(getSharedPreferences("agent-fleet-updates", Context.MODE_PRIVATE)
            .getString("manifest-url", "").orEmpty().takeIf { it.isNotBlank() })
    }

    private fun checkForUpdate() {
        val sources = apkUpdateSources()
        if (sources.isEmpty()) {
            updateState.value = UpdateUiState.Error("Pair this phone to receive its private update source")
            return
        }
        updateState.value = UpdateUiState.Checking
        updateExecutor.execute {
            val result = runCatching {
                var failure: Exception? = null
                for (url in sources) {
                    try {
                        val origins = runCatching { clientPolicyStore.load()?.artifactOrigins.orEmpty() }.getOrDefault(emptySet())
                        return@runCatching updateManager.check(url, origins)
                    } catch (error: Exception) {
                        failure = error
                    }
                }
                throw failure ?: IllegalStateException("No app update source was reachable")
            }
            runOnUiThread {
                updateState.value = result.fold(
                    onSuccess = { update ->
                        if (update == null) UpdateUiState.Current(updateManager.installedVersionName())
                        else UpdateUiState.Available(update)
                    },
                    onFailure = {
                        reportDiagnosticError("app_update.check", it, show = false)
                        UpdateUiState.Error(it.message ?: "Update check failed")
                    }
                )
            }
        }
    }

    private fun prepareEmbeddedRuntime(autoRepair: Boolean, blocking: Boolean) {
        if (isFinishing || isDestroyed || runtimeExecutor.isShutdown) return
        runtimeUi.value = runtimeUi.value.copy(
            busy = true, blocking = blocking, error = "",
            detail = if (autoRepair) "Preparing the built-in terminal…" else "Checking the built-in terminal…"
        )
        runtimeExecutor.executeLifecycleTask {
            val result = runCatching {
                val inspected = embeddedRuntime.inspect()
                val preserveCurrent = runtimeUpdateManager.shouldPreserveCurrentRuntime(inspected)
                val prepared = if (shouldInstallEmbeddedBaseline(inspected, autoRepair)) embeddedRuntime.repair(
                    preserveCurrent = preserveCurrent
                ) { detail ->
                    runOnUiThread { runtimeUi.value = runtimeUi.value.copy(detail = detail) }
                } else inspected
                runtimeUpdateManager.reconcileRuntimeFloor(prepared)
            }
            runOnUiThread {
                result.onSuccess { status ->
                    runtimeUi.value = RuntimeUiState(
                        status = status, detail = status.detail, blocking = false,
                        compatibilityDetail = runtimeCompatibilityDetail()
                    )
                    refreshFleet()
                    if (status.usable && runtimeUpdateManager.shouldCheck()) checkRuntimeUpdate(manual = false)
                }.onFailure { error ->
                    reportDiagnosticError("runtime.prepare", error, show = false)
                    runtimeUi.value = RuntimeUiState(
                        busy = false, blocking = blocking,
                        detail = if (blocking) "Terminal preparation stopped" else "Built-in runtime needs attention",
                        error = error.message ?: "Terminal preparation failed"
                    )
                }
            }
        }
    }

    private fun checkRuntimeUpdate(manual: Boolean) {
        if (runtimeUi.value.busy) return
        runtimeUi.value = runtimeUi.value.copy(busy = true, error = "", updateDetail = "Checking for runtime fixes…")
        updateExecutor.execute {
            val result = runCatching { runtimeUpdateManager.checkAndInstall(manual) }
            runOnUiThread {
                result.onSuccess { update ->
                    val detail = when (update) {
                        RuntimeUpdateResult.NoPolicy -> "Pair this phone to receive runtime updates"
                        is RuntimeUpdateResult.Current -> "Runtime is current"
                        is RuntimeUpdateResult.Installed -> "Updated to ${update.update.version}"
                    }
                    runtimeUi.value = runtimeUi.value.copy(busy = false, updateDetail = detail)
                    prepareEmbeddedRuntime(autoRepair = false, blocking = false)
                }.onFailure { error ->
                    reportDiagnosticError("runtime.update", error, show = false)
                    runtimeUi.value = runtimeUi.value.copy(
                        busy = false, updateDetail = "Runtime update failed",
                        error = error.message ?: "Runtime update failed"
                    )
                }
            }
        }
    }

    private fun rollbackRuntime() = runRuntimeMaintenance("Rolling back runtime…") { embeddedRuntime.rollback() }

    private fun restoreBaseline() = runRuntimeMaintenance("Restoring APK baseline…") { embeddedRuntime.restoreBaseline() }

    private fun runRuntimeMaintenance(detail: String, action: () -> EmbeddedRuntimeStatus) {
        if (runtimeUi.value.busy) return
        runtimeUi.value = runtimeUi.value.copy(busy = true, error = "", detail = detail)
        runtimeExecutor.execute {
            val result = runCatching(action)
            runOnUiThread {
                result.onSuccess { status -> runtimeUi.value = RuntimeUiState(
                    status = status, detail = status.detail,
                    compatibilityDetail = runtimeCompatibilityDetail()
                ) }
                    .onFailure { error -> runtimeUi.value = runtimeUi.value.copy(
                        busy = false, error = error.message ?: "Runtime recovery failed"
                    ) }
            }
        }
    }

    private fun runtimeCompatibilityDetail(): String {
        val descriptor = embeddedRuntime.descriptor()
        val components = descriptor.components
        val releaseSet = runtimeUpdateManager.healthyReleaseSetSequence()
        return buildString {
            append("Client ").append(components.getValue("clientRuntime").sequence)
            append(" · Host ").append(components.getValue("hostRuntime").sequence)
            append(" · Adapters ").append(components.getValue("providerAdapters").sequence)
            append(" · Contracts ").append(descriptor.contractPackageVersion)
            if (releaseSet > 0) append(" · Set ").append(releaseSet)
        }
    }

    private fun installUpdate(update: AgentFleetUpdate) {
        updateState.value = UpdateUiState.Downloading
        updateExecutor.execute {
            val origins = runCatching { clientPolicyStore.load()?.artifactOrigins.orEmpty() }.getOrDefault(emptySet())
            val result = runCatching { updateManager.downloadAndVerify(update, origins) }
            runOnUiThread {
                result.onSuccess { apk ->
                    updateState.value = UpdateUiState.Available(update)
                    startActivity(updateManager.installerIntent(apk))
                }.onFailure {
                    updateState.value = UpdateUiState.Error(it.message ?: "Update verification failed")
                }
            }
        }
    }

    private fun acceptPairingIntent(intent: Intent?) {
        val value = intent?.dataString.orEmpty()
        if (value.startsWith("wtmux://pair?") && value.length <= 4_096 && value.none { it.isISOControl() }) {
            pendingPairInvitation.value = value
        }
    }

    private fun acceptFocusedSession(intent: Intent?) {
        val value = intent?.getStringExtra(AgentFleetContract.EXTRA_FOCUS_SESSION_ID)
            ?.takeIf { it.length <= 320 && it.isNotBlank() && it.none(Char::isISOControl) }
        focusedSession.value = value
        if (value != null) focusedSessionSerial.value++
    }

    @Suppress("DEPRECATION")
    private fun acceptSharedImages(intent: Intent?) {
        if (intent?.type?.startsWith("image/") != true) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        val readable = uris.distinct().take(8).filter { uri ->
            runCatching { contentResolver.getType(uri)?.startsWith("image/") == true }.getOrDefault(false)
        }
        if (readable.isNotEmpty()) {
            pendingSharedImages.value = readable.map(Uri::toString)
        } else if (uris.isNotEmpty()) {
            Toast.makeText(this, "The shared image permission is unavailable", Toast.LENGTH_LONG).show()
        }
    }

    private fun mutateFleet(
        successMessage: String,
        afterSuccess: (FleetSnapshot) -> Unit = {},
        action: (FleetSnapshot) -> FleetSnapshot
    ) {
        val snapshot = (fleetState.value as? FleetLoadState.Ready)?.snapshot ?: return refreshFleet()
        fleetExecutor.execute {
            val result = runCatching { action(snapshot) }
            runOnUiThread {
                result.onSuccess {
                    FleetSnapshotStore.publish(it)
                    afterSuccess(it)
                    Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    val code = (it as? com.termux.app.fleet.FleetUnavailableException)?.code.orEmpty()
                    reportDiagnosticError("fleet.mutate", it, show = code !in setOf("host_offline", "stale_revision"))
                    Toast.makeText(this, it.message ?: "Fleet action failed", Toast.LENGTH_LONG).show()
                    refreshFleet()
                }
            }
        }
    }
}

data class RuntimeUiState(
    val status: EmbeddedRuntimeStatus? = null,
    val busy: Boolean = false,
    val blocking: Boolean = false,
    val detail: String = "Checking the built-in terminal…",
    val updateDetail: String = "",
    val compatibilityDetail: String = "",
    val error: String = ""
)

data class PairingReviewUiState(
    val requestId: String,
    val loading: Boolean = false,
    val review: FleetPairingReview? = null,
    val snapshotRevision: String = "",
    val error: String = ""
)

internal enum class PairingDecisionReadiness { Ready, Busy, NeedsReview }

internal fun pairingDecisionReadiness(
    state: PairingReviewUiState?,
    requestId: String,
    snapshotRevision: String
): PairingDecisionReadiness = when {
    state?.requestId != requestId -> PairingDecisionReadiness.NeedsReview
    state.loading -> PairingDecisionReadiness.Busy
    state.review == null || state.error.isNotBlank() || state.snapshotRevision != snapshotRevision ->
        PairingDecisionReadiness.NeedsReview
    else -> PairingDecisionReadiness.Ready
}

enum class FleetSection(val label: String, val glyph: String) {
    Sessions("Sessions", "▣"),
    Terminal("Terminal", ">_"),
    Limits("Limits", "%"),
    More("More", "•••")
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var amount = bytes.toDouble() / 1024.0
    var unit = units.first()
    for (candidate in units) {
        unit = candidate
        if (amount < 1024 || candidate == units.last()) break
        amount /= 1024.0
    }
    return String.format(Locale.US, if (amount >= 10) "%.0f %s" else "%.1f %s", amount, unit)
}

fun filterSessions(sessions: List<FleetSession>, hosts: Map<String, FleetHost>, query: String): List<FleetSession> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return sessions
    return sessions.filter {
        hosts[it.hostId]?.name?.contains(normalized, ignoreCase = true) == true ||
            it.project.contains(normalized, ignoreCase = true) ||
            it.name.contains(normalized, ignoreCase = true) ||
            it.title.contains(normalized, ignoreCase = true) ||
            it.tool.contains(normalized, ignoreCase = true)
    }
}

internal fun physicalHostIdForFleetAlert(snapshot: FleetSnapshot?, targetId: String): String? =
    snapshot?.physicalHosts?.firstOrNull { host ->
        host.id == targetId || targetId in host.legacyHostIds
    }?.id

private const val MORE_PAIRING_REVIEWS_INDEX = 4
internal const val MAX_QUEUED_FLEET_ALERT_ROUTES = 16

private val fleetAlertOverflowSummary = FleetAlert(
    category = null,
    title = "More fleet changes are waiting",
    body = "Existing unseen alerts were preserved. Review them, then inspect Sessions and More for current state.",
    target = FleetAlertTarget.Dashboard
)

internal fun enqueueFleetAlerts(existing: List<FleetAlert>, incoming: List<FleetAlert>): List<FleetAlert> {
    if (incoming.isEmpty()) return existing
    val existingRoutes = existing.filter { it.category != null }.take(MAX_QUEUED_FLEET_ALERT_ROUTES)
    val incomingRoutes = incoming.filter { it.category != null }
    val available = (MAX_QUEUED_FLEET_ALERT_ROUTES - existingRoutes.size).coerceAtLeast(0)
    val admitted = incomingRoutes.take(available)
    val overflowed = existing.any { it.category == null } ||
        incoming.any { it.category == null } ||
        admitted.size < incomingRoutes.size
    return existingRoutes + admitted + if (overflowed) listOf(fleetAlertOverflowSummary) else emptyList()
}

internal fun moreHostLazyListIndex(
    awaitingPairingRequests: Int,
    pendingSchedules: Int,
    physicalHostIndex: Int
): Int {
    require(awaitingPairingRequests >= 0 && pendingSchedules >= 0 && physicalHostIndex >= 0)
    return 11 + awaitingPairingRequests + pendingSchedules + physicalHostIndex
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentFleetApp(
    fleetState: FleetLoadState,
    recentSessions: List<FleetSession>,
    pendingPairInvitation: String?,
    onPairInvitationHandled: () -> Unit,
    pendingSharedImages: List<String>,
    updateState: UpdateUiState,
    updateManifestUrl: String,
    runtimeUi: RuntimeUiState,
    diagnosticsUi: DiagnosticsUiState,
    diagnosticError: AgentFleetDiagnosticEvent?,
    localModelUi: LocalModelUiState,
    onSharedImagesHandled: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSession: (FleetSession) -> Unit,
    onOpenSessionWithImages: (FleetSession, List<String>) -> Unit,
    onCreateSession: (String, String, String, String, String, String) -> Unit,
    onListDirectory: (String, String, String, (Result<FleetDirectoryListing>) -> Unit) -> Unit,
    onCreateDirectory: (String, String, String, String, (Result<String>) -> Unit) -> Unit,
    onListRepository: (FleetSession, String, Boolean, String, (Result<FleetRepositoryPage>) -> Unit) -> Unit,
    onSearchRepository: (FleetSession, String, Boolean, (Result<FleetRepositoryPage>) -> Unit) -> Unit,
    onDownloadRepository: (FleetSession, FleetRepositoryEntry, (FleetDownloadState) -> Unit) -> FleetDownloadCancellation,
    onCloseRepository: () -> Unit,
    onOpenDownload: (FleetDownloadState) -> Unit,
    onRenameSession: (FleetSession, String) -> Unit,
    onResetSessionName: (FleetSession) -> Unit,
    onScheduleContinue: (FleetSession, Long) -> Unit,
    onScheduleAttention: (FleetSession, FleetAttention, Long) -> Unit,
    onDismissAttention: (FleetAttention) -> Unit,
    onCancelSchedule: (FleetSchedule) -> Unit,
    onKillSession: (FleetSession) -> Unit,
    onCopyAttachCommand: (FleetSession) -> Unit,
    onPairInvitation: (String) -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: (AgentFleetUpdate) -> Unit,
    onRepairRuntime: () -> Unit,
    onCheckRuntime: () -> Unit,
    onRollbackRuntime: () -> Unit,
    onRestoreBaseline: () -> Unit,
    onOpenAppearance: () -> Unit,
    onMigrateFleetState: () -> Unit,
    onSetLocalSuggestions: (LocalSuggestionMode) -> Unit,
    onDownloadLocalModel: () -> Unit,
    onImportLocalModel: () -> Unit,
    onCancelLocalModel: () -> Unit,
    onRemoveLocalModel: () -> Unit,
    onRunDiagnostics: () -> Unit,
    onCopyDiagnostics: (AgentFleetDiagnosticReport) -> Unit,
    onExportDiagnostics: (AgentFleetDiagnosticReport) -> Unit,
    onCopyDiagnosticError: (AgentFleetDiagnosticEvent) -> Unit,
    onDiagnosticErrorHandled: () -> Unit,
    workspaceTerminalBroker: WorkspaceTerminalBroker? = null,
    initialActionSession: String? = null,
    initialActionSerial: Long = 0,
    fleetAlerts: List<FleetAlert> = emptyList(),
    fleetAlertSettings: FleetAlertSettings = FleetAlertSettings(),
    onDismissFleetAlert: () -> Unit = {},
    onFleetAlertSettings: (FleetAlertSettings) -> Unit = {},
    onPauseFleetAlerts: () -> Unit = {},
    onResumeFleetAlerts: () -> Unit = {},
    onReviewPairingRequest: (String) -> Unit = {},
    pairingReviewUi: PairingReviewUiState? = null,
    onDismissPairingReview: () -> Unit = {},
    onDecidePairingRequest: (String, Boolean) -> Unit = { _, _ -> }
) {
    if (runtimeUi.blocking) {
        PreparingTerminalScreen(runtimeUi, onRepairRuntime)
        return
    }
    var section by rememberSaveable { mutableStateOf(FleetSection.Sessions) }
    var actionSession by rememberSaveable { mutableStateOf<String?>(null) }
    var actionPane by rememberSaveable { mutableStateOf<String?>(null) }
    var renameSession by rememberSaveable { mutableStateOf<String?>(null) }
    var scheduleSession by rememberSaveable { mutableStateOf<String?>(null) }
    var killSession by rememberSaveable { mutableStateOf<String?>(null) }
    var repositorySession by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreateSession by rememberSaveable { mutableStateOf(false) }
    var showPairing by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var highlightedHostId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingHostScrollId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingHostScrollSerial by rememberSaveable { mutableStateOf<Long?>(null) }
    var highlightedPairingReviews by rememberSaveable { mutableStateOf(false) }
    var pendingPairingReviewsScrollSerial by rememberSaveable { mutableStateOf<Long?>(null) }
    var moreAlertRouteSerial by rememberSaveable { mutableStateOf(0L) }
    val currentSnapshot = (fleetState as? FleetLoadState.Ready)?.snapshot
    var handledInitialAction by rememberSaveable { mutableStateOf(-1L) }
    LaunchedEffect(initialActionSerial, currentSnapshot != null) {
        val requested = initialActionSession
        if (requested != null && handledInitialAction != initialActionSerial && currentSnapshot?.sessions?.any { it.id == requested } == true) {
            section = FleetSection.Sessions
            actionSession = requested
            actionPane = null
            handledInitialAction = initialActionSerial
        }
    }
    val context = LocalContext.current
    val workspaceStore = remember { AndroidWorkspaceStore(context.applicationContext) }
    val presentationStore = remember { WorkspacePresentationStore(context.applicationContext) }
    var workspaceState by remember { mutableStateOf(workspaceStore.load()) }
    var presentationMode by remember { mutableStateOf(presentationStore.load()) }
    LaunchedEffect(currentSnapshot?.revision) {
        currentSnapshot?.let { snapshot ->
            val reconciled = workspaceStore.reconcile(snapshot, workspaceState)
            if (reconciled != workspaceState) {
                workspaceState = reconciled
                workspaceStore.save(reconciled)
            }
        }
    }
    val visibleSessions = currentSnapshot?.sessions.orEmpty().filterNot { it.id in workspaceState.hiddenUnavailableSessionIds }
    val localAttachments = workspaceTerminalBroker?.attachedSessionIds?.value.orEmpty()
    val sessionsById = visibleSessions.associateBy { it.id }
    fun hideUnavailable(session: FleetSession) {
        if (currentSnapshot == null || isFleetSessionAvailable(currentSnapshot, session)) return
        workspaceState = workspaceState.copy(
            hiddenUnavailableSessionIds = (workspaceState.hiddenUnavailableSessionIds + session.id).take(64).toSet()
        )
        workspaceStore.save(workspaceState)
        actionSession = null
        actionPane = null
    }
    LaunchedEffect(pendingPairInvitation) {
        if (pendingPairInvitation != null) showPairing = true
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val desktop = isDesktopPresentation(presentationMode, maxWidth.value.toInt()) && workspaceTerminalBroker != null
        var wasDesktop by remember { mutableStateOf(desktop) }
        LaunchedEffect(desktop) {
            if (wasDesktop && !desktop && currentSnapshot != null) {
                val focused = com.termux.app.fleet.workspacePanes(workspaceState.layout.root)
                    .firstOrNull { it.id == workspaceState.layout.focusedPaneId }
                    ?.sessionId
                    ?.let { id -> currentSnapshot.sessions.firstOrNull { it.id == id } }
                if (focused != null && isFleetSessionAvailable(currentSnapshot, focused)) {
                    workspaceTerminalBroker?.openFullscreen(focused)
                }
            }
            wasDesktop = desktop
        }
        if (desktop) {
            Row(Modifier.fillMaxSize()) {
                DesktopNavigationRail(section) { section = it }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    when (section) {
                        FleetSection.Sessions, FleetSection.Terminal -> DesktopWorkspaceScreen(
                            snapshot = currentSnapshot,
                            sessions = visibleSessions,
                            phoneSessionId = recentSessions.firstOrNull()?.id,
                            state = workspaceState,
                            broker = workspaceTerminalBroker!!,
                            onStateChange = { updated -> workspaceState = updated; workspaceStore.save(updated) },
                            onMoreSession = { session, pane -> actionSession = session.id; actionPane = pane },
                            onRefresh = onRefresh
                        )
                        FleetSection.Limits -> LimitsScreen(PaddingValues(0.dp), fleetState, onScheduleAttention, onDismissAttention)
                        FleetSection.More -> MoreScreen(
                            PaddingValues(0.dp), fleetState, updateState, updateManifestUrl, runtimeUi, localModelUi,
                            { showPairing = true }, onCancelSchedule, onCheckUpdate, onInstallUpdate,
                            onRepairRuntime, onCheckRuntime, onRollbackRuntime, onRestoreBaseline,
                            onOpenAppearance, onMigrateFleetState, onSetLocalSuggestions, onDownloadLocalModel,
                            onImportLocalModel, onCancelLocalModel, onRemoveLocalModel,
                            { showDiagnostics = true }, presentationMode,
                            fleetAlertSettings, onFleetAlertSettings, onPauseFleetAlerts, onResumeFleetAlerts,
                            onReviewPairingRequest, highlightedHostId, pendingHostScrollId, pendingHostScrollSerial,
                            { consumed ->
                                if (pendingHostScrollSerial == consumed) {
                                    pendingHostScrollId = null
                                    pendingHostScrollSerial = null
                                }
                            },
                            highlightedPairingReviews, pendingPairingReviewsScrollSerial,
                            { consumed ->
                                if (pendingPairingReviewsScrollSerial == consumed) {
                                    pendingPairingReviewsScrollSerial = null
                                }
                            }
                        ) { mode -> presentationMode = mode; presentationStore.save(mode) }
                    }
                }
            }
        } else Scaffold(
        modifier = Modifier.testTag("agent-fleet-shell"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Agent Fleet", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Text(section.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                FleetSection.values().forEach { item ->
                    NavigationBarItem(
                        modifier = Modifier.testTag("nav-${item.label.lowercase(Locale.US)}"),
                        selected = section == item,
                        onClick = { section = item },
                        icon = { Text(item.glyph, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
                        label = { Text(item.label, fontSize = 12.sp) }
                    )
                }
            }
        }
    ) { padding ->
        when (section) {
            FleetSection.Sessions -> SessionsScreen(padding, fleetState, visibleSessions, localAttachments, onRefresh, onOpenSession, { actionSession = it.id; actionPane = null }, { showCreateSession = true }, { showPairing = true })
            FleetSection.Terminal -> TerminalScreen(padding, recentSessions, onOpenSession, onOpenAppearance)
            FleetSection.Limits -> LimitsScreen(padding, fleetState, onScheduleAttention, onDismissAttention)
            FleetSection.More -> MoreScreen(
                padding,
                fleetState,
                updateState,
                updateManifestUrl,
                runtimeUi,
                localModelUi,
                { showPairing = true },
                onCancelSchedule,
                onCheckUpdate,
                onInstallUpdate,
                onRepairRuntime,
                onCheckRuntime,
                onRollbackRuntime,
                onRestoreBaseline,
                onOpenAppearance,
                onMigrateFleetState,
                onSetLocalSuggestions,
                onDownloadLocalModel,
                onImportLocalModel,
                onCancelLocalModel,
                onRemoveLocalModel,
                { showDiagnostics = true },
                presentationMode,
                fleetAlertSettings,
                onFleetAlertSettings,
                onPauseFleetAlerts,
                onResumeFleetAlerts,
                onReviewPairingRequest,
                highlightedHostId,
                pendingHostScrollId,
                pendingHostScrollSerial,
                { consumed ->
                    if (pendingHostScrollSerial == consumed) {
                        pendingHostScrollId = null
                        pendingHostScrollSerial = null
                    }
                },
                highlightedPairingReviews,
                pendingPairingReviewsScrollSerial,
                { consumed ->
                    if (pendingPairingReviewsScrollSerial == consumed) {
                        pendingPairingReviewsScrollSerial = null
                    }
                }
            ) { mode -> presentationMode = mode; presentationStore.save(mode) }
        }
    }
        fleetAlerts.firstOrNull()?.let { alert ->
            FleetAlertBanner(
                alert = alert,
                queued = fleetAlerts.size,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(16.dp),
                onOpen = {
                    when (val target = alert.target) {
                        is FleetAlertTarget.Session -> {
                            val session = currentSnapshot?.sessions?.firstOrNull { it.id == target.id }
                            if (session == null || currentSnapshot?.let { isFleetSessionAvailable(it, session) } != true) {
                                section = FleetSection.Sessions
                                Toast.makeText(context, "That session is no longer available.", Toast.LENGTH_SHORT).show()
                            } else if (desktop) {
                                val updated = workspaceState.copy(
                                    layout = WorkspaceReducer.assign(
                                        workspaceState.layout,
                                        workspaceState.layout.focusedPaneId,
                                        session.id
                                    )
                                )
                                workspaceState = updated
                                workspaceStore.save(updated)
                                section = FleetSection.Sessions
                            } else {
                                onOpenSession(session)
                            }
                        }
                        is FleetAlertTarget.Host -> {
                            val physicalHostId = physicalHostIdForFleetAlert(currentSnapshot, target.id)
                            highlightedPairingReviews = false
                            pendingPairingReviewsScrollSerial = null
                            highlightedHostId = physicalHostId
                            pendingHostScrollId = physicalHostId
                            moreAlertRouteSerial += 1
                            pendingHostScrollSerial = physicalHostId?.let { moreAlertRouteSerial }
                            section = FleetSection.More
                            if (physicalHostId == null) {
                                Toast.makeText(context, "That host is no longer in the fleet.", Toast.LENGTH_SHORT).show()
                            }
                        }
                        is FleetAlertTarget.PairingReview -> onReviewPairingRequest(target.requestId)
                        FleetAlertTarget.Dashboard -> {
                            if (alert.category == FleetAlertCategory.Pairing) {
                                highlightedHostId = null
                                pendingHostScrollId = null
                                pendingHostScrollSerial = null
                                highlightedPairingReviews = true
                                moreAlertRouteSerial += 1
                                pendingPairingReviewsScrollSerial = moreAlertRouteSerial
                                section = FleetSection.More
                            } else {
                                section = FleetSection.Sessions
                            }
                        }
                    }
                    onDismissFleetAlert()
                },
                onDismiss = onDismissFleetAlert
            )
        }
    }

    sessionsById[actionSession]?.let { session ->
        val available = currentSnapshot?.let { isFleetSessionAvailable(it, session) } == true
        SessionActionsDialog(
            session = session,
            snapshot = currentSnapshot,
            available = available,
            onDismiss = { actionSession = null; actionPane = null },
            onOpen = { actionSession = null; actionPane = null; onOpenSession(session) },
            onRename = { actionSession = null; actionPane = null; renameSession = session.id },
            onResetName = { actionSession = null; actionPane = null; onResetSessionName(session) },
            onSchedule = { actionSession = null; actionPane = null; scheduleSession = session.id },
            onDownload = { actionSession = null; actionPane = null; repositorySession = session.id },
            onCopy = { actionSession = null; actionPane = null; onCopyAttachCommand(session) },
            onKill = { actionSession = null; actionPane = null; killSession = session.id },
            onHide = { hideUnavailable(session) },
            onDetach = actionPane?.takeIf { pane ->
                workspacePanes(workspaceState.layout.root).any { it.id == pane && it.sessionId == session.id }
            }?.let { pane ->
                {
                    workspaceTerminalBroker?.detach(session.id)
                    workspaceState = workspaceState.copy(layout = WorkspaceReducer.close(workspaceState.layout, pane))
                    workspaceStore.save(workspaceState)
                    actionSession = null
                    actionPane = null
                }
            }
        )
    }
    sessionsById[repositorySession]?.let { session ->
        RepositoryBrowserDialog(
            session = session,
            onDismiss = { repositorySession = null },
            onList = onListRepository,
            onSearch = onSearchRepository,
            onDownload = onDownloadRepository,
            onCloseBridge = onCloseRepository,
            onOpen = onOpenDownload
        )
    }
    sessionsById[renameSession]?.let { session ->
        RenameSessionDialog(session, { renameSession = null }) { name ->
            renameSession = null
            onRenameSession(session, name)
        }
    }
    sessionsById[scheduleSession]?.let { session ->
        ScheduleContinueDialog(session, { scheduleSession = null }) { delay ->
            scheduleSession = null
            onScheduleContinue(session, delay)
        }
    }
    sessionsById[killSession]?.let { session ->
        ConfirmKillDialog(session, { killSession = null }) {
            killSession = null
            onKillSession(session)
        }
    }
    if (showCreateSession && currentSnapshot != null) {
        CreateSessionDialog(
            snapshot = currentSnapshot,
            onListDirectory = onListDirectory,
            onCreateDirectory = onCreateDirectory,
            onDismiss = { showCreateSession = false }
        ) { host, project, backend, tool, path, locationKind ->
            showCreateSession = false
            onCreateSession(host, project, backend, tool, path, locationKind)
        }
    }
    if (showPairing) {
        PairingDialog(pendingPairInvitation.orEmpty(), {
            showPairing = false
            onPairInvitationHandled()
        }) { invitation ->
            showPairing = false
            onPairInvitationHandled()
            onPairInvitation(invitation)
        }
    }
    pairingReviewUi?.let { state ->
        PairingReviewDialog(
            state = state,
            onDismiss = onDismissPairingReview,
            onRetry = { onReviewPairingRequest(state.requestId) },
            onDecision = { approve -> onDecidePairingRequest(state.requestId, approve) }
        )
    }
    if (showDiagnostics) {
        DiagnosticsDialog(
            state = diagnosticsUi,
            onDismiss = { showDiagnostics = false },
            onRun = onRunDiagnostics,
            onCopy = onCopyDiagnostics,
            onExport = onExportDiagnostics
        )
    }
    diagnosticError?.let { event ->
        var showErrorDetails by rememberSaveable(event.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = onDiagnosticErrorHandled,
            title = { Text("Something needs attention") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Event ${event.id}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (showErrorDetails) {
                        Text(event.message.ifBlank { "The operation failed." })
                        Text(
                            "${event.operation} · ${event.code.ifBlank { "operation_failed" }}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onDiagnosticErrorHandled(); showDiagnostics = true }) { Text("Open Diagnostics") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showErrorDetails = !showErrorDetails }) { Text("Details") }
                    TextButton(onClick = { onCopyDiagnosticError(event) }) { Text("Copy") }
                    TextButton(onClick = onDiagnosticErrorHandled) { Text("Dismiss") }
                }
            }
        )
    }
    if (pendingSharedImages.isNotEmpty() && currentSnapshot != null) {
        SharedImagesSessionDialog(
            sessions = currentSnapshot.sessions.filter { it.tool in setOf("codex", "claude", "copilot") },
            imageCount = pendingSharedImages.size,
            onDismiss = onSharedImagesHandled
        ) { session ->
            val images = pendingSharedImages
            onSharedImagesHandled()
            onOpenSessionWithImages(session, images)
        }
    }
}

@Composable
private fun SessionsScreen(
    padding: PaddingValues,
    fleetState: FleetLoadState,
    sessions: List<FleetSession>,
    localAttachments: Set<String>,
    onRefresh: () -> Unit,
    onOpenSession: (FleetSession) -> Unit,
    onMoreSession: (FleetSession) -> Unit,
    onNewSession: () -> Unit,
    onPair: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val snapshot = (fleetState as? FleetLoadState.Ready)?.snapshot
    val hosts = snapshot?.hosts?.associateBy { it.id }.orEmpty()
    val physicalHosts = snapshot?.physicalHosts?.associateBy { it.id }.orEmpty()
    val filtered = filterSessions(sessions, hosts, query)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).testTag("sessions-screen"),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Your sessions", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (snapshot == null) "Connect to your fleet" else "${snapshot.sessions.size} sessions across ${snapshot.physicalHosts.size} hosts",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                }
                Button(onClick = onNewSession, enabled = snapshot?.let { !it.isStale && it.hosts.any { host -> host.status in setOf("online", "healthy") } } == true, shape = RoundedCornerShape(14.dp)) { Text("New", fontSize = 16.sp) }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                snapshot?.hosts?.forEach { host ->
                    val dotColor = when (host.status) {
                        "online", "healthy" -> Color(0xFF4CAF50)
                        "connecting" -> Color(0xFFFF9800)
                        else -> Color(0xFF757575)
                    }
                    val statusLabel = when (host.status) {
                        "online", "healthy" -> "online"
                        "connecting" -> "connecting\u2026"
                        else -> "offline"
                    }
                    Surface(modifier = Modifier.testTag("host-status-${host.id}"), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(dotColor))
                            Text(host.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("\u00b7 $statusLabel", fontSize = 10.sp, color = dotColor)
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().testTag("session-search"),
                label = { Text("Search sessions") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )
        }
        when (fleetState) {
            FleetLoadState.Loading -> item { EmptyState("Refreshing fleet…") }
            is FleetLoadState.Unavailable -> item {
                FleetUnavailableCard(fleetState, onRefresh, onPair)
            }
            is FleetLoadState.Ready -> {
                if (fleetState.snapshot.isStale) {
                    item {
                        Text(
                            "Showing cached sessions · Last refresh " + android.text.format.DateUtils.getRelativeTimeSpanString(fleetState.snapshot.receivedAtMillis),
                            modifier = Modifier.testTag("fleet-cache-status").padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
                        )
                    }
                }
                items(fleetState.snapshot.hosts.filter { it.errorCode.isNotBlank() }, key = { "failure-${it.id}" }) { host ->
                    val recovery = TransportContract.recoveryFor(host.errorCode)
                    Column(Modifier.fillMaxWidth().testTag("host-failure-${host.id}").padding(vertical = 4.dp)) {
                        Text("${host.name} · ${recovery?.title ?: "Host unavailable"}", fontSize = 12.sp)
                        Text(recovery?.action ?: "Open Diagnostics to review this host", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (recovery?.actionKind == "retry" || host.errorCode == "REGISTRY_INVALID") {
                            TextButton(onClick = onRefresh) {
                                Text(if (host.errorCode == "REGISTRY_INVALID") "Repair and retry" else "Retry")
                            }
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item { EmptyState(when {
                        query.isNotBlank() -> "No sessions match \u201c$query\u201d."
                        fleetState.snapshot.hosts.any { it.status !in setOf("online", "healthy") } -> "Session discovery is incomplete. Waiting for host contact."
                        else -> "No managed sessions are open."
                    }) }
                } else {
                    items(filtered, key = { it.id }) { session ->
                        val available = snapshot?.let { isFleetSessionAvailable(it, session) } == true
                        SessionCard(
                            session,
                            physicalHosts[session.physicalHostId]?.name ?: session.physicalHostId,
                            available,
                            session.id in localAttachments,
                            { onOpenSession(session) },
                            { onMoreSession(session) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: FleetSession,
    hostName: String,
    available: Boolean,
    locallyAttached: Boolean,
    onOpen: () -> Unit,
    onMore: () -> Unit
) {
    val identity = sessionIdentityPresentation(session)
    Card(
        modifier = Modifier.fillMaxWidth().testTag("session-${session.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (available && session.activity == "active") ReadyGreen else QuietGray)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(identity.primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (identity.secondary.isNotBlank()) Text(identity.secondary, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    if (!available) "Unavailable" else if (session.activity == "active") "Active" else "Idle",
                    color = if (available && session.activity == "active") ReadyGreen else QuietGray,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hostName,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    session.tool.replaceFirstChar { it.uppercase() },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                    maxLines = 1,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onOpen, enabled = available, modifier = Modifier.weight(1f).testTag("session-open-${session.id}"), shape = RoundedCornerShape(14.dp)) {
                    Text(if (locallyAttached) "Return" else "Enter", fontSize = 17.sp)
                }
                OutlinedButton(onClick = onMore, modifier = Modifier.testTag("session-more-${session.id}"), shape = RoundedCornerShape(14.dp)) { Text("More", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun SessionActionsDialog(
    session: FleetSession,
    snapshot: FleetSnapshot?,
    available: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onResetName: () -> Unit,
    onSchedule: () -> Unit,
    onDownload: () -> Unit,
    onCopy: () -> Unit,
    onKill: () -> Unit,
    onHide: () -> Unit,
    onDetach: (() -> Unit)? = null
) {
    val physicalHost = snapshot?.physicalHosts?.firstOrNull { it.id == session.physicalHostId }
    val target = snapshot?.executionTargets?.firstOrNull {
        it.physicalHostId == session.physicalHostId && it.id == session.executionTargetId
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(sessionIdentityPresentation(session).primary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Session details", fontWeight = FontWeight.SemiBold)
                Text(
                    "${physicalHost?.name ?: session.physicalHostId} · ${target?.label ?: session.executionTargetId} · ${session.tool}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(session.projectPath.ifBlank { "Path unavailable for this older session" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (session.title.isNotBlank()) Text("Automatic title: ${session.title}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!available) Text("This is a cached session. Its host is offline.", color = WarningAmber)
                DialogAction("Open terminal", onOpen, enabled = available)
                DialogAction("Rename", onRename, enabled = available)
                if (session.nameMode == "manual") DialogAction("Use automatic title", onResetName, enabled = available)
                DialogAction("Schedule Continue", onSchedule, enabled = available)
                DialogAction("Download a file", onDownload, enabled = available)
                DialogAction("Copy attach command", onCopy)
                if (onDetach != null) DialogAction("Detach from pane", onDetach)
                if (available) DialogAction("Stop session…", onKill, WarningAmber)
                else DialogAction("Remove from this device", onHide, WarningAmber)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun DialogAction(label: String, onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.primary, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.fillMaxWidth(), color = if (enabled) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f), fontSize = 17.sp)
    }
}

@Composable
private fun RepositoryBrowserDialog(
    session: FleetSession,
    onDismiss: () -> Unit,
    onList: (FleetSession, String, Boolean, String, (Result<FleetRepositoryPage>) -> Unit) -> Unit,
    onSearch: (FleetSession, String, Boolean, (Result<FleetRepositoryPage>) -> Unit) -> Unit,
    onDownload: (FleetSession, FleetRepositoryEntry, (FleetDownloadState) -> Unit) -> FleetDownloadCancellation,
    onCloseBridge: () -> Unit,
    onOpen: (FleetDownloadState) -> Unit
) {
    var page by remember(session.id) { mutableStateOf<FleetRepositoryPage?>(null) }
    var loading by remember(session.id) { mutableStateOf(true) }
    var error by remember(session.id) { mutableStateOf("") }
    var showHidden by rememberSaveable(session.id) { mutableStateOf(false) }
    var query by rememberSaveable(session.id) { mutableStateOf("") }
    var searching by rememberSaveable(session.id) { mutableStateOf(false) }
    var pending by remember(session.id) { mutableStateOf<FleetRepositoryEntry?>(null) }
    var download by remember(session.id) { mutableStateOf<FleetDownloadState?>(null) }
    var cancellation by remember(session.id) { mutableStateOf<FleetDownloadCancellation?>(null) }
    var retryAction by remember(session.id) { mutableStateOf<(() -> Unit)?>(null) }

    DisposableEffect(session.id) {
        onDispose { onCloseBridge() }
    }

    fun load(path: String, cursor: String = "", append: Boolean = false) {
        if (loading && page != null) return
        loading = true
        error = ""
        onList(session, path, showHidden, cursor) { result ->
            loading = false
            result.onSuccess { next ->
                page = if (append && page?.relativePath == next.relativePath) {
                    next.copy(entries = page!!.entries + next.entries)
                } else next
                searching = false
                if (!append) query = ""
                retryAction = null
            }.onFailure {
                error = it.message ?: "Repository could not be loaded"
                retryAction = if (isRetryableRepositoryFailure(it)) ({ load(path, cursor, append) }) else null
            }
        }
    }

    fun search(value: String) {
        val clean = value.trim()
        if (clean.length < 2) {
            error = "Search needs at least two characters"
            retryAction = null
            return
        }
        loading = true
        error = ""
        onSearch(session, clean, showHidden) { result ->
            loading = false
            result.onSuccess {
                page = it
                searching = true
                retryAction = null
            }.onFailure {
                error = it.message ?: "Search failed"
                retryAction = if (isRetryableRepositoryFailure(it)) ({ search(clean) }) else null
            }
        }
    }

    LaunchedEffect(session.id, showHidden) { load(page?.relativePath ?: "") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp).testTag("repository-dialog"),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 8.dp
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Download from repository", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Text(sessionIdentityPresentation(session).primary, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text(
                            page?.relativePath?.takeIf { it.isNotBlank() } ?: page?.rootName ?: session.project,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().testTag("repository-search-input"),
                    label = { Text("Search files") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { load(page?.parentPath ?: "") },
                        enabled = page?.relativePath?.isNotBlank() == true && !loading
                    ) { Text("Up") }
                    Button(onClick = { search(query) }, enabled = !loading, modifier = Modifier.testTag("repository-search")) { Text("Search") }
                    Spacer(Modifier.weight(1f))
                    if (loading) Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showHidden = !showHidden }, enabled = !loading) {
                        Text(if (showHidden) "Hide hidden" else "Show hidden")
                    }
                    if (searching) TextButton(onClick = { load("") }, enabled = !loading) { Text("Clear search") }
                }
                if (error.isNotBlank()) {
                    Card(modifier = Modifier.testTag("repository-error"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                error,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 15.sp
                            )
                            retryAction?.let { retry ->
                                TextButton(onClick = retry, enabled = !loading, modifier = Modifier.testTag("repository-retry")) { Text("Retry") }
                            }
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 180.dp, max = 390.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val entries = page?.entries.orEmpty()
                    if (shouldShowRepositoryEmpty(loading, error, entries.size)) {
                        item { EmptyState(if (searching) "No matching files." else "This folder is empty.") }
                    }
                    items(entries, key = { it.relativePath }) { entry ->
                        OutlinedButton(
                            onClick = {
                                if (entry.kind == "directory") load(entry.relativePath)
                                else if ((entry.size ?: 0) > 50L * 1024 * 1024) pending = entry
                                else cancellation = onDownload(session, entry) { download = it }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("repository-entry-${entry.relativePath}"),
                            enabled = download?.status != "running"
                        ) {
                            Text(if (entry.kind == "directory") "▰" else "▤", fontSize = 18.sp)
                            Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.name, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                                Text(
                                    if (entry.kind == "directory") "Folder" else formatFileSize(entry.size ?: 0),
                                    modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
                                )
                            }
                            Text("›", fontSize = 22.sp)
                        }
                    }
                    page?.nextCursor?.let { cursor ->
                        item {
                            TextButton(onClick = { load(page?.relativePath ?: "", cursor, true) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                                Text("Load more")
                            }
                        }
                    }
                }
                pending?.let { entry ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Download ${entry.name}?", fontWeight = FontWeight.Bold)
                            Text("${formatFileSize(entry.size ?: 0)} is a large transfer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { pending = null }) { Text("Cancel") }
                                Button(onClick = {
                                    pending = null
                                    cancellation = onDownload(session, entry) { download = it }
                                }) { Text("Download") }
                            }
                        }
                    }
                }
                download?.let { state ->
                    Card(modifier = Modifier.testTag("repository-download-state"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(state.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(state.message, color = if (state.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.status == "running") {
                                LinearProgressIndicator(
                                    progress = {
                                        if (state.total == 0L) 0f
                                        else state.received.toFloat() / state.total.toFloat()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                TextButton(onClick = { cancellation?.cancel() }) { Text("Cancel download") }
                            } else if (state.status == "completed") {
                                Button(onClick = { onOpen(state) }) { Text("Open file") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameSessionDialog(session: FleetSession, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable(session.id) { mutableStateOf(session.name) }
    val valid = name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._ -]{0,63}"))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = valid) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ScheduleContinueDialog(session: FleetSession, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule Continue") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Choose when ${sessionIdentityPresentation(session).primary} should receive one guarded Continue message.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                DialogAction("In 15 minutes", { onConfirm(15 * 60 * 1_000L) })
                DialogAction("In 1 hour", { onConfirm(60 * 60 * 1_000L) })
                DialogAction("In 5 hours", { onConfirm(5 * 60 * 60 * 1_000L) })
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ConfirmKillDialog(session: FleetSession, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop ${session.name}?") },
        text = { Text("This closes the remote tmux session and cancels its pending scheduled messages.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Stop session", color = WarningAmber) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun CreateSessionDialog(
    snapshot: FleetSnapshot,
    onListDirectory: (String, String, String, (Result<FleetDirectoryListing>) -> Unit) -> Unit,
    onCreateDirectory: (String, String, String, String, (Result<String>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String, String, String) -> Unit
) {
    val hosts = snapshot.physicalHosts.filter { it.status == "healthy" }
    var hostId by rememberSaveable {
        mutableStateOf(hosts.firstOrNull()?.id ?: snapshot.physicalHosts.firstOrNull()?.id.orEmpty())
    }
    var backend by rememberSaveable {
        mutableStateOf(snapshot.executionTargets.firstOrNull {
            it.physicalHostId == hostId && it.status != "unavailable"
        }?.id ?: "linux")
    }
    var locationKind by rememberSaveable { mutableStateOf("project") }
    var tool by rememberSaveable { mutableStateOf("codex") }
    var selectedPath by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var newFolder by rememberSaveable { mutableStateOf("") }
    var listing by remember { mutableStateOf<FleetDirectoryListing?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var recentVersion by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val recentStore = remember(context) { RecentLocationStore(context) }
    val recents = remember(hostId, backend, recentVersion) { recentStore.load(hostId, backend) }
    val targets = snapshot.executionTargets.filter {
        it.physicalHostId == hostId && it.status != "unavailable"
    }

    fun browse(path: String, preferProjects: Boolean = false, recent: Boolean = false) {
        val transportHost = transportHostId(snapshot, hostId, backend)
        if (transportHost == null) {
            loading = false
            error = "The selected execution target is unavailable."
            return
        }
        loading = true
        error = ""
        onListDirectory(transportHost, backend, path) { first ->
            first.onSuccess { value ->
                if (preferProjects) {
                    val projects = value.shortcuts.firstOrNull { it.id == "projects" }
                    if (projects != null && projects.path != value.path) {
                        browse(projects.path)
                        return@onSuccess
                    }
                }
                listing = value
                loading = false
            }.onFailure { failure ->
                loading = false
                error = failure.message ?: "Folder could not be loaded."
                if (recent && path.isNotBlank()) {
                    recentStore.remove(hostId, backend, path)
                    recentVersion++
                }
            }
        }
    }

    LaunchedEffect(hostId, targets.map { it.id }) {
        if (targets.none { it.id == backend }) backend = targets.firstOrNull()?.id ?: "linux"
    }
    LaunchedEffect(hostId, backend, locationKind) {
        listing = null
        selectedPath = ""
        label = ""
        browse("", locationKind == "project")
    }
    val valid = transportHostId(snapshot, hostId, backend) != null && selectedPath.isNotBlank() &&
        label.matches(Regex("[A-Za-z0-9][A-Za-z0-9._ -]{0,63}"))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New session") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Host", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    hosts.forEach { host ->
                        AssistChip(onClick = {
                            hostId = host.id
                            val hostTargets = snapshot.executionTargets.filter {
                                it.physicalHostId == host.id && it.status != "unavailable"
                            }
                            if (hostTargets.none { it.id == backend }) backend = hostTargets.firstOrNull()?.id ?: "linux"
                        }, label = { Text(if (host.id == hostId) "✓ ${host.name}" else host.name) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    targets.forEach { target ->
                        AssistChip(
                            onClick = { backend = target.id },
                            label = { Text(if (backend == target.id) "✓ ${target.label}" else target.label) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("project" to "Projects", "custom" to "Other location").forEach { (choice, title) ->
                        AssistChip(onClick = { locationKind = choice }, label = { Text(if (locationKind == choice) "✓ $title" else title) })
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { browse("", locationKind == "project") }) { Text("Retry") }
                }
                listing?.let { directory ->
                    Text(directory.path, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        directory.shortcuts.forEach { shortcut -> AssistChip(onClick = { browse(shortcut.path) }, label = { Text(shortcut.label) }) }
                    }
                    if (locationKind == "custom" && recents.isNotEmpty()) {
                        Text("Recent", fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            recents.forEach { path -> AssistChip(onClick = { browse(path, recent = true) }, label = { Text(shortLocation(path)) }) }
                            AssistChip(onClick = { recentStore.clear(hostId, backend); recentVersion++ }, label = { Text("Clear") })
                        }
                    }
                    Column(Modifier.fillMaxWidth().heightIn(max = 230.dp).verticalScroll(rememberScrollState())) {
                        directory.parentPath?.let { parent -> TextButton(onClick = { browse(parent) }) { Text("↑ Parent folder") } }
                        directory.entries.forEach { entry ->
                            TextButton(onClick = { browse(entry.path) }, modifier = Modifier.fillMaxWidth()) {
                                Text("📁 ${entry.name}", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                                Text("›")
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(newFolder, { newFolder = it }, Modifier.weight(1f), label = { Text("New folder") }, singleLine = true)
                        OutlinedButton(onClick = {
                            val parent = directory.path
                            val transportHost = transportHostId(snapshot, hostId, backend)
                                ?: return@OutlinedButton
                            onCreateDirectory(transportHost, backend, parent, newFolder.trim()) { result ->
                                result.onSuccess { path -> newFolder = ""; browse(path) }
                                    .onFailure { error = it.message ?: "Folder could not be created." }
                            }
                        }, enabled = newFolder.isNotBlank()) { Text("Create") }
                    }
                    Button(onClick = {
                        selectedPath = directory.path
                        label = shortLocation(directory.path).substringAfterLast('/').ifBlank { "Session" }
                    }, Modifier.fillMaxWidth()) { Text("Use this folder") }
                }
                if (selectedPath.isNotBlank()) {
                    OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), label = { Text("Session label") }, singleLine = true)
                    Text("Tool", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("codex", "claude", "copilot", "shell").forEach { choice ->
                            AssistChip(onClick = { tool = choice }, label = { Text(if (tool == choice) "✓ $choice" else choice) })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            recentStore.record(hostId, backend, selectedPath)
            val transportHost = transportHostId(snapshot, hostId, backend) ?: return@TextButton
            onConfirm(transportHost, label.trim(), backend, tool, selectedPath, locationKind)
        }, enabled = valid) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun shortLocation(path: String): String {
    val normalized = path.replace('\\', '/').trimEnd('/')
    val parts = normalized.split('/').filter { it.isNotBlank() }
    return parts.takeLast(2).joinToString("/").ifBlank { path }
}

@Composable
private fun PairingDialog(initialInvitation: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var invitation by rememberSaveable(initialInvitation) { mutableStateOf(initialInvitation) }
    val review = remember(invitation) {
        runCatching { com.termux.app.fleet.FleetConfigurationParser.reviewInvitation(invitation.trim()) }.getOrNull()
    }
    val valid = review != null && !review.expired
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair or restore") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Paste the invitation created by your Agent Fleet controller.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = invitation,
                    onValueChange = { invitation = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("wtmux invitation") },
                    minLines = 3,
                    maxLines = 5
                )
                if (review != null) {
                    Text(
                        "Controller ${review.bootstrapPeer} · expires ${review.expiresAt}",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (review.expired) "This invitation expired. Create a new invitation."
                        else "Review this controller, then request access. The controller must still approve this phone.",
                        fontSize = 14.sp,
                        color = if (review.expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (invitation.isNotBlank()) {
                    Text("This invitation is invalid.", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(invitation.trim()) }, enabled = valid) { Text("Request pairing") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PairingReviewDialog(
    state: PairingReviewUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onDecision: (Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.loading) onDismiss() },
        title = { Text("Review exact pairing proposal") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when {
                    state.loading -> Text("Verifying the live peer and proposal…")
                    state.error.isNotBlank() -> {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(
                            onClick = onRetry,
                            modifier = Modifier.testTag("pairing-review-retry")
                        ) { Text("Review again") }
                    }
                    state.review != null -> {
                        Text(
                            "${state.review.deviceName} · ${state.review.platform}",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Verified live peer: ${state.review.peer} (${state.review.peerIp})",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            state.review.proposalJson,
                            modifier = Modifier.testTag("pairing-review-proposal"),
                            fontSize = 13.sp
                        )
                        Text(
                            "Approve only if every identity, role, path, transport, and command is expected.",
                            color = WarningAmber,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onDecision(true) },
                enabled = !state.loading && state.error.isBlank() && state.review != null,
                modifier = Modifier.testTag("pairing-review-approve")
            ) { Text("Approve") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onDecision(false) },
                    enabled = !state.loading && state.error.isBlank() && state.review != null,
                    modifier = Modifier.testTag("pairing-review-reject")
                ) { Text("Reject") }
                TextButton(
                    onClick = onDismiss,
                    enabled = !state.loading,
                    modifier = Modifier.testTag("pairing-review-cancel")
                ) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun SharedImagesSessionDialog(
    sessions: List<FleetSession>,
    imageCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (FleetSession) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send ${if (imageCount == 1) "image" else "$imageCount images"} to…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (sessions.isEmpty()) Text("No open AI sessions are available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                sessions.take(8).forEach { session ->
                    val identity = sessionIdentityPresentation(session)
                    DialogAction("${identity.primary} · ${identity.stableName} · ${session.tool}", { onConfirm(session) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun FleetUnavailableCard(state: FleetLoadState.Unavailable, onRefresh: () -> Unit, onPair: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(TransportContract.recoveryFor(state.code)?.title ?: "Fleet is not connected", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(state.reason, fontSize = 16.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.code.isBlank()) Button(onClick = onPair, shape = RoundedCornerShape(14.dp)) { Text("Pair", fontSize = 16.sp) }
                OutlinedButton(onClick = onRefresh, enabled = !state.recovering, shape = RoundedCornerShape(14.dp)) {
                    Text(if (state.code == "REGISTRY_INVALID") "Repair and retry" else "Retry", fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun TerminalScreen(
    padding: PaddingValues,
    recentSessions: List<FleetSession>,
    onOpenSession: (FleetSession) -> Unit,
    onOpenAppearance: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).testTag("terminal-screen"),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Terminal", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Recent fleet terminal sessions", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = onOpenAppearance,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Appearance", fontSize = 16.sp) }
            }
        }
        if (recentSessions.isEmpty()) {
            item { EmptyState("Your opened fleet sessions will appear here.") }
        } else {
            items(recentSessions, key = { it.id }) { session ->
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val identity = sessionIdentityPresentation(session)
                            Text(identity.primary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text(
                                identity.secondary,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Button(onClick = { onOpenSession(session) }, shape = RoundedCornerShape(14.dp)) { Text("Open", fontSize = 16.sp) }
                    }
                }
            }
        }
        item {
            Text("Shell tabs use direct terminal input. AI tabs will use the multiline mobile composer.", fontSize = 15.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LimitsScreen(
    padding: PaddingValues,
    fleetState: FleetLoadState,
    onScheduleAttention: (FleetSession, FleetAttention, Long) -> Unit,
    onDismissAttention: (FleetAttention) -> Unit
) {
    val context = LocalContext.current
    val snapshot = (fleetState as? FleetLoadState.Ready)?.snapshot
    val sessions = snapshot?.sessions?.associateBy { it.id }.orEmpty()
    val activeAttention = snapshot?.attention?.filter { it.state in setOf("detected", "offering", "offered") }.orEmpty()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).testTag("limits-screen"),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("AI limits", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                snapshot?.let { "Trusted host sources · ${formatAge(it.generatedAt)}" } ?: "Waiting for trusted host sources",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when {
            fleetState is FleetLoadState.Loading -> item { EmptyState("Refreshing limits…") }
            fleetState is FleetLoadState.Unavailable -> item { EmptyState(fleetState.reason) }
            activeAttention.isEmpty() -> item {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("No active hard limits", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ReadyGreen)
                        Text("Detected Codex and Claude limit events will appear here with a one-tap guarded Continue action.", fontSize = 16.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            else -> items(activeAttention, key = { it.id }) { attention ->
                val session = sessions[attention.sessionId]
                Card(colors = CardDefaults.cardColors(containerColor = WarningAmber.copy(alpha = 0.13f)), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${attention.agent.replaceFirstChar { it.uppercase() }} reached a limit", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            listOfNotNull(snapshot!!.hosts.firstOrNull { it.id == attention.hostId }?.name, attention.resetAt?.let { "available $it" }).joinToString(" · "),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (session != null) {
                            val deliverAt = defaultLimitScheduleTime(attention.resetAt, System.currentTimeMillis())
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onScheduleAttention(session, attention, deliverAt) }, shape = RoundedCornerShape(14.dp)) {
                                    Text("Schedule Continue", fontSize = 16.sp)
                                }
                                OutlinedButton(onClick = {
                                    showLimitDateTimePicker(context, deliverAt) {
                                        onScheduleAttention(session, attention, it)
                                    }
                                }) { Text("Change time") }
                                TextButton(onClick = { onDismissAttention(attention) }) { Text("Dismiss") }
                            }
                        }
                    }
                }
            }
        }
        if (snapshot != null) {
            item { Text("Profiles", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            if (snapshot.limits.isEmpty()) {
                item { FeatureCard("No quota profiles reported", "Choose designated desktop sources and update their wtmux runtime to publish quota windows.") }
            } else {
                items(snapshot.limits, key = { it.id }) { limit -> QuotaProfileCard(limit, snapshot.hosts.firstOrNull { it.id == limit.hostId }?.name ?: limit.hostId) }
            }
        }
    }
}

@Composable
private fun QuotaProfileCard(limit: FleetLimit, hostName: String) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(limit.profileAlias, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(hostName, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (limit.status == "limited") "Limited" else "Ready", color = if (limit.status == "limited") WarningAmber else ReadyGreen, fontWeight = FontWeight.Bold)
            }
            limit.primary?.let { QuotaWindowRow(it) }
            limit.secondary?.let { QuotaWindowRow(it) }
            Text(formatAge(limit.updatedAt), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuotaWindowRow(window: FleetLimitWindow) {
    val label = when (window.windowMinutes) {
        300 -> "5h"
        10080 -> "Weekly"
        else -> "${window.windowMinutes / 60}h"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.size(width = 64.dp, height = 24.dp), fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(
            progress = { (window.remainingPercent / 100.0).toFloat() },
            modifier = Modifier.weight(1f).height(9.dp),
            color = if (window.remainingPercent <= 0) WarningAmber else ReadyGreen,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Text(quotaRemainingLabel(window.remainingPercent), modifier = Modifier.padding(start = 10.dp), fontWeight = FontWeight.Bold)
    }
}

internal fun quotaRemainingLabel(remainingPercent: Double): String =
    "${remainingPercent.toInt()}% left"

private fun resetDelayMs(resetAt: String): Long {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    return runCatching { (parser.parse(resetAt)?.time ?: 0L) + 60_000L - System.currentTimeMillis() }.getOrDefault(0L).coerceAtLeast(0L)
}

private fun formatAge(timestamp: String): String {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val seconds = runCatching { (System.currentTimeMillis() - (parser.parse(timestamp)?.time ?: 0L)).coerceAtLeast(0L) / 1_000L }.getOrDefault(0L)
    return when {
        seconds < 60 -> "updated now"
        seconds < 3_600 -> "updated ${seconds / 60}m ago"
        seconds < 86_400 -> "updated ${seconds / 3_600}h ago"
        else -> "updated ${seconds / 86_400}d ago"
    }
}

@Composable
private fun FleetAlertBanner(
    alert: FleetAlert,
    queued: Int,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("fleet-alert-banner"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(alert.title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(alert.body, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    if (queued > 1) {
                        Text(
                            "${queued - 1} more alert${if (queued == 2) "" else "s"} queued",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("fleet-alert-dismiss")) { Text("Dismiss") }
                Button(onClick = onOpen, modifier = Modifier.testTag("fleet-alert-open")) { Text("Open") }
            }
        }
    }
}

@Composable
private fun FleetAlertSettingsCard(
    settings: FleetAlertSettings,
    onSettings: (FleetAlertSettings) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    val paused = settings.isPaused(System.currentTimeMillis())
    Card(
        modifier = Modifier.testTag("fleet-alert-settings"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fleet alerts", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Shown in this app only while Agent Fleet is in the foreground.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = if (paused) onResume else onPause,
                    modifier = Modifier.testTag(if (paused) "fleet-alert-resume" else "fleet-alert-pause")
                ) { Text(if (paused) "Resume" else "Pause 1 hour") }
            }
            FleetAlertSettingRow("Usage limits", FleetAlertCategory.HardLimits, settings, onSettings)
            FleetAlertSettingRow("Delivery failures", FleetAlertCategory.DeliveryFailures, settings, onSettings)
            FleetAlertSettingRow("Delivery success", FleetAlertCategory.DeliverySuccess, settings, onSettings)
            FleetAlertSettingRow("Host offline and recovery", FleetAlertCategory.HostState, settings, onSettings)
            FleetAlertSettingRow("Runtime version drift", FleetAlertCategory.VersionDrift, settings, onSettings)
            FleetAlertSettingRow("Pairing requests", FleetAlertCategory.Pairing, settings, onSettings)
        }
    }
}

@Composable
private fun FleetAlertSettingRow(
    label: String,
    category: FleetAlertCategory,
    settings: FleetAlertSettings,
    onSettings: (FleetAlertSettings) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 15.sp)
        Switch(
            checked = settings.isEnabled(category),
            onCheckedChange = { enabled ->
                onSettings(when (category) {
                    FleetAlertCategory.HardLimits -> settings.copy(hardLimits = enabled)
                    FleetAlertCategory.DeliveryFailures -> settings.copy(deliveryFailures = enabled)
                    FleetAlertCategory.DeliverySuccess -> settings.copy(deliverySuccess = enabled)
                    FleetAlertCategory.HostState -> settings.copy(hostState = enabled)
                    FleetAlertCategory.VersionDrift -> settings.copy(versionDrift = enabled)
                    FleetAlertCategory.Pairing -> settings.copy(pairing = enabled)
                })
            },
            modifier = Modifier
                .semantics { contentDescription = label }
                .testTag("fleet-alert-${category.canonicalId}")
        )
    }
}

@Composable
private fun AwaitingPairingReviewCard(
    request: FleetPairingRequest,
    onReviewPairingRequest: (String) -> Unit
) {
    Card(
        modifier = Modifier.testTag("pairing-request-${request.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(request.deviceName, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "${request.platform} · ${request.peer}",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Requested ${request.requestedAt} · expires ${request.expiresAt}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { onReviewPairingRequest(request.id) },
                modifier = Modifier
                    .align(Alignment.End)
                    .semantics { contentDescription = "Review exact proposal from ${request.deviceName}" }
                    .testTag("pairing-request-review-${request.id}"),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Review exact proposal") }
        }
    }
}

@Composable
private fun MoreScreen(
    padding: PaddingValues,
    fleetState: FleetLoadState,
    updateState: UpdateUiState,
    updateManifestUrl: String,
    runtimeUi: RuntimeUiState,
    localModelUi: LocalModelUiState,
    onPair: () -> Unit,
    onCancelSchedule: (FleetSchedule) -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: (AgentFleetUpdate) -> Unit,
    onRepairRuntime: () -> Unit,
    onCheckRuntime: () -> Unit,
    onRollbackRuntime: () -> Unit,
    onRestoreBaseline: () -> Unit,
    onOpenAppearance: () -> Unit,
    onMigrateFleetState: () -> Unit,
    onSetLocalSuggestions: (LocalSuggestionMode) -> Unit,
    onDownloadLocalModel: () -> Unit,
    onImportLocalModel: () -> Unit,
    onCancelLocalModel: () -> Unit,
    onRemoveLocalModel: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    presentationMode: WorkspacePresentationMode,
    fleetAlertSettings: FleetAlertSettings,
    onFleetAlertSettings: (FleetAlertSettings) -> Unit,
    onPauseFleetAlerts: () -> Unit,
    onResumeFleetAlerts: () -> Unit,
    onReviewPairingRequest: (String) -> Unit,
    highlightedHostId: String?,
    pendingHostScrollId: String?,
    pendingHostScrollSerial: Long?,
    onHostScrollConsumed: (Long) -> Unit,
    highlightedPairingReviews: Boolean,
    pendingPairingReviewsScrollSerial: Long?,
    onPairingReviewsScrollConsumed: (Long) -> Unit,
    onPresentationMode: (WorkspacePresentationMode) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var nativeSessionEnabled by rememberSaveable {
        mutableStateOf(NativeSessionSettings.isEnabled(context))
    }
    var automaticSessionTitles by rememberSaveable {
        mutableStateOf(AutomaticSessionTitleSettings.isEnabled(context))
    }
    var confirmMeteredModelDownload by rememberSaveable { mutableStateOf(false) }
    val snapshot = (fleetState as? FleetLoadState.Ready)?.snapshot
    val pendingSchedules = snapshot?.schedules?.count { it.status == "pending" } ?: 0
    var showHostSetup by rememberSaveable { mutableStateOf(false) }
    if (showHostSetup) HostSetupDialog(
        onDismiss = { showHostSetup = false },
        onChanged = { FleetSnapshotStore.refresh(reconnect = true) },
        knownHosts = snapshot?.hosts.orEmpty()
    )
    val awaitingPairingRequests = snapshot?.pairingRequests.orEmpty().filter { it.status == "awaiting-review" }
    val healthyHosts = snapshot?.physicalHosts?.count { it.status == "healthy" } ?: 0
    val hostCount = snapshot?.physicalHosts?.size ?: 0
    val listState = rememberLazyListState()
    LaunchedEffect(pendingHostScrollSerial) {
        val requestSerial = pendingHostScrollSerial ?: return@LaunchedEffect
        val requestedHostId = pendingHostScrollId
        try {
            val hostIndex = snapshot?.physicalHosts?.indexOfFirst { it.id == requestedHostId } ?: -1
            if (hostIndex >= 0) {
                listState.animateScrollToItem(
                    moreHostLazyListIndex(awaitingPairingRequests.size, pendingSchedules, hostIndex)
                )
            }
        } finally {
            onHostScrollConsumed(requestSerial)
        }
    }
    LaunchedEffect(pendingPairingReviewsScrollSerial) {
        val requestSerial = pendingPairingReviewsScrollSerial ?: return@LaunchedEffect
        try {
            listState.animateScrollToItem(MORE_PAIRING_REVIEWS_INDEX)
        } finally {
            onPairingReviewsScrollConsumed(requestSerial)
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).testTag("more-screen"),
        state = listState,
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Text("More", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { AppUpdateCard(updateState, updateManifestUrl, onCheckUpdate, onInstallUpdate) }
        item {
            Button(onClick = { showHostSetup = true }, modifier = Modifier.fillMaxWidth().testTag("find-repair-hosts")) {
                Text("Find and repair hosts")
            }
        }
        item {
            Card(
                modifier = Modifier.testTag("window-layout-settings"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Window layout", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Auto uses the desktop workspace at 840 dp and wider.", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WorkspacePresentationMode.values().forEach { mode ->
                            if (mode == presentationMode) Button(onClick = { onPresentationMode(mode) }) { Text(mode.name) }
                            else OutlinedButton(onClick = { onPresentationMode(mode) }) { Text(mode.name) }
                        }
                    }
                }
            }
        }
        item {
            FleetAlertSettingsCard(
                settings = fleetAlertSettings,
                onSettings = onFleetAlertSettings,
                onPause = onPauseFleetAlerts,
                onResume = onResumeFleetAlerts
            )
        }
        item {
            FeatureCard(
                title = "Awaiting pairing reviews",
                detail = if (awaitingPairingRequests.isEmpty()) {
                    "No device proposals need review."
                } else {
                    "${awaitingPairingRequests.size} verified device proposal${if (awaitingPairingRequests.size == 1) "" else "s"} need an exact review."
                },
                modifier = Modifier.testTag("awaiting-pairing-reviews"),
                highlightLabel = if (highlightedPairingReviews) "Selected from alert" else null,
                highlightTag = if (highlightedPairingReviews) "awaiting-pairing-reviews-highlight" else null
            )
        }
        items(awaitingPairingRequests, key = { "pairing-review:${it.id}" }) { request ->
            AwaitingPairingReviewCard(request, onReviewPairingRequest)
        }
        item {
            val peerPackage = AgentFleetMigrationPeer.counterpart(context.packageName)
            val peerReady = remember(peerPackage) { AgentFleetMigrationPeer.sameSigner(context, peerPackage) }
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Move Fleet state", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (peerReady) "Copy Fleet settings, pairing, registry, and SSH files from the other app"
                            else "Install the other same-signed Agent Fleet lane to transfer state",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = onMigrateFleetState, enabled = peerReady, shape = RoundedCornerShape(14.dp)) { Text("Import") }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatic coding-session titles", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Uses bounded provider metadata. Turning this off purges cached titles on this phone.",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = automaticSessionTitles,
                        onCheckedChange = {
                            automaticSessionTitles = it
                            AutomaticSessionTitleSettings.setEnabled(context, it)
                        },
                        modifier = Modifier
                            .semantics { contentDescription = "Automatic coding-session titles" }
                            .testTag("automatic-session-titles")
                    )
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Native session view", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Conversation-first view with one-tap Terminal fallback", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = nativeSessionEnabled,
                        onCheckedChange = {
                            nativeSessionEnabled = it
                            NativeSessionSettings.setEnabled(context, it)
                        },
                        modifier = Modifier
                            .semantics { contentDescription = "Native session view" }
                            .testTag("native-session-view")
                    )
                }
            }
        }
        item {
            Card(
                modifier = Modifier.testTag("local-suggestions-settings"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("Local reply suggestions", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "${LocalSuggestionModel.DISPLAY_NAME} · on-device · Native view only",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().testTag("local-suggestions-mode"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LocalSuggestionMode.entries.forEach { mode ->
                            val selected = localModelUi.mode == mode
                            val enabled = mode == LocalSuggestionMode.OFF || localModelUi.ready && !localModelUi.busy
                            if (selected) Button(
                                onClick = { onSetLocalSuggestions(mode) }, enabled = enabled,
                                modifier = Modifier.weight(1f).testTag("local-suggestions-mode-${mode.preferenceValue}"),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 7.dp)
                            ) { Text(mode.displayLabel(), fontSize = 13.sp) }
                            else OutlinedButton(
                                onClick = { onSetLocalSuggestions(mode) }, enabled = enabled,
                                modifier = Modifier.weight(1f).testTag("local-suggestions-mode-${mode.preferenceValue}"),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 7.dp)
                            ) { Text(mode.displayLabel(), fontSize = 13.sp) }
                        }
                    }
                    Text(
                        when (localModelUi.mode) {
                            LocalSuggestionMode.OFF -> "No requests run and model RAM is released."
                            LocalSuggestionMode.MANUAL -> "Tap Suggest when you want local reply choices."
                            LocalSuggestionMode.AUTOMATIC -> "Prepares selectable replies for each new response in the active Native session; it never sends automatically."
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        localModelUi.error.ifBlank { localModelUi.detail },
                        fontSize = 14.sp,
                        color = if (localModelUi.error.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                    if (localModelUi.busy || localModelUi.progressBytes in 1 until LocalSuggestionModel.SIZE) {
                        LinearProgressIndicator(progress = { localModelUi.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "Pinned verified model · ${formatModelBytes(LocalSuggestionModel.SIZE)} storage. Off/background releases all model RAM.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (localModelUi.busy) {
                            OutlinedButton(onClick = onCancelLocalModel, modifier = Modifier.testTag("local-model-cancel")) { Text("Cancel") }
                        } else if (!localModelUi.ready) {
                            Button(
                                onClick = {
                                    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                                    if (manager?.isActiveNetworkMetered == true) confirmMeteredModelDownload = true else onDownloadLocalModel()
                                },
                                modifier = Modifier.testTag("local-model-download")
                            ) { Text(if (localModelUi.progressBytes > 0L) "Resume" else "Download") }
                            OutlinedButton(onClick = onImportLocalModel, modifier = Modifier.testTag("local-model-import")) { Text("Import") }
                            if (localModelUi.progressBytes > 0L) {
                                TextButton(onClick = onRemoveLocalModel, modifier = Modifier.testTag("local-model-remove")) { Text("Remove") }
                            }
                        } else {
                            TextButton(onClick = onRemoveLocalModel, modifier = Modifier.testTag("local-model-remove")) { Text("Remove") }
                        }
                    }
                }
            }
        }
        item { FeatureCard("Schedules", "$pendingSchedules pending · guarded delivery runs on the destination host") }
        if (snapshot != null) {
            items(snapshot.schedules.filter { it.status == "pending" }, key = { "schedule:${it.id}" }) { schedule ->
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Continue", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("${schedule.sessionId.substringAfter(':')} · ${schedule.deliverAt}", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = { onCancelSchedule(schedule) }, shape = RoundedCornerShape(14.dp)) { Text("Cancel") }
                    }
                }
            }
        }
        item { FeatureCard("Fleet health", "$healthyHosts of $hostCount hosts healthy${snapshot?.generatedAt?.let { " · $it" }.orEmpty()}") }
        if (snapshot != null) {
            items(snapshot.physicalHosts, key = { "host:${it.id}" }) { host ->
                val detail = physicalHostRecoveryDetail(snapshot, host)
                    ?: "${transportEndpointLabel(selectedTransportEndpoint(snapshot, host))} · Connected"
                val highlighted = host.id == highlightedHostId
                FeatureCard(
                    host.name,
                    detail,
                    modifier = Modifier.testTag("fleet-host-${host.id}"),
                    highlightLabel = if (highlighted) "Selected from alert" else null,
                    highlightTag = if (highlighted) "fleet-host-${host.id}-highlight" else null
                )
            }
        }
        item {
            val status = runtimeUi.status
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Built-in terminal", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        runtimeUi.error.ifBlank { runtimeUi.updateDetail.ifBlank { runtimeUi.detail } },
                        fontSize = 16.sp,
                        color = if (runtimeUi.error.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                    if (status != null) {
                        Text(
                            "Active ${status.current.ifBlank { "external" }} · APK ${status.embeddedBaseline} · ${status.packageCount - status.missingOrOldPackages}/${status.packageCount} packages ready",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (runtimeUi.compatibilityDetail.isNotBlank()) {
                            Text(
                                runtimeUi.compatibilityDetail,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (runtimeUi.busy) {
                            Button(onClick = {}, enabled = false, shape = RoundedCornerShape(14.dp)) { Text("Please wait") }
                        } else if (status?.supported != false) {
                            if (status == null || status.repairNeeded) {
                                Button(onClick = onRepairRuntime, shape = RoundedCornerShape(14.dp)) { Text("Repair") }
                            }
                            OutlinedButton(onClick = onCheckRuntime, shape = RoundedCornerShape(14.dp)) { Text("Check fixes") }
                            if (!status?.previous.isNullOrBlank()) {
                                OutlinedButton(onClick = onRollbackRuntime, shape = RoundedCornerShape(14.dp)) { Text("Roll back") }
                            }
                            if (status != null && status.baseline.isNotBlank() && status.current != status.baseline) {
                                OutlinedButton(onClick = onRestoreBaseline, shape = RoundedCornerShape(14.dp)) { Text("APK baseline") }
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pair or restore", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Connect this phone to the fleet", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = onPair, shape = RoundedCornerShape(14.dp)) { Text("Open") }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Terminal appearance", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Themes, text, cursor, spacing, and keys", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = onOpenAppearance, shape = RoundedCornerShape(14.dp)) { Text("Open") }
                }
            }
        }
        item {
            val status = runtimeUi.status
            Card(
                modifier = Modifier.testTag("open-diagnostics"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Diagnostics", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Runtime ${status?.current.orEmpty().ifBlank { "unavailable" }} · policy ${if (updateManifestUrl.isBlank()) "not paired" else "paired"}",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = onOpenDiagnostics, modifier = Modifier.testTag("open-diagnostics-button"), shape = RoundedCornerShape(14.dp)) { Text("Open") }
                }
            }
        }
    }
    if (confirmMeteredModelDownload) {
        AlertDialog(
            onDismissRequest = { confirmMeteredModelDownload = false },
            title = { Text("Download over a metered connection?") },
            text = { Text("The verified Gemma model is ${formatModelBytes(LocalSuggestionModel.SIZE)}. Carrier or hotspot charges may apply.") },
            confirmButton = {
                Button(onClick = { confirmMeteredModelDownload = false; onDownloadLocalModel() }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { confirmMeteredModelDownload = false }) { Text("Cancel") } }
        )
    }
}

private fun LocalSuggestionMode.displayLabel(): String = when (this) {
    LocalSuggestionMode.OFF -> "Off"
    LocalSuggestionMode.MANUAL -> "Manual"
    LocalSuggestionMode.AUTOMATIC -> "Automatic"
}

@Composable
private fun AppUpdateCard(
    updateState: UpdateUiState,
    updateManifestUrl: String,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: (AgentFleetUpdate) -> Unit
) {
    val detail = when (updateState) {
        UpdateUiState.Idle -> if (updateManifestUrl.isBlank()) "Pair this phone to configure updates" else "Ready to check"
        UpdateUiState.Checking -> "Checking signed manifest…"
        UpdateUiState.Downloading -> "Downloading and verifying…"
        is UpdateUiState.Current -> "${updateState.versionName} is current"
        is UpdateUiState.Available -> "${updateState.update.versionName} is available"
        is UpdateUiState.Error -> updateState.message
    }
    Card(
        modifier = Modifier.testTag("app-updates"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("App updates", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(detail, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (updateState) {
                    is UpdateUiState.Available -> Button(onClick = { onInstallUpdate(updateState.update) }, shape = RoundedCornerShape(14.dp)) { Text("Install") }
                    UpdateUiState.Checking, UpdateUiState.Downloading -> Button(onClick = {}, enabled = false, shape = RoundedCornerShape(14.dp)) { Text("Please wait") }
                    else -> Button(onClick = onCheckUpdate, enabled = updateManifestUrl.isNotBlank(), shape = RoundedCornerShape(14.dp)) { Text("Check") }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsDialog(
    state: DiagnosticsUiState,
    onDismiss: () -> Unit,
    onRun: () -> Unit,
    onCopy: (AgentFleetDiagnosticReport) -> Unit,
    onExport: (AgentFleetDiagnosticReport) -> Unit
) {
    var previewExport by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp).testTag("diagnostics-dialog"),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Diagnostics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Private, bounded health checks", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Text(
                    "Reports contain metadata only—never prompts, responses, transcripts, terminal output, credentials, invitations, attachments, or repository paths.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("diagnostics-progress"))
                    Text("Checking this phone and reachable hosts…")
                }
                if (state.error.isNotBlank()) {
                    Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("diagnostics-error"))
                }
                state.report?.let { report ->
                    val layeredChecks = LayeredDiagnostics.checks(report)
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("diagnostics-report"),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "${layeredChecks.count { it.status == "healthy" }}/${layeredChecks.size} layers healthy",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        layeredChecks.forEach { check ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("${when (check.status) { "healthy" -> "✓"; "failure" -> "×"; else -> "!" }}  ${check.label}", fontWeight = FontWeight.Bold)
                                    Text(check.summary, fontSize = 14.sp)
                                    Text(
                                        "${check.errorCode} · ${diagnosticRecoveryLabel(check.recoveryAction)}",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRun, enabled = !state.running, modifier = Modifier.testTag("diagnostics-run")) { Text("Run again") }
                        OutlinedButton(onClick = { onCopy(report) }, modifier = Modifier.testTag("diagnostics-copy")) { Text("Copy redacted report") }
                        OutlinedButton(onClick = { previewExport = true }, modifier = Modifier.testTag("diagnostics-export")) { Text("Export") }
                    }
                } ?: Button(
                    onClick = onRun,
                    enabled = !state.running,
                    modifier = Modifier.fillMaxWidth().testTag("diagnostics-run")
                ) { Text(if (state.running) "Running checks…" else "Run checks") }
            }
        }
    }
    if (previewExport) {
        val report = state.report
        AlertDialog(
            onDismissRequest = { previewExport = false },
            title = { Text("Preview diagnostic report") },
            text = {
                Text(
                    report?.diagnosticsJson().orEmpty(),
                    modifier = Modifier.verticalScroll(rememberScrollState()).testTag("diagnostics-preview")
                )
            },
            confirmButton = {
                TextButton(
                    enabled = report != null,
                    onClick = { previewExport = false; report?.let(onExport) },
                    modifier = Modifier.testTag("diagnostics-share")
                ) { Text("Share ZIP") }
            },
            dismissButton = { TextButton(onClick = { previewExport = false }) { Text("Cancel") } }
        )
    }
}

private fun diagnosticRecoveryLabel(action: String): String = when (action) {
    "retry" -> "Retry"
    "repair_client_runtime" -> "Repair client runtime"
    "rollback_runtime" -> "Roll back runtime"
    "open_tailscale" -> "Open Tailscale"
    "review_host_key" -> "Review host key"
    "copy_redacted_report" -> "Copy redacted report"
    "open_terminal" -> "Open Terminal"
    else -> "No action needed"
}

@Composable
private fun PreparingTerminalScreen(runtimeUi: RuntimeUiState, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(
                Modifier.fillMaxWidth().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Preparing terminal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    runtimeUi.error.ifBlank { runtimeUi.detail },
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                    color = if (runtimeUi.error.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
                if (runtimeUi.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Everything needed is already inside the APK", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Button(onClick = onRetry, shape = RoundedCornerShape(14.dp)) { Text("Try again") }
                }
            }
        }
    }
}

@Composable
private fun FeatureCard(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    highlightLabel: String? = null,
    highlightTag: String? = null
) {
    Card(
        modifier = if (highlightLabel == null) {
            modifier
        } else {
            modifier.semantics { liveRegion = LiveRegionMode.Polite }
        },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlightLabel == null) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            }
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (highlightLabel != null) {
                Text(
                    highlightLabel,
                    modifier = if (highlightTag == null) Modifier else Modifier.testTag(highlightTag),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(detail, fontSize = 16.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 42.dp), contentAlignment = Alignment.Center) {
        Text(message, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun shouldShowRepositoryEmpty(loading: Boolean, error: String, entryCount: Int): Boolean =
    !loading && error.isBlank() && entryCount == 0

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(11.dp).background(color, CircleShape))
}

private val FleetBlue = Color(0xFF315DDE)
private val ReadyGreen = Color(0xFF14845B)
private val WarningAmber = Color(0xFFB06000)
private val QuietGray = Color(0xFF6D7280)
private val previewFleetSnapshot = FleetSnapshot(
    revision = "preview",
    generatedAt = "2026-07-12T05:00:00Z",
    hosts = listOf(FleetHost("gaming", "Gaming desktop", "healthy", "wsl", null, setOf("sessions.read"))),
    sessions = listOf(
        FleetSession("gaming:wtmux", "gaming", "wtmux-main", "wtmux", "Android companion", "wtmux", "codex", "linux", "active", true, null, 0),
        FleetSession("gaming:agent", "gaming", "agent-main", "agent-fleet", "Terminal tabs", "agent-fleet", "claude", "linux", "idle", false, null, 0)
    ),
    schedules = emptyList(),
    attention = emptyList()
)

private val FleetLightColors = lightColorScheme(
    primary = FleetBlue,
    onPrimary = Color.White,
    background = Color(0xFFF6F7FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFE3E7F1),
    onSurface = Color(0xFF181B22),
    onSurfaceVariant = Color(0xFF5D6472)
)

private val FleetDarkColors = darkColorScheme(
    primary = Color(0xFFAFC6FF),
    background = Color(0xFF111318),
    surface = Color(0xFF1B1E24),
    surfaceVariant = Color(0xFF343842),
    onSurface = Color(0xFFE6E8EE),
    onSurfaceVariant = Color(0xFFBFC5D1)
)

private fun TextStyle.withFleetFontMetrics(): TextStyle = copy(
    platformStyle = PlatformTextStyle(includeFontPadding = true),
    lineHeightStyle = null
)

private val FleetTypography = Typography().let { defaults ->
    Typography(
        displayLarge = defaults.displayLarge.withFleetFontMetrics(),
        displayMedium = defaults.displayMedium.withFleetFontMetrics(),
        displaySmall = defaults.displaySmall.withFleetFontMetrics(),
        headlineLarge = defaults.headlineLarge.withFleetFontMetrics(),
        headlineMedium = defaults.headlineMedium.withFleetFontMetrics(),
        headlineSmall = defaults.headlineSmall.withFleetFontMetrics(),
        titleLarge = defaults.titleLarge.withFleetFontMetrics(),
        titleMedium = defaults.titleMedium.withFleetFontMetrics(),
        titleSmall = defaults.titleSmall.withFleetFontMetrics(),
        bodyLarge = defaults.bodyLarge.withFleetFontMetrics(),
        bodyMedium = defaults.bodyMedium.withFleetFontMetrics(),
        bodySmall = defaults.bodySmall.withFleetFontMetrics(),
        labelLarge = defaults.labelLarge.withFleetFontMetrics(),
        labelMedium = defaults.labelMedium.withFleetFontMetrics(),
        labelSmall = defaults.labelSmall.withFleetFontMetrics()
    )
}

@Composable
fun AgentFleetTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) FleetDarkColors else FleetLightColors,
        typography = FleetTypography
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun AgentFleetPreview() {
    AgentFleetTheme {
        AgentFleetApp(
            fleetState = FleetLoadState.Ready(previewFleetSnapshot),
            recentSessions = previewFleetSnapshot.sessions,
            pendingPairInvitation = null,
            onPairInvitationHandled = {},
            pendingSharedImages = emptyList(),
            updateState = UpdateUiState.Idle,
            updateManifestUrl = "",
            runtimeUi = RuntimeUiState(
                status = EmbeddedRuntimeStatus(
                    supported = true, usable = true, repairNeeded = false,
                    embeddedBaseline = "git-5b17df4", baseline = "git-5b17df4", current = "git-5b17df4", previous = "",
                    missingOrOldPackages = 0, packageCount = 70, trustedKeyIds = emptyList(), detail = "Built-in terminal is ready"
                ),
                detail = "Built-in terminal is ready"
            ),
            diagnosticsUi = DiagnosticsUiState(),
            diagnosticError = null,
            localModelUi = LocalModelUiState(),
            onSharedImagesHandled = {},
            onRefresh = {},
            onOpenSession = {},
            onOpenSessionWithImages = { _, _ -> },
            onCreateSession = { _, _, _, _, _, _ -> },
            onListDirectory = { _, _, _, callback -> callback(Result.failure(IllegalStateException("Preview"))) },
            onCreateDirectory = { _, _, _, _, callback -> callback(Result.failure(IllegalStateException("Preview"))) },
            onListRepository = { _, _, _, _, callback -> callback(Result.failure(IllegalStateException("Preview"))) },
            onSearchRepository = { _, _, _, callback -> callback(Result.failure(IllegalStateException("Preview"))) },
            onDownloadRepository = { _, _, _ -> FleetDownloadCancellation() },
            onCloseRepository = {},
            onOpenDownload = {},
            onRenameSession = { _, _ -> },
            onResetSessionName = {},
            onScheduleContinue = { _, _ -> },
            onScheduleAttention = { _, _, _ -> },
            onDismissAttention = {},
            onCancelSchedule = {},
            onKillSession = {},
            onCopyAttachCommand = {},
            onPairInvitation = {},
            onCheckUpdate = {},
            onInstallUpdate = {},
            onRepairRuntime = {},
            onCheckRuntime = {},
            onRollbackRuntime = {},
            onRestoreBaseline = {},
            onOpenAppearance = {},
            onMigrateFleetState = {},
            onSetLocalSuggestions = {},
            onDownloadLocalModel = {},
            onImportLocalModel = {},
            onCancelLocalModel = {},
            onRemoveLocalModel = {},
            onRunDiagnostics = {},
            onCopyDiagnostics = {},
            onExportDiagnostics = {},
            onCopyDiagnosticError = {},
            onDiagnosticErrorHandled = {}
        )
    }
}
