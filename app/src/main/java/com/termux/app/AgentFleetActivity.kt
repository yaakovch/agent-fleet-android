package com.termux.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.app.fleet.FleetHost
import com.termux.app.fleet.FleetDirectoryListing
import com.termux.app.fleet.FleetLoadState
import com.termux.app.fleet.FleetLimit
import com.termux.app.fleet.FleetLimitWindow
import com.termux.app.fleet.FleetRuntime
import com.termux.app.fleet.FleetSession
import com.termux.app.fleet.FleetSchedule
import com.termux.app.fleet.FleetSnapshot
import com.termux.app.fleet.NativeSessionSettings
import com.termux.app.fleet.RecentSessionStore
import com.termux.app.fleet.RecentLocationStore
import com.termux.app.fleet.AgentFleetUpdate
import com.termux.app.fleet.AgentFleetUpdateManager
import com.termux.app.fleet.ClientPolicyStore
import com.termux.app.fleet.EmbeddedRuntimeManager
import com.termux.app.fleet.EmbeddedRuntimeStatus
import com.termux.app.fleet.RuntimeUpdateManager
import com.termux.app.fleet.RuntimeUpdateResult
import com.termux.app.fleet.supportsEmbeddedRuntime
import com.termux.app.fleet.UpdateUiState
import java.io.File
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class AgentFleetActivity : ComponentActivity() {
    private val fleetState = mutableStateOf<FleetLoadState>(FleetLoadState.Loading)
    private val recentSessions = mutableStateOf<List<FleetSession>>(emptyList())
    private val pendingPairInvitation = mutableStateOf<String?>(null)
    private val pendingSharedImages = mutableStateOf<List<String>>(emptyList())
    private val updateState = mutableStateOf<UpdateUiState>(UpdateUiState.Idle)
    private val updateManifestUrl = mutableStateOf("")
    private val runtimeUi = mutableStateOf(RuntimeUiState())
    private val fleetExecutor = Executors.newSingleThreadExecutor()
    private val updateExecutor = Executors.newSingleThreadExecutor()
    private val runtimeExecutor = Executors.newSingleThreadExecutor()
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshFleet()
            refreshHandler.postDelayed(this, 10_000)
        }
    }
    private lateinit var fleetRuntime: FleetRuntime
    private lateinit var recentSessionStore: RecentSessionStore
    private lateinit var updateManager: AgentFleetUpdateManager
    private lateinit var embeddedRuntime: EmbeddedRuntimeManager
    private lateinit var runtimeUpdateManager: RuntimeUpdateManager
    private lateinit var clientPolicyStore: ClientPolicyStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fleetRuntime = FleetRuntime(applicationContext)
        recentSessionStore = RecentSessionStore(applicationContext)
        updateManager = AgentFleetUpdateManager(applicationContext)
        embeddedRuntime = EmbeddedRuntimeManager(applicationContext)
        runtimeUpdateManager = RuntimeUpdateManager(applicationContext, embeddedRuntime)
        clientPolicyStore = ClientPolicyStore(applicationContext)
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
        setContent {
            AgentFleetTheme {
                AgentFleetApp(
                    fleetState = fleetState.value,
                    recentSessions = recentSessions.value,
                    pendingPairInvitation = pendingPairInvitation.value,
                    onPairInvitationHandled = { pendingPairInvitation.value = null },
                    pendingSharedImages = pendingSharedImages.value,
                    updateState = updateState.value,
                    updateManifestUrl = updateManifestUrl.value,
                    runtimeUi = runtimeUi.value,
                    onSharedImagesHandled = { pendingSharedImages.value = emptyList() },
                    onRefresh = ::refreshFleet,
                    onOpenSession = ::openFleetSession,
                    onOpenSessionWithImages = ::openFleetSessionWithImages,
                    onCreateSession = ::createFleetSession,
                    onListDirectory = ::listFleetDirectory,
                    onCreateDirectory = ::createFleetDirectory,
                    onRenameSession = ::renameFleetSession,
                    onScheduleContinue = ::scheduleContinue,
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
                    onOpenClassicTerminal = {
                        try {
                            fleetRuntime.openLocalShell()
                        } catch (error: Exception) {
                            Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
        }
        TermuxInstaller.setupBootstrapIfNeeded(this) {
            prepareEmbeddedRuntime(autoRepair = automaticPreparation, blocking = automaticPreparation)
        }
    }

    override fun onStart() {
        super.onStart()
        refreshUpdatePolicy()
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshRunnable.run()
        if (
            ::runtimeUpdateManager.isInitialized && !runtimeUi.value.busy && runtimeUi.value.status?.usable == true &&
            runtimeUpdateManager.shouldCheck()
        ) checkRuntimeUpdate(manual = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptPairingIntent(intent)
        acceptSharedImages(intent)
    }

    override fun onStop() {
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        fleetExecutor.shutdownNow()
        updateExecutor.shutdownNow()
        runtimeExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun refreshFleet() {
        if (fleetState.value !is FleetLoadState.Ready) fleetState.value = FleetLoadState.Loading
        fleetExecutor.execute {
            val result = try {
                FleetLoadState.Ready(fleetRuntime.loadSnapshot())
            } catch (error: Exception) {
                FleetLoadState.Unavailable(error.message ?: "Fleet refresh failed.")
            }
            runOnUiThread { fleetState.value = result }
        }
    }

    private fun openFleetSession(session: FleetSession) {
        try {
            recentSessionStore.record(session)
            recentSessions.value = recentSessionStore.load()
            fleetRuntime.openSession(session)
        } catch (error: Exception) {
            Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun openFleetSessionWithImages(session: FleetSession, images: List<String>) {
        try {
            recentSessionStore.record(session)
            recentSessions.value = recentSessionStore.load()
            fleetRuntime.openSession(session, images)
        } catch (error: Exception) {
            Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun renameFleetSession(session: FleetSession, name: String) = mutateFleet("Session renamed") { snapshot ->
        fleetRuntime.renameSession(snapshot, session, name)
    }

    private fun createFleetSession(hostId: String, project: String, backend: String, tool: String, path: String, locationKind: String) = mutateFleet("Session created") { snapshot ->
        fleetRuntime.createSession(snapshot, hostId, project, backend, tool, path, locationKind)
    }

    private fun listFleetDirectory(hostId: String, backend: String, path: String, callback: (Result<FleetDirectoryListing>) -> Unit) {
        val snapshot = (fleetState.value as? FleetLoadState.Ready)?.snapshot
            ?: return callback(Result.failure(IllegalStateException("Fleet is still loading.")))
        fleetExecutor.execute {
            val result = runCatching { fleetRuntime.listDirectory(snapshot, hostId, backend, path) }
            runOnUiThread { callback(result) }
        }
    }

    private fun createFleetDirectory(hostId: String, backend: String, parentPath: String, name: String, callback: (Result<String>) -> Unit) {
        val snapshot = (fleetState.value as? FleetLoadState.Ready)?.snapshot
            ?: return callback(Result.failure(IllegalStateException("Fleet is still loading.")))
        fleetExecutor.execute {
            val result = runCatching { fleetRuntime.createDirectory(snapshot, hostId, backend, parentPath, name) }
            runOnUiThread { callback(result) }
        }
    }

    private fun scheduleContinue(session: FleetSession, delayMs: Long) = mutateFleet("Continue scheduled") { snapshot ->
        fleetRuntime.scheduleContinue(snapshot, session, System.currentTimeMillis() + delayMs)
    }

    private fun killFleetSession(session: FleetSession) = mutateFleet("Session stopped") { snapshot ->
        fleetRuntime.killSession(snapshot, session)
    }

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
                    onFailure = { UpdateUiState.Error(it.message ?: "Update check failed") }
                )
            }
        }
    }

    private fun prepareEmbeddedRuntime(autoRepair: Boolean, blocking: Boolean) {
        runtimeUi.value = runtimeUi.value.copy(
            busy = true, blocking = blocking, error = "",
            detail = if (autoRepair) "Preparing the built-in terminal…" else "Checking the built-in terminal…"
        )
        runtimeExecutor.execute {
            val result = runCatching {
                if (autoRepair) embeddedRuntime.repair { detail ->
                    runOnUiThread { runtimeUi.value = runtimeUi.value.copy(detail = detail) }
                } else embeddedRuntime.inspect()
            }
            runOnUiThread {
                result.onSuccess { status ->
                    runtimeUi.value = RuntimeUiState(
                        status = status, detail = status.detail, blocking = false
                    )
                    refreshFleet()
                    if (status.usable && runtimeUpdateManager.shouldCheck()) checkRuntimeUpdate(manual = false)
                }.onFailure { error ->
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
                result.onSuccess { status -> runtimeUi.value = RuntimeUiState(status = status, detail = status.detail) }
                    .onFailure { error -> runtimeUi.value = runtimeUi.value.copy(
                        busy = false, error = error.message ?: "Runtime recovery failed"
                    ) }
            }
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

    private fun mutateFleet(successMessage: String, action: (FleetSnapshot) -> FleetSnapshot) {
        val snapshot = (fleetState.value as? FleetLoadState.Ready)?.snapshot ?: return refreshFleet()
        fleetExecutor.execute {
            val result = runCatching { action(snapshot) }
            runOnUiThread {
                result.onSuccess {
                    fleetState.value = FleetLoadState.Ready(it)
                    Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show()
                }.onFailure {
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
    val error: String = ""
)

enum class FleetSection(val label: String, val glyph: String) {
    Sessions("Sessions", "▣"),
    Terminal("Terminal", ">_"),
    Limits("Limits", "%"),
    More("More", "•••")
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
    onSharedImagesHandled: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSession: (FleetSession) -> Unit,
    onOpenSessionWithImages: (FleetSession, List<String>) -> Unit,
    onCreateSession: (String, String, String, String, String, String) -> Unit,
    onListDirectory: (String, String, String, (Result<FleetDirectoryListing>) -> Unit) -> Unit,
    onCreateDirectory: (String, String, String, String, (Result<String>) -> Unit) -> Unit,
    onRenameSession: (FleetSession, String) -> Unit,
    onScheduleContinue: (FleetSession, Long) -> Unit,
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
    onOpenClassicTerminal: () -> Unit
) {
    if (runtimeUi.blocking) {
        PreparingTerminalScreen(runtimeUi, onRepairRuntime)
        return
    }
    var section by rememberSaveable { mutableStateOf(FleetSection.Sessions) }
    var actionSession by rememberSaveable { mutableStateOf<String?>(null) }
    var renameSession by rememberSaveable { mutableStateOf<String?>(null) }
    var scheduleSession by rememberSaveable { mutableStateOf<String?>(null) }
    var killSession by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreateSession by rememberSaveable { mutableStateOf(false) }
    var showPairing by rememberSaveable { mutableStateOf(false) }
    val currentSnapshot = (fleetState as? FleetLoadState.Ready)?.snapshot
    val sessionsById = currentSnapshot?.sessions?.associateBy { it.id }.orEmpty()
    LaunchedEffect(pendingPairInvitation) {
        if (pendingPairInvitation != null) showPairing = true
    }

    Scaffold(
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
            FleetSection.Sessions -> SessionsScreen(padding, fleetState, onRefresh, onOpenSession, { actionSession = it.id }, { showCreateSession = true }, { showPairing = true }, onOpenClassicTerminal)
            FleetSection.Terminal -> TerminalScreen(padding, recentSessions, onOpenSession, onOpenClassicTerminal, onOpenAppearance)
            FleetSection.Limits -> LimitsScreen(padding, fleetState, onScheduleContinue)
            FleetSection.More -> MoreScreen(
                padding,
                fleetState,
                updateState,
                updateManifestUrl,
                runtimeUi,
                { showPairing = true },
                onCancelSchedule,
                onCheckUpdate,
                onInstallUpdate,
                onRepairRuntime,
                onCheckRuntime,
                onRollbackRuntime,
                onRestoreBaseline,
                onOpenAppearance
            )
        }
    }

    sessionsById[actionSession]?.let { session ->
        SessionActionsDialog(
            session = session,
            onDismiss = { actionSession = null },
            onOpen = { actionSession = null; onOpenSession(session) },
            onRename = { actionSession = null; renameSession = session.id },
            onSchedule = { actionSession = null; scheduleSession = session.id },
            onCopy = { actionSession = null; onCopyAttachCommand(session) },
            onKill = { actionSession = null; killSession = session.id }
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
            hosts = currentSnapshot.hosts,
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
    onRefresh: () -> Unit,
    onOpenSession: (FleetSession) -> Unit,
    onMoreSession: (FleetSession) -> Unit,
    onNewSession: () -> Unit,
    onPair: () -> Unit,
    onOpenTerminal: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val snapshot = (fleetState as? FleetLoadState.Ready)?.snapshot
    val hosts = snapshot?.hosts?.associateBy { it.id }.orEmpty()
    val filtered = filterSessions(snapshot?.sessions.orEmpty(), hosts, query)

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
                        if (snapshot == null) "Connect to your fleet" else "${snapshot.sessions.size} sessions across ${snapshot.hosts.size} machines",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                }
                Button(onClick = onNewSession, enabled = snapshot != null, shape = RoundedCornerShape(14.dp)) { Text("New", fontSize = 16.sp) }
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
                FleetUnavailableCard(fleetState.reason, onRefresh, onPair, onOpenTerminal)
            }
            is FleetLoadState.Ready -> {
                if (filtered.isEmpty()) {
                    item { EmptyState(if (query.isBlank()) "No managed sessions are open." else "No sessions match “$query”.") }
                } else {
                    items(filtered, key = { it.id }) { session ->
                        SessionCard(session, hosts[session.hostId]?.name ?: session.hostId, { onOpenSession(session) }, { onMoreSession(session) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(session: FleetSession, hostName: String, onOpen: () -> Unit, onMore: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("session-${session.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (session.activity == "active") ReadyGreen else QuietGray)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(session.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (session.title.isNotBlank()) Text(session.title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (session.activity == "active") "Active" else "Idle", color = if (session.activity == "active") ReadyGreen else QuietGray, fontWeight = FontWeight.SemiBold)
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
                Button(onClick = onOpen, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                    Text(if (session.attached) "Return" else "Enter", fontSize = 17.sp)
                }
                OutlinedButton(onClick = onMore, shape = RoundedCornerShape(14.dp)) { Text("More", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun SessionActionsDialog(
    session: FleetSession,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onSchedule: () -> Unit,
    onCopy: () -> Unit,
    onKill: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(session.name, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Session details", fontWeight = FontWeight.SemiBold)
                Text("${session.hostId} · ${session.backend} · ${session.tool}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(session.projectPath.ifBlank { "Path unavailable for this older session" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DialogAction("Open terminal", onOpen)
                DialogAction("Rename", onRename)
                DialogAction("Schedule Continue", onSchedule)
                DialogAction("Copy attach command", onCopy)
                DialogAction("Stop session…", onKill, WarningAmber)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun DialogAction(label: String, onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.primary) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.fillMaxWidth(), color = color, fontSize = 17.sp)
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
                Text("Choose when ${session.name} should receive one guarded Continue message.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    hosts: List<FleetHost>,
    onListDirectory: (String, String, String, (Result<FleetDirectoryListing>) -> Unit) -> Unit,
    onCreateDirectory: (String, String, String, String, (Result<String>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String, String, String) -> Unit
) {
    var hostId by rememberSaveable { mutableStateOf(hosts.firstOrNull { it.status == "healthy" }?.id ?: hosts.firstOrNull()?.id.orEmpty()) }
    var backend by rememberSaveable { mutableStateOf("linux") }
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

    fun browse(path: String, preferProjects: Boolean = false, recent: Boolean = false) {
        loading = true
        error = ""
        onListDirectory(hostId, backend, path) { first ->
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

    LaunchedEffect(hostId, backend, locationKind) {
        listing = null
        selectedPath = ""
        label = ""
        browse("", locationKind == "project")
    }
    val valid = hostId.isNotBlank() && selectedPath.isNotBlank() && label.matches(Regex("[A-Za-z0-9][A-Za-z0-9._ -]{0,63}"))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New session") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Machine", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    hosts.filter { it.status == "healthy" }.forEach { host ->
                        AssistChip(onClick = { hostId = host.id }, label = { Text(if (host.id == hostId) "✓ ${host.name}" else host.name) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("linux", "windows").forEach { choice ->
                        AssistChip(onClick = { backend = choice }, label = { Text(if (backend == choice) "✓ $choice" else choice) })
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
                            onCreateDirectory(hostId, backend, parent, newFolder.trim()) { result ->
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
            onConfirm(hostId, label.trim(), backend, tool, selectedPath, locationKind)
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
    val valid = invitation.trim().startsWith("wtmux://pair?") && invitation.length <= 4_096
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
                Text("The controller still has to approve this phone.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(invitation.trim()) }, enabled = valid) { Text("Continue in terminal") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
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
                    DialogAction("${session.name} · ${session.tool}", { onConfirm(session) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FleetUnavailableCard(reason: String, onRefresh: () -> Unit, onPair: () -> Unit, onOpenTerminal: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Fleet is not connected", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(reason, fontSize = 16.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPair, shape = RoundedCornerShape(14.dp)) { Text("Pair", fontSize = 16.sp) }
                OutlinedButton(onClick = onRefresh, shape = RoundedCornerShape(14.dp)) { Text("Retry", fontSize = 16.sp) }
            }
            TextButton(onClick = onOpenTerminal) { Text("Open terminal for manual restore") }
        }
    }
}

@Composable
private fun TerminalScreen(
    padding: PaddingValues,
    recentSessions: List<FleetSession>,
    onOpenSession: (FleetSession) -> Unit,
    onOpenClassicTerminal: () -> Unit,
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
                Text("Recent local and fleet tabs", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = onOpenClassicTerminal,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("New shell", fontSize = 16.sp) }
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
                            Text(session.name, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text(
                                session.hostId,
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
    onScheduleContinue: (FleetSession, Long) -> Unit
) {
    val snapshot = (fleetState as? FleetLoadState.Ready)?.snapshot
    val sessions = snapshot?.sessions?.associateBy { it.id }.orEmpty()
    val activeAttention = snapshot?.attention?.filter { it.state in setOf("detected", "offered", "scheduled") }.orEmpty()
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
                        if (session != null && attention.resetAt != null) {
                            val delay = resetDelayMs(attention.resetAt)
                            Button(onClick = { onScheduleContinue(session, delay) }, enabled = delay > 0, shape = RoundedCornerShape(14.dp)) {
                                Text("Schedule Continue after reset", fontSize = 16.sp)
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
            progress = (window.remainingPercent / 100.0).toFloat(),
            modifier = Modifier.weight(1f).height(9.dp),
            color = if (window.remainingPercent <= 0) WarningAmber else ReadyGreen,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Text("${window.remainingPercent.toInt()}%", modifier = Modifier.padding(start = 10.dp), fontWeight = FontWeight.Bold)
    }
}

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
private fun MoreScreen(
    padding: PaddingValues,
    fleetState: FleetLoadState,
    updateState: UpdateUiState,
    updateManifestUrl: String,
    runtimeUi: RuntimeUiState,
    onPair: () -> Unit,
    onCancelSchedule: (FleetSchedule) -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: (AgentFleetUpdate) -> Unit,
    onRepairRuntime: () -> Unit,
    onCheckRuntime: () -> Unit,
    onRollbackRuntime: () -> Unit,
    onRestoreBaseline: () -> Unit,
    onOpenAppearance: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var nativeSessionEnabled by rememberSaveable {
        mutableStateOf(NativeSessionSettings.isEnabled(context))
    }
    val snapshot = (fleetState as? FleetLoadState.Ready)?.snapshot
    val pendingSchedules = snapshot?.schedules?.count { it.status == "pending" } ?: 0
    val healthyHosts = snapshot?.hosts?.count { it.status == "healthy" } ?: 0
    val hostCount = snapshot?.hosts?.size ?: 0
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).testTag("more-screen"),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Text("More", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
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
                        }
                    )
                }
            }
        }
        item { FeatureCard("Schedules", "$pendingSchedules pending · guarded delivery runs on the destination host") }
        if (snapshot != null) {
            items(snapshot.schedules.filter { it.status == "pending" }, key = { it.id }) { schedule ->
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
            items(snapshot.hosts.filter { it.status != "healthy" }, key = { it.id }) { host ->
                FeatureCard(host.name, "${host.status} · last seen ${host.lastSeenAt ?: "unknown"}")
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
            val detail = when (updateState) {
                UpdateUiState.Idle -> if (updateManifestUrl.isBlank()) "Pair this phone to configure updates" else "Ready to check"
                UpdateUiState.Checking -> "Checking signed manifest…"
                UpdateUiState.Downloading -> "Downloading and verifying…"
                is UpdateUiState.Current -> "${updateState.versionName} is current"
                is UpdateUiState.Available -> "${updateState.update.versionName} is available"
                is UpdateUiState.Error -> updateState.message
            }
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
            FeatureCard(
                "Diagnostics",
                "Runtime ${status?.current.orEmpty().ifBlank { "unavailable" }} · baseline ${status?.baseline.orEmpty().ifBlank { "not installed" }} · policy ${if (updateManifestUrl.isBlank()) "not paired" else "paired"}"
            )
        }
    }
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
private fun FeatureCard(title: String, detail: String) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
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

@Composable
fun AgentFleetTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) FleetDarkColors else FleetLightColors) {
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
                    embeddedBaseline = "git-ea0eebe", baseline = "git-ea0eebe", current = "git-ea0eebe", previous = "",
                    missingOrOldPackages = 0, packageCount = 70, trustedKeyIds = emptyList(), detail = "Built-in terminal is ready"
                ),
                detail = "Built-in terminal is ready"
            ),
            onSharedImagesHandled = {},
            onRefresh = {},
            onOpenSession = {},
            onOpenSessionWithImages = { _, _ -> },
            onCreateSession = { _, _, _, _, _, _ -> },
            onListDirectory = { _, _, _, callback -> callback(Result.failure(IllegalStateException("Preview"))) },
            onCreateDirectory = { _, _, _, _, callback -> callback(Result.failure(IllegalStateException("Preview"))) },
            onRenameSession = { _, _ -> },
            onScheduleContinue = { _, _ -> },
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
            onOpenClassicTerminal = {}
        )
    }
}
