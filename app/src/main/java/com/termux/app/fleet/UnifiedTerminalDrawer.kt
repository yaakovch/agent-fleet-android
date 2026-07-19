package com.termux.app.fleet

import android.content.Intent
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.drawerlayout.widget.DrawerLayout
import com.termux.app.AgentFleetActivity
import com.termux.app.AgentFleetTheme
import com.termux.app.TerminalAppearanceActivity
import com.termux.app.TermuxActivity
import com.termux.app.TermuxService
import com.termux.shared.shell.TermuxSession
import kotlin.math.roundToInt
import java.util.concurrent.Executors

data class DrawerLocalSession(
    val handle: String,
    val name: String,
    val title: String,
    val running: Boolean
)

data class UnifiedDrawerState(
    val remoteSessions: List<DrawerRemoteSession> = emptyList(),
    val hostNames: Map<String, String> = emptyMap(),
    val localSessions: List<DrawerLocalSession> = emptyList(),
    val attachedSessionIds: Set<String> = emptySet(),
    val activeRemoteId: String? = null,
    val activeLocalHandle: String? = null,
    val drawerOpen: Boolean = false,
    val status: String = "",
    val busySessionId: String? = null
)

class UnifiedTerminalDrawerController(
    private val activity: TermuxActivity,
    private val composeView: ComposeView
) : DrawerLayout.DrawerListener {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val store = DrawerSessionStore(activity.applicationContext)
    private val recentStore = RecentSessionStore(activity.applicationContext)
    private val runtime = FleetRuntime(activity.applicationContext)
    private val state = mutableStateOf(UnifiedDrawerState())
    private var service: TermuxService? = null
    private var snapshot: FleetSnapshot? = FleetSnapshotStore.latestSnapshot()
    private var fleetReachable = snapshot != null
    private var statusMessage = ""
    private var observing = false
    private var lastDrawerRefreshAt = 0L
    private val locallyRemovedOfflineIds = mutableSetOf<String>()
    private var destroyed = false

    init {
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        composeView.setContent {
            AgentFleetTheme {
                UnifiedTerminalDrawer(
                    state = state.value,
                    onOpenRemote = ::openRemote,
                    onTogglePin = ::togglePin,
                    onKillRemote = ::killRemote,
                    onCloseRemote = ::closeRemote,
                    onRemoveRemote = ::removeRemote,
                    onOpenAgentFleetSession = ::openAgentFleetSession,
                    onRefresh = ::refreshNow,
                    onCloseLocal = ::closeLocal,
                    onCreateLocal = ::createLocal,
                    onOpenAgentFleet = ::openAgentFleet,
                    onKeyboard = ::toggleKeyboard,
                    onAppearance = ::openAppearance
                )
            }
        }
        activity.drawer.addDrawerListener(this)
        rebuild()
    }

    fun attachService(value: TermuxService) {
        service = value
        rebuild()
    }

    fun onStart() {
        if (activity.drawer.isDrawerOpen(composeView)) beginObserving()
        rebuild()
    }

    fun onStop() {
        endObserving()
    }

    fun close() {
        destroyed = true
        endObserving()
        activity.drawer.removeDrawerListener(this)
        main.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        runtime.shutdown()
    }

    fun notifySessionsChanged() = rebuild()

    fun scrollToSession() = rebuild()

    private fun beginObserving() {
        if (observing || destroyed) return
        observing = true
        FleetSnapshotStore.observePassive(activity.applicationContext, this) { loadState ->
            when (loadState) {
                is FleetLoadState.Ready -> {
                    snapshot = loadState.snapshot
                    fleetReachable = true
                    statusMessage = ""
                    locallyRemovedOfflineIds.clear()
                    rebuild()
                }
                is FleetLoadState.Unavailable -> {
                    fleetReachable = false
                    statusMessage = loadState.reason
                    rebuild()
                }
                FleetLoadState.Loading -> {
                    statusMessage = "Refreshing sessions…"
                    rebuild()
                }
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastDrawerRefreshAt >= REFRESH_COOLDOWN_MS) {
            lastDrawerRefreshAt = now
            FleetSnapshotStore.refresh()
        }
    }

    private fun endObserving() {
        if (!observing) return
        observing = false
        FleetSnapshotStore.removeObserver(this)
    }

    private fun refreshNow() {
        if (!observing) beginObserving()
        lastDrawerRefreshAt = System.currentTimeMillis()
        statusMessage = "Refreshing sessions…"
        FleetSnapshotStore.refresh(showLoading = false)
        rebuild()
    }

    private fun rebuild() {
        if (destroyed) return
        val hostNames = snapshot?.hosts.orEmpty().associate { it.id to it.name }
        val local = service?.classicTermuxSessions.orEmpty().mapNotNull { session ->
            val terminal = session.terminalSession ?: return@mapNotNull null
            DrawerLocalSession(
                handle = terminal.mHandle,
                name = terminal.mSessionName.orEmpty(),
                title = terminal.title.orEmpty(),
                running = terminal.isRunning
            )
        }
        val current = activity.currentSession
        val activeRemote = service?.currentAgentFleetSessionId
        val remoteRows = store.rows(snapshot)
            .filterNot { it.session.id in locallyRemovedOfflineIds }
            .map { row -> if (fleetReachable) row else row.copy(available = false, cached = true) }
        state.value = state.value.copy(
            remoteSessions = remoteRows,
            hostNames = hostNames,
            localSessions = local,
            attachedSessionIds = service?.agentFleetWorkspaceSessionIds.orEmpty(),
            activeRemoteId = activeRemote,
            activeLocalHandle = if (activeRemote == null) current?.mHandle else null,
            status = statusMessage.ifBlank { if (snapshot == null) "Fleet status is unavailable" else "" }
        )
    }

    private fun openRemote(row: DrawerRemoteSession, surface: DrawerSessionSurface) {
        if (!row.available || state.value.busySessionId != null) return
        store.recordOpened(row.session, surface)
        rebuild()
        val host = service ?: return showError("The terminal service is still starting")
        val existing = host.getAgentFleetWorkspaceSession(row.session.id)
        if (existing != null) {
            activity.activateAgentFleetSession(row.session, surface)
            return
        }
        state.value = state.value.copy(busySessionId = row.session.id)
        runCatching { runtime.startWorkspaceSession(row.session) }.onFailure {
            state.value = state.value.copy(busySessionId = null)
            showError(it.message ?: "The session could not open")
            return
        }
        waitForAttachment(row.session, surface, 0)
    }

    private fun waitForAttachment(session: FleetSession, surface: DrawerSessionSurface, attempt: Int) {
        main.postDelayed({
            if (destroyed || state.value.busySessionId != session.id) return@postDelayed
            if (service?.getAgentFleetWorkspaceSession(session.id) != null) {
                state.value = state.value.copy(busySessionId = null)
                activity.activateAgentFleetSession(session, surface)
            } else if (attempt >= ATTACH_ATTEMPTS) {
                state.value = state.value.copy(busySessionId = null)
                showError("The local terminal attachment did not start. Try again.")
            } else {
                waitForAttachment(session, surface, attempt + 1)
            }
        }, if (attempt == 0) 50 else 100)
    }

    private fun togglePin(row: DrawerRemoteSession) {
        store.setPinned(row.session, !row.pinned)
        rebuild()
    }

    private fun killRemote(row: DrawerRemoteSession) {
        val current = snapshot ?: return refreshNow()
        val session = current.sessions.firstOrNull { it.id == row.session.id } ?: run {
            removeRemote(row)
            return
        }
        if (!isFleetSessionAvailable(current, session) || state.value.busySessionId != null) return
        state.value = state.value.copy(busySessionId = session.id)
        executor.execute {
            val result = runCatching { runtime.killSession(current, session) }
            main.post {
                result.onSuccess { updated ->
                    store.remove(session.id)
                    recentStore.remove(session.id)
                    service?.finishAgentFleetWorkspaceSession(session.id)
                    snapshot = updated
                    FleetSnapshotStore.publish(updated)
                    state.value = state.value.copy(busySessionId = null)
                    rebuild()
                    Toast.makeText(activity, "Session stopped", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    state.value = state.value.copy(busySessionId = null)
                    showError(error.message ?: "The session could not be stopped")
                    refreshNow()
                }
            }
        }
    }

    private fun closeRemote(row: DrawerRemoteSession) {
        if (service?.currentAgentFleetSessionId == row.session.id) activity.closeAgentFleetSessionTab()
        else service?.finishAgentFleetWorkspaceSession(row.session.id)
        rebuild()
    }

    private fun removeRemote(row: DrawerRemoteSession) {
        if (!fleetReachable) locallyRemovedOfflineIds += row.session.id
        store.remove(row.session.id)
        recentStore.remove(row.session.id)
        rebuild()
    }

    private fun openAgentFleetSession(row: DrawerRemoteSession) {
        activity.startActivity(Intent(activity, AgentFleetActivity::class.java).apply {
            putExtra(AgentFleetContract.EXTRA_FOCUS_SESSION_ID, row.session.id)
        })
        activity.drawer.closeDrawers()
    }

    private fun openLocal(row: DrawerLocalSession) {
        val session = findLocal(row.handle) ?: return
        activity.activateClassicSession(session.terminalSession)
    }

    private fun renameLocal(row: DrawerLocalSession) {
        findLocal(row.handle)?.terminalSession?.let(activity.termuxTerminalSessionClient::renameSession)
    }

    private fun closeLocal(row: DrawerLocalSession) {
        val session = findLocal(row.handle) ?: return
        if (session.terminalSession.isRunning) session.killIfExecuting(activity, true) else session.finish()
        rebuild()
    }

    private fun findLocal(handle: String): TermuxSession? =
        service?.classicTermuxSessions?.firstOrNull { it.terminalSession.mHandle == handle }

    private fun createLocal(failsafe: Boolean, name: String?) {
        activity.termuxTerminalSessionClient.addNewSession(failsafe, name)
        rebuild()
    }

    private fun openAgentFleet() {
        activity.startActivity(Intent(activity, AgentFleetActivity::class.java))
        activity.drawer.closeDrawers()
    }

    private fun toggleKeyboard() {
        activity.termuxTerminalViewClient.onToggleSoftKeyboardRequest()
        activity.drawer.closeDrawers()
    }

    private fun openAppearance() {
        activity.startActivity(Intent(activity, TerminalAppearanceActivity::class.java))
        activity.drawer.closeDrawers()
    }

    private fun showError(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    override fun onDrawerOpened(drawerView: android.view.View) {
        if (drawerView !== composeView) return
        activity.currentFocus?.let { focused ->
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
        state.value = state.value.copy(drawerOpen = true)
        beginObserving()
        rebuild()
    }

    override fun onDrawerClosed(drawerView: android.view.View) {
        if (drawerView !== composeView) return
        state.value = state.value.copy(drawerOpen = false)
        endObserving()
    }

    override fun onDrawerSlide(drawerView: android.view.View, slideOffset: Float) = Unit
    override fun onDrawerStateChanged(newState: Int) = Unit

    companion object {
        private const val REFRESH_COOLDOWN_MS = 15_000L
        private const val ATTACH_ATTEMPTS = 50
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedTerminalDrawer(
    state: UnifiedDrawerState,
    onOpenRemote: (DrawerRemoteSession, DrawerSessionSurface) -> Unit,
    onTogglePin: (DrawerRemoteSession) -> Unit,
    onKillRemote: (DrawerRemoteSession) -> Unit,
    onCloseRemote: (DrawerRemoteSession) -> Unit,
    onRemoveRemote: (DrawerRemoteSession) -> Unit,
    onOpenAgentFleetSession: (DrawerRemoteSession) -> Unit,
    onRefresh: () -> Unit,
    onCloseLocal: (DrawerLocalSession) -> Unit,
    onCreateLocal: (Boolean, String?) -> Unit,
    onOpenAgentFleet: () -> Unit,
    onKeyboard: () -> Unit,
    onAppearance: () -> Unit
) {
    val density by AgentFleetDisplayDensityStore.observe(LocalContext.current).collectAsState()
    var query by remember { mutableStateOf("") }
    var actionSessionId by remember { mutableStateOf<String?>(null) }
    var confirmKillId by remember { mutableStateOf<String?>(null) }
    var confirmLocalHandle by remember { mutableStateOf<String?>(null) }
    var showLocalChooser by remember { mutableStateOf(false) }
    var showNamedLocal by remember { mutableStateOf(false) }
    var localName by remember { mutableStateOf("") }
    val combinedCount = state.remoteSessions.size
    val needle = query.trim()
    val remote = state.remoteSessions.filter { row ->
        needle.isEmpty() || listOf(
            row.session.name, row.session.title, row.session.project, row.session.tool,
            state.hostNames[row.session.hostId].orEmpty(), row.session.hostId
        ).any { it.contains(needle, ignoreCase = true) }
    }
    val local = emptyList<DrawerLocalSession>()
    val listState = rememberLazyListState()

    LaunchedEffect(state.drawerOpen, state.activeRemoteId, state.activeLocalHandle, remote.size, local.size, query) {
        if (!state.drawerOpen) return@LaunchedEffect
        val remoteIndex = remote.indexOfFirst { it.session.id == state.activeRemoteId }
        val localIndex = local.indexOfFirst { it.handle == state.activeLocalHandle }
        val target = when {
            remoteIndex >= 0 -> 1 + remoteIndex
            localIndex >= 0 -> 2 + remote.size + localIndex
            else -> -1
        }
        if (target >= 0) listState.animateScrollToItem(target)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxHeight()) {
            Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars))
            if (combinedCount > 8) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(80) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp).testTag("drawer-search"),
                    placeholder = { Text("Find a session", fontSize = density.drawerMetadataSp.sp) },
                    singleLine = true
                )
            }
            if (state.status.isNotBlank()) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onRefresh).padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(state.status, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    Text("Refresh", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            LazyColumn(Modifier.weight(1f), state = listState) {
                item("remote-heading") { DrawerSectionHeading("Fleet sessions", remote.size) }
                items(remote, key = { "remote-${it.session.id}" }) { row ->
                    RemoteDrawerRow(
                        row = row,
                        hostName = state.hostNames[row.session.hostId].orEmpty().ifBlank { row.session.hostId },
                        active = row.session.id == state.activeRemoteId,
                        busy = row.session.id == state.busySessionId,
                        onOpen = { onOpenRemote(row, row.surface) },
                        onPin = { onTogglePin(row) },
                        onRevealKill = { confirmKillId = row.session.id },
                        onMore = { actionSessionId = row.session.id },
                        density = density
                    )
                }
                if (remote.isEmpty()) item("empty") {
                    Text("No matching sessions", Modifier.padding(24.dp), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 2.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                DrawerFooterAction("AF", "Agent Fleet", onOpenAgentFleet, Modifier.weight(1f).testTag("drawer-agent-fleet"))
                DrawerFooterAction("⌨", "Keyboard", onKeyboard, Modifier.weight(1f))
                DrawerFooterAction("Aa", "Appearance", onAppearance, Modifier.weight(1f))
            }
        }
    }

    val actionRow = state.remoteSessions.firstOrNull { it.session.id == actionSessionId }
    if (actionRow != null) {
        ModalBottomSheet(onDismissRequest = { actionSessionId = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
                Text(sessionIdentityPresentation(actionRow.session).primary, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                DrawerSheetAction("Open Native") { actionSessionId = null; onOpenRemote(actionRow, DrawerSessionSurface.Native) }
                DrawerSheetAction("Open Terminal") { actionSessionId = null; onOpenRemote(actionRow, DrawerSessionSurface.Terminal) }
                DrawerSheetAction(if (actionRow.pinned) "Unfavorite" else "Favorite") { actionSessionId = null; onTogglePin(actionRow) }
                if (actionRow.session.id in state.attachedSessionIds) {
                    DrawerSheetAction("Close on phone") { actionSessionId = null; onCloseRemote(actionRow) }
                }
                if (actionRow.available) {
                    DrawerSheetAction("Kill session", destructive = true) { actionSessionId = null; confirmKillId = actionRow.session.id }
                } else {
                    DrawerSheetAction("Refresh") { actionSessionId = null; onRefresh() }
                    DrawerSheetAction("Remove from phone") { actionSessionId = null; onRemoveRemote(actionRow) }
                }
                DrawerSheetAction("More in Agent Fleet") { actionSessionId = null; onOpenAgentFleetSession(actionRow) }
            }
        }
    }

    state.remoteSessions.firstOrNull { it.session.id == confirmKillId }?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmKillId = null },
            title = { Text("Kill ${row.session.name}?") },
            text = { Text("This ends the remote tmux session for everyone. Closing it on this phone is available from More.") },
            confirmButton = {
                Button(
                    onClick = { confirmKillId = null; onKillRemote(row) },
                    modifier = Modifier.testTag("drawer-confirm-kill")
                ) { Text("Kill") }
            },
            dismissButton = { TextButton(onClick = { confirmKillId = null }) { Text("Cancel") } }
        )
    }

    state.localSessions.firstOrNull { it.handle == confirmLocalHandle }?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmLocalHandle = null },
            title = { Text("Close local terminal?") },
            text = { Text(row.name.ifBlank { row.title.ifBlank { "Its running shell will be stopped." } }) },
            confirmButton = {
                Button(
                    onClick = { confirmLocalHandle = null; onCloseLocal(row) },
                    modifier = Modifier.testTag("drawer-confirm-local-close")
                ) { Text("Close") }
            },
            dismissButton = { TextButton(onClick = { confirmLocalHandle = null }) { Text("Cancel") } }
        )
    }

    if (showLocalChooser) {
        AlertDialog(
            onDismissRequest = { showLocalChooser = false },
            title = { Text("New local terminal") },
            text = {
                Column {
                    DrawerSheetAction("Normal shell") { showLocalChooser = false; onCreateLocal(false, null) }
                    DrawerSheetAction("Named shell") { showLocalChooser = false; localName = ""; showNamedLocal = true }
                    DrawerSheetAction("Failsafe shell") { showLocalChooser = false; onCreateLocal(true, null) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showLocalChooser = false }) { Text("Cancel") } }
        )
    }
    if (showNamedLocal) {
        AlertDialog(
            onDismissRequest = { showNamedLocal = false },
            title = { Text("Name local terminal") },
            text = {
                OutlinedTextField(localName, { localName = it.filterNot(Char::isISOControl).take(64) }, singleLine = true, label = { Text("Name") })
            },
            confirmButton = {
                Button(enabled = localName.isNotBlank(), onClick = {
                    showNamedLocal = false
                    onCreateLocal(false, localName.trim())
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNamedLocal = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DrawerSectionHeading(label: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 6.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(count.toString(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RemoteDrawerRow(
    row: DrawerRemoteSession,
    hostName: String,
    active: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onRevealKill: () -> Unit,
    onMore: () -> Unit,
    density: AgentFleetDisplayDensity
) {
    SwipeRevealRow(
        key = row.session.id,
        rightLabel = if (row.pinned) "Unpin" else "Pin",
        leftLabel = "Kill",
        leftEnabled = row.available,
        onRight = onPin,
        onLeftAction = onRevealKill
    ) {
        val identity = sessionIdentityPresentation(row.session)
        val borderColor = if (active) MaterialTheme.colorScheme.primary else Color.Transparent
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp)
                .heightIn(min = density.drawerRowHeightDp.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .clickable(enabled = row.available && !busy, onClick = onOpen)
                .alpha(if (row.available) 1f else 0.62f)
                .padding(start = 8.dp)
                .testTag("drawer-session-${row.session.id}"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(Modifier.size(30.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(contentAlignment = Alignment.Center) {
                    Text(row.session.tool.take(1).uppercase().ifBlank { "›" }, fontSize = density.drawerMetadataSp.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.pinned) Text("★ ", color = MaterialTheme.colorScheme.primary, fontSize = density.drawerMetadataSp.sp)
                    Text(identity.primary, Modifier.weight(1f), fontSize = density.drawerTitleSp.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val location = if (row.session.nameMode != "manual" && row.session.title.isNotBlank()) {
                    listOf(row.session.name, hostName, row.session.project).filter(String::isNotBlank).joinToString(" · ")
                } else listOf(hostName, row.session.project).filter(String::isNotBlank).joinToString(" · ")
                Text(
                    if (row.available) location else "Offline · ${location.ifBlank { "last known" }}",
                    fontSize = density.drawerMetadataSp.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (busy) Text("…", fontSize = density.drawerTitleSp.sp, modifier = Modifier.padding(horizontal = 4.dp))
            TextButton(
                onClick = onMore,
                modifier = Modifier.size(44.dp).testTag("drawer-session-more-${row.session.id}"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) { Text("⋮", fontSize = (density.drawerTitleSp + 4).sp) }
        }
    }
}

@Composable
private fun LocalDrawerRow(
    row: DrawerLocalSession,
    active: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onMore: () -> Unit
) {
    SwipeRevealRow(row.handle, rightLabel = "", leftLabel = "Close", leftEnabled = true, onRight = {}, onLeftAction = onClose) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                .border(2.dp, if (active) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(16.dp))
                .clickable(onClick = onOpen)
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp)
                .testTag("drawer-local-${row.handle}"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(">_", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(row.name.ifBlank { "Local shell" }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.title.isNotBlank()) Text(row.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onMore) { Text("⋮", fontSize = 23.sp) }
        }
    }
}

@Composable
private fun SwipeRevealRow(
    key: String,
    rightLabel: String,
    leftLabel: String,
    leftEnabled: Boolean,
    onRight: () -> Unit,
    onLeftAction: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val actionWidth = with(density) { 88.dp.toPx() }
    var offset by remember(key) { mutableStateOf(0f) }
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        if (rightLabel.isNotBlank() && offset > 1f) {
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primaryContainer).padding(start = 18.dp), contentAlignment = Alignment.CenterStart) {
                Text(rightLabel, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (leftEnabled && offset < -1f) {
            Box(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(88.dp)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable(onClick = onLeftAction)
                    .testTag("drawer-swipe-action-$key"),
                contentAlignment = Alignment.Center
            ) { Text(leftLabel, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) }
        }
        Box(
            Modifier.offset { IntOffset(offset.roundToInt(), 0) }
                .pointerInput(key, leftEnabled) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            offset = (offset + amount).coerceIn(if (leftEnabled) -actionWidth else 0f, actionWidth)
                        },
                        onDragEnd = {
                            when {
                                offset > actionWidth * 0.55f && rightLabel.isNotBlank() -> { offset = 0f; onRight() }
                                offset < -actionWidth * 0.4f && leftEnabled -> offset = -actionWidth
                                else -> offset = 0f
                            }
                        },
                        onDragCancel = { offset = 0f }
                    )
                }
        ) { content() }
    }
}

@Composable
private fun DrawerFooterAction(glyph: String, label: String, onClick: () -> Unit, modifier: Modifier) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(glyph, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(label, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun DrawerSheetAction(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(
            label,
            Modifier.fillMaxWidth(),
            fontSize = 17.sp,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}
