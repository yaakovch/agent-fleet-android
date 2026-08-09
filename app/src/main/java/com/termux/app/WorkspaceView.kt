package com.termux.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import com.termux.app.fleet.AgentFleetContract
import com.termux.app.fleet.AgentFleetComposer
import com.termux.app.fleet.AgentFleetComposerUploadOwner
import com.termux.app.fleet.AgentFleetCameraStaging
import com.termux.app.fleet.AgentFleetUploadCancellation
import com.termux.app.fleet.AndroidWorkspaceState
import com.termux.app.fleet.FleetSession
import com.termux.app.fleet.FleetSnapshot
import com.termux.app.fleet.NativeSessionController
import com.termux.app.fleet.NativeSessionHost
import com.termux.app.fleet.NativeSessionLifecycleBinding
import com.termux.app.fleet.ManagedNativeSessionLease
import com.termux.app.fleet.NativeSessionRegistry
import com.termux.app.fleet.RetainedNativeSession
import com.termux.app.fleet.RetainedNativeSessionFactory
import com.termux.app.fleet.TerminalScrollbackController
import com.termux.app.fleet.WorkspaceDirection
import com.termux.app.fleet.WorkspaceNode
import com.termux.app.fleet.WorkspacePane
import com.termux.app.fleet.WorkspacePreset
import com.termux.app.fleet.WorkspaceReducer
import com.termux.app.fleet.WorkspaceSplit
import com.termux.app.fleet.WorkspaceTerminalBroker
import com.termux.app.fleet.WorkspaceTerminalViewClient
import com.termux.app.fleet.WorkspaceViewMode
import com.termux.app.fleet.agentFleetWorkspaceTarget
import com.termux.app.fleet.agentFleetWorkspaceImageCapacity
import com.termux.app.fleet.isFleetSessionAvailable
import com.termux.app.fleet.sessionIdentityPresentation
import com.termux.app.fleet.workspacePanes
import com.termux.app.fleet.workspacePaneChrome
import com.termux.app.fleet.buildAgentFleetComposerText
import com.termux.shared.terminal.TermuxTerminalSessionClientBase
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.io.File
import java.util.Locale
import java.util.UUID

internal val LocalWorkspaceNativeSessionRegistry =
    staticCompositionLocalOf<NativeSessionRegistry<WorkspaceRetainedNativeSession>?> { null }

internal class WorkspaceNativeSessionState(
    val target: String
) {
    var attachments by mutableStateOf<List<String>>(emptyList())
    var attachmentUploading by mutableStateOf(false)
    var uploadCancellation: AgentFleetUploadCancellation? = null
    val uploadOwner = AgentFleetComposerUploadOwner()

    fun cancelPendingUpload() {
        uploadCancellation?.cancel()
        uploadCancellation = null
        uploadOwner.invalidate(target)
        attachmentUploading = false
    }
}

internal class WorkspaceRetainedNativeSession(
    val composeView: ComposeView,
    private val controller: NativeSessionController,
    val state: WorkspaceNativeSessionState
) : RetainedNativeSession {
    override fun onStart() = controller.onStart()

    override fun onStop() = controller.onStop()

    fun setSuggestionFocus(focused: Boolean) = controller.setSuggestionFocus(focused)

    internal fun suggestionFocusedForTest(): Boolean = controller.suggestionFocusedForTest()

    override fun close() {
        state.cancelPendingUpload()
        controller.close()
    }
}

private class WorkspaceNativeSessionStateHolder(
    var value: WorkspaceNativeSessionState
)

@Composable
fun DesktopNavigationRail(section: FleetSection, onSection: (FleetSection) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(
            Modifier.width(88.dp).fillMaxHeight().padding(vertical = 14.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("AF", fontSize = 20.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(vertical = 8.dp))
            FleetSection.values().forEach { item ->
                val selected = section == item
                if (selected) Button(
                    onClick = { onSection(item) },
                    modifier = Modifier.fillMaxWidth()
                        .semantics {
                            contentDescription = item.label
                            role = Role.Tab
                            this.selected = true
                            stateDescription = "Selected"
                        }
                        .testTag("desktop-nav-${item.label.lowercase(Locale.US)}"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp, horizontal = 4.dp)
                ) { Text(item.glyph, fontSize = 17.sp) }
                else TextButton(
                    onClick = { onSection(item) },
                    modifier = Modifier.fillMaxWidth()
                        .semantics {
                            contentDescription = item.label
                            role = Role.Tab
                            this.selected = false
                            stateDescription = "Not selected"
                        }
                        .testTag("desktop-nav-${item.label.lowercase(Locale.US)}")
                ) { Text(item.glyph, fontSize = 17.sp) }
                Text(item.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun DesktopWorkspaceScreen(
    snapshot: FleetSnapshot?,
    sessions: List<FleetSession>,
    phoneSessionId: String? = null,
    state: AndroidWorkspaceState,
    broker: WorkspaceTerminalBroker,
    onStateChange: (AndroidWorkspaceState) -> Unit,
    onMoreSession: (FleetSession, String?) -> Unit,
    onRefresh: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val hosts = snapshot?.physicalHosts?.associateBy { it.id }.orEmpty()
    val filtered = sessions.filter { session ->
        query.isBlank() || listOf(session.name, session.title, session.project, session.tool, hosts[session.physicalHostId]?.name.orEmpty())
            .any { it.contains(query.trim(), ignoreCase = true) }
    }
    val panes = workspacePanes(state.layout.root)
    val focusedPane = panes.firstOrNull { it.id == state.layout.focusedPaneId } ?: panes.first()
    val focusedSession = focusedPane.sessionId?.let { id ->
        sessions.firstOrNull { it.id == id } ?: snapshot?.sessions?.firstOrNull { it.id == id }
    }
    val focusedAvailable = focusedSession != null && snapshot?.let { isFleetSessionAvailable(it, focusedSession) } == true
    val focusedBinding = focusedSession?.let { broker.state(it.id).value }
    val focusedChrome = workspacePaneChrome(
        focusedPane, focusedSession, focusedAvailable,
        focusedBinding?.status ?: "connecting", focusedBinding?.message ?: "Opening session…"
    )
    val paneNumbers = panes.mapIndexed { index, pane -> pane.id to index + 1 }.toMap()
    val paneBounds = remember { mutableStateMapOf<String, Rect>() }
    var draggedPaneId by remember { mutableStateOf<String?>(null) }
    var dragPointer by remember { mutableStateOf<Offset?>(null) }
    var emptyPaneMenu by remember(focusedPane.id) { mutableStateOf(false) }
    val dragTargetPaneId = dragPointer?.let { pointer ->
        paneBounds.entries.firstOrNull { it.value.contains(pointer) }?.key
    }
    val closePane: (String) -> Unit = { paneId ->
        workspacePanes(state.layout.root).firstOrNull { it.id == paneId }?.sessionId?.let(broker::detach)
        onStateChange(state.copy(layout = WorkspaceReducer.close(state.layout, paneId)))
    }

    LaunchedEffect(snapshot?.revision, state.layout) {
        if (snapshot == null) {
            broker.setActiveSessions(emptyList())
            return@LaunchedEffect
        }
        var layout = state.layout
        var changed = false
        if (workspacePanes(layout.root).all { it.sessionId == null }) {
            val handoff = phoneSessionId?.let { id -> snapshot.sessions.firstOrNull { it.id == id } }
            if (handoff != null && isFleetSessionAvailable(snapshot, handoff)) {
                layout = WorkspaceReducer.assign(layout, layout.focusedPaneId, handoff.id)
                broker.attach(handoff)
                changed = true
            }
        }
        val activeSessions = workspacePanes(layout.root).mapNotNull { pane ->
            pane.sessionId?.let { id -> snapshot.sessions.firstOrNull { it.id == id } }
                ?.takeIf { isFleetSessionAvailable(snapshot, it) }
        }
        broker.setActiveSessions(activeSessions)
        workspacePanes(layout.root).forEach { pane ->
            val id = pane.sessionId ?: return@forEach
            val session = snapshot.sessions.firstOrNull { it.id == id }
            if (session != null && isFleetSessionAvailable(snapshot, session)) {
                broker.attach(session)
            } else if (session != null) {
                broker.detach(id)
            } else {
                val owner = id.substringBefore(':')
                val authoritative = snapshot.hosts.firstOrNull { it.id == owner }?.status == "healthy"
                if (authoritative) {
                    broker.detach(id)
                    layout = WorkspaceReducer.clear(layout, pane.id)
                    changed = true
                }
            }
        }
        if (changed) onStateChange(state.copy(layout = layout))
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (!state.railCollapsed) {
            Surface(Modifier.width(state.railWidthDp.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sessions", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("${sessions.size} available", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onStateChange(state.copy(railCollapsed = true)) }) { Text("Hide") }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().testTag("desktop-session-search"),
                        placeholder = { Text("Search") },
                        singleLine = true
                    )
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (snapshot == null) item {
                            TextButton(onClick = onRefresh) { Text("Refresh fleet") }
                        }
                        items(filtered, key = { it.id }) { session ->
                            val available = snapshot?.let { isFleetSessionAvailable(it, session) } == true
                            val assigned = workspacePanes(state.layout.root).any { it.sessionId == session.id }
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (assigned) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(sessionIdentityPresentation(session).primary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                if (available) sessionIdentityPresentation(session).secondary else "Unavailable",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                        TextButton(
                                            onClick = { onMoreSession(session, null) },
                                            modifier = Modifier.semantics { contentDescription = "More actions for ${session.name}" }
                                        ) { Text("•••") }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(enabled = available, onClick = {
                                            val current = workspacePanes(state.layout.root).firstOrNull { it.id == state.layout.focusedPaneId }?.sessionId
                                            if (current != null && current != session.id) broker.detach(current)
                                            onStateChange(state.copy(layout = WorkspaceReducer.assign(state.layout, state.layout.focusedPaneId, session.id)))
                                        }) { Text(if (assigned) "Focus" else "Open") }
                                        TextButton(enabled = available && workspacePanes(state.layout.root).size < 4, onClick = {
                                            var next = WorkspaceReducer.split(state.layout, state.layout.focusedPaneId, WorkspaceDirection.Row)
                                            next = WorkspaceReducer.assign(next, next.focusedPaneId, session.id)
                                            onStateChange(state.copy(layout = next))
                                        }, modifier = Modifier.semantics { contentDescription = "Open ${session.name} in a new pane to the right" }) { Text("Split →") }
                                        TextButton(enabled = available && workspacePanes(state.layout.root).size < 4, onClick = {
                                            var next = WorkspaceReducer.split(state.layout, state.layout.focusedPaneId, WorkspaceDirection.Column)
                                            next = WorkspaceReducer.assign(next, next.focusedPaneId, session.id)
                                            onStateChange(state.copy(layout = next))
                                        }, modifier = Modifier.semantics { contentDescription = "Open ${session.name} in a new pane below" }) { Text("Split ↓") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Surface(Modifier.width(48.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
                TextButton(
                    onClick = { onStateChange(state.copy(railCollapsed = false)) },
                    modifier = Modifier.semantics { contentDescription = "Expand session rail" }
                ) { Text("›", fontSize = 24.sp) }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WorkspaceFocusedIdentity(
                    number = paneNumbers[focusedPane.id] ?: 1,
                    title = focusedChrome.title,
                    context = focusedChrome.context,
                    status = focusedChrome.status
                )
                OutlinedButton(
                    enabled = focusedChrome.nativeEnabled,
                    onClick = { onStateChange(state.copy(layout = WorkspaceReducer.setView(state.layout, focusedPane.id, WorkspaceViewMode.Native))) },
                    modifier = Modifier.semantics {
                        role = Role.Tab
                        selected = focusedPane.viewMode == WorkspaceViewMode.Native
                        stateDescription = if (focusedPane.viewMode == WorkspaceViewMode.Native) "Selected" else "Not selected"
                    }.testTag("workspace-mode-native")
                ) { Text("Native") }
                OutlinedButton(
                    enabled = focusedChrome.terminalEnabled,
                    onClick = { onStateChange(state.copy(layout = WorkspaceReducer.setView(state.layout, focusedPane.id, WorkspaceViewMode.Terminal))) },
                    modifier = Modifier.semantics {
                        role = Role.Tab
                        selected = focusedPane.viewMode == WorkspaceViewMode.Terminal
                        stateDescription = if (focusedPane.viewMode == WorkspaceViewMode.Terminal) "Selected" else "Not selected"
                    }.testTag("workspace-mode-terminal")
                ) { Text("Terminal") }
                Box(Modifier.width(72.dp)) {
                    if (focusedChrome.retryVisible && focusedSession != null) OutlinedButton(
                        onClick = { broker.attach(focusedSession) },
                        modifier = Modifier.testTag("workspace-retry")
                    ) { Text("Retry") }
                }
                Box {
                    TextButton(
                        onClick = {
                            if (focusedSession != null) onMoreSession(focusedSession, focusedPane.id)
                            else emptyPaneMenu = true
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "More actions for focused pane ${paneNumbers[focusedPane.id] ?: 1}"
                        }.testTag("workspace-more")
                    ) { Text("•••") }
                }
                OutlinedButton(
                    enabled = panes.size < 4,
                    onClick = { onStateChange(state.copy(layout = WorkspaceReducer.split(state.layout, focusedPane.id, WorkspaceDirection.Row))) },
                    modifier = Modifier.semantics { contentDescription = "Split focused pane to the right" }
                        .testTag("workspace-split-right")
                ) { Text("Split →") }
                OutlinedButton(
                    enabled = panes.size < 4,
                    onClick = { onStateChange(state.copy(layout = WorkspaceReducer.split(state.layout, focusedPane.id, WorkspaceDirection.Column))) },
                    modifier = Modifier.semantics { contentDescription = "Split focused pane below" }
                        .testTag("workspace-split-down")
                ) { Text("Split ↓") }
                WorkspacePreset.values().forEach { preset ->
                    OutlinedButton(onClick = {
                        val old = workspacePanes(state.layout.root).mapNotNull { it.sessionId }.toSet()
                        val layout = WorkspaceReducer.preset(state.layout, preset)
                        val retained = workspacePanes(layout.root).mapNotNull { it.sessionId }.toSet()
                        (old - retained).forEach(broker::detach)
                        onStateChange(state.copy(layout = layout))
                    }, modifier = Modifier
                        .semantics { contentDescription = "Use ${presetDescription(preset)} layout" }
                        .testTag("workspace-preset-${preset.name.lowercase(Locale.US)}")) { Text(presetLabel(preset)) }
                }
            }
            if (emptyPaneMenu) AlertDialog(
                onDismissRequest = { emptyPaneMenu = false },
                title = { Text("Empty pane") },
                text = { Text("Close this pane and keep every session running?") },
                confirmButton = {
                    TextButton(onClick = { emptyPaneMenu = false; closePane(focusedPane.id) }) { Text("Close pane") }
                },
                dismissButton = { TextButton(onClick = { emptyPaneMenu = false }) { Text("Cancel") } }
            )
            WorkspaceTree(
                node = state.layout.root,
                focusedPaneId = state.layout.focusedPaneId,
                snapshot = snapshot,
                sessions = sessions,
                broker = broker,
                modifier = Modifier.weight(1f),
                paneNumbers = paneNumbers,
                dragTargetPaneId = dragTargetPaneId,
                onPaneBounds = { pane, bounds -> paneBounds[pane] = bounds },
                onPaneDragStart = { pane, pointer -> draggedPaneId = pane; dragPointer = pointer },
                onPaneDrag = { pointer -> dragPointer = pointer },
                onPaneDragEnd = {
                    val source = draggedPaneId
                    val target = dragPointer?.let { pointer -> paneBounds.entries.firstOrNull { it.value.contains(pointer) }?.key }
                    if (source != null && target != null && source != target) {
                        onStateChange(state.copy(layout = WorkspaceReducer.swap(state.layout, source, target)))
                    }
                    draggedPaneId = null
                    dragPointer = null
                },
                onFocus = { onStateChange(state.copy(layout = WorkspaceReducer.focus(state.layout, it))) },
                onView = { pane, mode -> onStateChange(state.copy(layout = WorkspaceReducer.setView(state.layout, pane, mode))) },
                onClose = closePane,
                onSwap = { source, target ->
                    onStateChange(state.copy(layout = WorkspaceReducer.swap(state.layout, source, target)))
                },
                onResize = { split, ratio -> onStateChange(state.copy(layout = WorkspaceReducer.resize(state.layout, split, ratio))) }
            )
        }
    }
}

@Composable
private fun WorkspaceTree(
    node: WorkspaceNode,
    focusedPaneId: String,
    snapshot: FleetSnapshot?,
    sessions: List<FleetSession>,
    broker: WorkspaceTerminalBroker,
    modifier: Modifier,
    paneNumbers: Map<String, Int>,
    dragTargetPaneId: String?,
    onPaneBounds: (String, Rect) -> Unit,
    onPaneDragStart: (String, Offset) -> Unit,
    onPaneDrag: (Offset) -> Unit,
    onPaneDragEnd: () -> Unit,
    onFocus: (String) -> Unit,
    onView: (String, WorkspaceViewMode) -> Unit,
    onClose: (String) -> Unit,
    onSwap: (String, String) -> Unit,
    onResize: (String, Float) -> Unit
) {
    when (node) {
        is WorkspacePane -> WorkspacePaneView(
            node, paneNumbers[node.id] ?: 1, node.id == focusedPaneId, node.id == dragTargetPaneId,
            snapshot, sessions, broker, modifier, paneNumbers, onPaneBounds, onPaneDragStart, onPaneDrag, onPaneDragEnd,
            onFocus, onView, onClose, onSwap
        )
        is WorkspaceSplit -> {
            val horizontal = node.direction == WorkspaceDirection.Row
            if (horizontal) Row(modifier) {
                WorkspaceTree(node.first, focusedPaneId, snapshot, sessions, broker, Modifier.weight(node.ratio).fillMaxHeight(), paneNumbers, dragTargetPaneId, onPaneBounds, onPaneDragStart, onPaneDrag, onPaneDragEnd, onFocus, onView, onClose, onSwap, onResize)
                SplitHandle(node, horizontal = true, onResize)
                WorkspaceTree(node.second, focusedPaneId, snapshot, sessions, broker, Modifier.weight(1f - node.ratio).fillMaxHeight(), paneNumbers, dragTargetPaneId, onPaneBounds, onPaneDragStart, onPaneDrag, onPaneDragEnd, onFocus, onView, onClose, onSwap, onResize)
            } else Column(modifier) {
                WorkspaceTree(node.first, focusedPaneId, snapshot, sessions, broker, Modifier.weight(node.ratio).fillMaxWidth(), paneNumbers, dragTargetPaneId, onPaneBounds, onPaneDragStart, onPaneDrag, onPaneDragEnd, onFocus, onView, onClose, onSwap, onResize)
                SplitHandle(node, horizontal = false, onResize)
                WorkspaceTree(node.second, focusedPaneId, snapshot, sessions, broker, Modifier.weight(1f - node.ratio).fillMaxWidth(), paneNumbers, dragTargetPaneId, onPaneBounds, onPaneDragStart, onPaneDrag, onPaneDragEnd, onFocus, onView, onClose, onSwap, onResize)
            }
        }
    }
}

@Composable
private fun SplitHandle(split: WorkspaceSplit, horizontal: Boolean, onResize: (String, Float) -> Unit) {
    val resize: (Float) -> Unit = { ratio -> onResize(split.id, ratio.coerceIn(0.2f, 0.8f)) }
    val modifier = if (horizontal) Modifier.width(48.dp).fillMaxHeight() else Modifier.height(48.dp).fillMaxWidth()
    Box(modifier
        .semantics {
            contentDescription = if (horizontal) "Resize panes left and right" else "Resize panes above and below"
            stateDescription = "First pane ${(split.ratio * 100).toInt()} percent"
            progressBarRangeInfo = ProgressBarRangeInfo(split.ratio, 0.2f..0.8f, 11)
            setProgress { value -> resize(value); true }
            customActions = listOf(
                CustomAccessibilityAction("Decrease first pane") { resize(split.ratio - 0.05f); true },
                CustomAccessibilityAction("Increase first pane") { resize(split.ratio + 0.05f); true },
                CustomAccessibilityAction("Center divider") { resize(0.5f); true }
            )
        }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionLeft, Key.DirectionUp -> { resize(split.ratio - 0.05f); true }
                Key.DirectionRight, Key.DirectionDown -> { resize(split.ratio + 0.05f); true }
                else -> false
            }
        }
        .focusable()
        .testTag("workspace-divider-${split.id}")
        .background(MaterialTheme.colorScheme.background)
        .pointerInput(split.id, split.ratio) {
        var ratio = split.ratio
        detectDragGestures { change, drag ->
            change.consume()
            ratio = (ratio + if (horizontal) drag.x / 900f else drag.y / 700f).coerceIn(0.2f, 0.8f)
            resize(ratio)
        }
    }, contentAlignment = Alignment.Center) {
        Box(
            (if (horizontal) Modifier.width(2.dp).fillMaxHeight() else Modifier.height(2.dp).fillMaxWidth())
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

@Composable
private fun WorkspacePaneView(
    pane: WorkspacePane,
    number: Int,
    focused: Boolean,
    dragTarget: Boolean,
    snapshot: FleetSnapshot?,
    sessions: List<FleetSession>,
    broker: WorkspaceTerminalBroker,
    modifier: Modifier,
    paneNumbers: Map<String, Int>,
    onPaneBounds: (String, Rect) -> Unit,
    onPaneDragStart: (String, Offset) -> Unit,
    onPaneDrag: (Offset) -> Unit,
    onPaneDragEnd: () -> Unit,
    onFocus: (String) -> Unit,
    onView: (String, WorkspaceViewMode) -> Unit,
    onClose: (String) -> Unit,
    onSwap: (String, String) -> Unit
) {
    val session = pane.sessionId?.let { id -> sessions.firstOrNull { it.id == id } ?: snapshot?.sessions?.firstOrNull { it.id == id } }
    val available = session != null && snapshot?.let { isFleetSessionAvailable(it, session) } == true
    val binding = session?.let { broker.state(it.id).value }
    val chrome = workspacePaneChrome(
        pane, session, available, binding?.status ?: "connecting", binding?.message ?: "Opening session…"
    )
    val borderColor = when {
        dragTarget -> MaterialTheme.colorScheme.tertiary
        focused -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        modifier = modifier
            .padding(2.dp)
            .onGloballyPositioned { onPaneBounds(pane.id, it.boundsInRoot()) }
            .pointerInput(pane.id, focused) {
                if (!focused) awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { !it.previousPressed && it.pressed }) onFocus(pane.id)
                    }
                }
            }
            .border(if (focused || dragTarget) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .semantics {
                contentDescription = "Pane $number, ${chrome.title}"
                selected = focused
                stateDescription = "${chrome.status}, ${chrome.modeBadge}"
            }
            .testTag("workspace-pane-${pane.id}"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(Modifier.fillMaxSize()) {
            when {
                session == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (pane.sessionId == null) "Choose a session from the rail" else "Opening session…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                !available -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Host unavailable", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("This last-known session will reconnect or become ended after the host returns.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                chrome.opening -> Box(Modifier.fillMaxSize().padding(top = 36.dp), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Opening session…", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(binding?.message ?: "Preparing local attachment", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                binding?.status == "error" -> Box(Modifier.fillMaxSize().padding(top = 36.dp), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Local attachment unavailable", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(binding.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Use Retry in the focused-pane toolbar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                pane.viewMode == WorkspaceViewMode.Terminal -> EmbeddedTerminal(
                    session, broker, Modifier.fillMaxSize().padding(top = 36.dp)
                )
                else -> key(agentFleetWorkspaceTarget(session)) {
                    EmbeddedNative(
                        pane.id,
                        session,
                        broker,
                        focused,
                        Modifier.fillMaxSize().padding(top = 36.dp),
                        onTerminal = { onView(pane.id, WorkspaceViewMode.Terminal) },
                        onClose = { onClose(pane.id) }
                    )
                }
            }
            WorkspacePaneTitleChip(
                pane = pane,
                number = number,
                title = chrome.title,
                status = chrome.status,
                modeBadge = chrome.modeBadge,
                focused = focused,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 5.dp).zIndex(2f),
                onFocus = onFocus,
                swapTargets = paneNumbers.filterKeys { it != pane.id },
                onSwap = onSwap,
                onDragStart = onPaneDragStart,
                onDrag = onPaneDrag,
                onDragEnd = onPaneDragEnd
            )
        }
    }
}

@Composable
private fun WorkspacePaneTitleChip(
    pane: WorkspacePane,
    number: Int,
    title: String,
    status: String,
    modeBadge: String,
    focused: Boolean,
    modifier: Modifier,
    onFocus: (String) -> Unit,
    swapTargets: Map<String, Int>,
    onSwap: (String, String) -> Unit,
    onDragStart: (String, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    var origin by remember(pane.id) { mutableStateOf(Offset.Zero) }
    OutlinedButton(
        onClick = { onFocus(pane.id) },
        modifier = modifier
            .widthIn(min = 112.dp, max = 300.dp)
            .height(48.dp)
            .onGloballyPositioned { origin = it.localToRoot(Offset.Zero) }
            .pointerInput(pane.id) {
                detectDragGestures(
                    onDragStart = { onDragStart(pane.id, origin + it) },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd
                ) { change, _ ->
                    change.consume()
                    onDrag(origin + change.position)
                }
            }
            .semantics {
                contentDescription = "Pane $number, $title"
                selected = focused
                stateDescription = "$status, $modeBadge"
                customActions = swapTargets.entries.sortedBy { it.value }.map { (target, targetNumber) ->
                    CustomAccessibilityAction("Swap with pane $targetNumber") { onSwap(pane.id, target); true }
                }
            }
            .testTag("workspace-pane-chip-${pane.id}"),
        border = BorderStroke(1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(9.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(number.toString(), fontSize = 10.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(5.dp))
        WorkspaceStatusDot(status)
        Spacer(Modifier.width(5.dp))
        Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
        Text(modeBadge, fontSize = 10.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun WorkspaceFocusedIdentity(number: Int, title: String, context: String, status: String) {
    Row(
        Modifier.widthIn(min = 170.dp, max = 310.dp).testTag("workspace-focused-identity"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(number.toString(), fontSize = 12.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        WorkspaceStatusDot(status)
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(context, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WorkspaceStatusDot(status: String) {
    val color = when (status) {
        "live" -> Color(0xFF51D5AA)
        "connecting", "reconnecting" -> Color(0xFFFFC65C)
        "offline", "ended", "error" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Box(
        Modifier.size(8.dp)
            .semantics { contentDescription = "Status ${status.replaceFirstChar { it.uppercase() }}" }
            .background(color, CircleShape)
    )
}

@Composable
private fun EmbeddedTerminal(session: FleetSession, broker: WorkspaceTerminalBroker, modifier: Modifier) {
    val binding by broker.state(session.id)
    LaunchedEffect(session.id) { broker.attach(session) }
    val context = LocalContext.current
    val scrollback = remember(session.id) { TerminalScrollbackController(context.applicationContext) }
    LaunchedEffect(session.id, session.hostId, session.internalName, session.tool) {
        scrollback.bind(
            session.hostId,
            session.internalName,
            session.tool in setOf("codex", "claude", "copilot")
        )
    }
    DisposableEffect(scrollback) {
        scrollback.onStart()
        onDispose { scrollback.close() }
    }
    var view by remember(session.id) { mutableStateOf<TerminalView?>(null) }
    val observer = remember(session.id, binding.terminal) {
        object : TermuxTerminalSessionClientBase() {
            override fun onTextChanged(changedSession: TerminalSession) {
                scrollback.onTerminalActivity()
                scrollback.onTerminalScreenChanged(changedSession.emulator?.isAlternateBufferActive == true)
                view?.post { view?.onScreenUpdated() }
            }
            override fun onColorsChanged(changedSession: TerminalSession) { view?.post { view?.onScreenUpdated() } }
            override fun onSessionFinished(finishedSession: TerminalSession) { view?.post { view?.onScreenUpdated() } }
            override fun onCopyTextToClipboard(terminal: TerminalSession, text: String) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Terminal text", text))
            }
            override fun onPasteTextFromClipboard(terminal: TerminalSession) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                if (text.isNotEmpty()) broker.write(session.id, text)
            }
        }
    }
    DisposableEffect(binding.terminal, observer) {
        binding.terminal?.addTerminalSessionObserver(observer)
        onDispose { binding.terminal?.removeTerminalSessionObserver(observer) }
    }
    val terminal = binding.terminal
    LaunchedEffect(terminal) {
        if (terminal == null) scrollback.setTerminalView(null)
    }
    if (terminal == null) Box(modifier, contentAlignment = Alignment.Center) {
        Text(binding.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else Box(modifier.background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { androidContext ->
                TerminalView(androidContext, null).apply {
                    setTerminalViewClient(WorkspaceTerminalViewClient())
                    setTextSize(26)
                    setBackgroundColor(AndroidColor.BLACK)
                    isFocusableInTouchMode = true
                    view = this
                    scrollback.setTerminalView(this)
                }
            },
            update = { terminalView ->
                view = terminalView
                terminalView.attachSession(terminal)
                terminalView.updateSize()
                terminalView.onScreenUpdated()
                scrollback.onTerminalScreenChanged(terminal.emulator?.isAlternateBufferActive == true)
            }
        )
    }
}

@Composable
private fun EmbeddedNative(
    paneSlot: String,
    session: FleetSession,
    broker: WorkspaceTerminalBroker,
    focused: Boolean,
    modifier: Modifier,
    onTerminal: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val retainedRegistry = LocalWorkspaceNativeSessionRegistry.current
    val workspaceTarget = agentFleetWorkspaceTarget(session)
    LaunchedEffect(workspaceTarget) { broker.attach(session) }
    val transientState = remember(workspaceTarget) { WorkspaceNativeSessionState(workspaceTarget) }
    val stateHolder = remember(workspaceTarget) { WorkspaceNativeSessionStateHolder(transientState) }
    val cameraPath = rememberSaveable(workspaceTarget) { mutableStateOf<String?>(null) }
    val currentSession by rememberUpdatedState(session)
    val currentBroker by rememberUpdatedState(broker)
    val currentContext by rememberUpdatedState(context)
    val currentOnTerminal by rememberUpdatedState(onTerminal)
    val currentOnClose by rememberUpdatedState(onClose)
    val uploadImages: (List<android.net.Uri>, File?) -> Unit = { uris, ownedCameraFile ->
        val uploadState = stateHolder.value
        val capacity = agentFleetWorkspaceImageCapacity(uploadState.attachments.size)
        if (uris.isEmpty() || uploadState.attachmentUploading || capacity == 0) {
            ownedCameraFile?.delete()
        } else {
            val ticket = uploadState.uploadOwner.begin(workspaceTarget)
            val cancellation = AgentFleetUploadCancellation()
            uploadState.uploadCancellation = cancellation
            uploadState.attachmentUploading = true
            AgentFleetComposer.uploadWorkspaceImages(
                currentContext.applicationContext,
                uris,
                currentSession,
                capacity,
                cancellation
            ) { result ->
                ownedCameraFile?.delete()
                if (!uploadState.uploadOwner.finish(ticket)) return@uploadWorkspaceImages
                uploadState.uploadCancellation = null
                uploadState.attachments = (uploadState.attachments + result.uploadedPaths).distinct().take(8)
                if (!result.cancelled) result.error?.let { error ->
                    Toast.makeText(
                        currentContext,
                        error.message ?: "Image upload failed. Refresh the session and retry.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                uploadState.attachmentUploading = false
            }
        }
    }
    val currentUploadImages by rememberUpdatedState(uploadImages)
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        currentUploadImages(uris, null)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val source = runCatching {
            AgentFleetCameraStaging.restore(currentContext.cacheDir, cameraPath.value)
        }.getOrNull()
        val uri = source?.let {
            runCatching {
                FileProvider.getUriForFile(
                    currentContext,
                    "${currentContext.packageName}.agentfleet.images",
                    it
                )
            }.getOrNull()
        }
        cameraPath.value = null
        if (captured && source != null && uri != null) currentUploadImages(listOf(uri), source) else source?.delete()
    }
    val host = remember(workspaceTarget, imagePicker, camera) {
        object : NativeSessionHost {
            override val nativeContext: Context = context.applicationContext
            override val nativeInlineComposer: Boolean = true

            override fun sendAgentFleetComposerText(text: String, appendEnter: Boolean): Boolean {
                val uploadState = stateHolder.value
                val activeSession = currentSession
                val composed = buildAgentFleetComposerText(text, uploadState.attachments)
                val sent = currentBroker.write(activeSession.id, composed + if (appendEnter) "\r" else "")
                if (sent) uploadState.attachments = emptyList()
                return sent
            }

            override fun sendAgentFleetControlC(): Boolean = currentBroker.key(currentSession.id, "CTRL_C")

            override fun sendAgentFleetKey(key: String): Boolean = currentBroker.key(currentSession.id, key)

            override fun pickAgentFleetImages() {
                if (stateHolder.value.attachmentUploading) {
                    Toast.makeText(currentContext, "An image upload is already in progress.", Toast.LENGTH_SHORT).show()
                } else {
                    imagePicker.launch("image/*")
                }
            }

            override fun pickAgentFleetCamera() {
                if (stateHolder.value.attachmentUploading) {
                    Toast.makeText(currentContext, "An image upload is already in progress.", Toast.LENGTH_SHORT).show()
                    return
                }
                val directory = File(currentContext.cacheDir, "agent-fleet-camera").apply { mkdirs() }
                if (!directory.isDirectory) {
                    Toast.makeText(currentContext, "Camera staging is unavailable.", Toast.LENGTH_LONG).show()
                    return
                }
                directory.listFiles()?.asSequence()?.filter {
                    it.isFile && it.lastModified() < System.currentTimeMillis() - 24L * 60L * 60L * 1_000L
                }?.take(64)?.forEach(File::delete)
                val source = File(directory, "${UUID.randomUUID()}.jpg")
                try {
                    val uri = FileProvider.getUriForFile(
                        currentContext,
                        "${currentContext.packageName}.agentfleet.images",
                        source
                    )
                    cameraPath.value = source.absolutePath
                    camera.launch(uri)
                } catch (_: RuntimeException) {
                    if (cameraPath.value == source.absolutePath) cameraPath.value = null
                    source.delete()
                    Toast.makeText(
                        currentContext,
                        "Camera could not be opened. Retry or choose an existing image.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun setAgentFleetNativeView(
                nativeAvailable: Boolean,
                nativeView: Boolean,
                automaticTerminal: Boolean,
                aiComposer: Boolean
            ) {
                if (nativeAvailable && !nativeView) currentOnTerminal()
            }

            override fun closeAgentFleetSessionTab() {
                currentBroker.detach(currentSession.id)
                currentOnClose()
            }
        }
    }
    val retainedLease: ManagedNativeSessionLease<WorkspaceRetainedNativeSession>? = if (retainedRegistry == null) {
        null
    } else {
        remember(retainedRegistry, paneSlot, workspaceTarget, host) {
            retainedRegistry.manage(
                paneSlot,
                workspaceTarget,
                host,
                RetainedNativeSessionFactory { target, forwardingHost ->
                    val retainedState = WorkspaceNativeSessionState(target)
                    val composeView = ComposeView(context)
                    val controller = NativeSessionController(
                        forwardingHost,
                        composeView,
                        showChrome = false,
                        retainCompositionAcrossDetach = true
                    ).also { it.bind(agentFleetWorkspaceNativeIntent(currentSession)) }
                    WorkspaceRetainedNativeSession(composeView, controller, retainedState)
                },
                beforeStart = { it.setSuggestionFocus(focused) }
            )
        }
    }
    stateHolder.value = retainedLease?.session?.state ?: transientState

    if (retainedLease != null) {
        LaunchedEffect(retainedLease, focused) {
            retainedLease.session.setSuggestionFocus(focused)
        }
        AndroidView(
            modifier = modifier,
            factory = {
                retainedLease.session.composeView.apply {
                    (parent as? ViewGroup)?.removeView(this)
                }
            }
        )
    } else {
        val lifecycleBinding = remember(workspaceTarget) { NativeSessionLifecycleBinding() }
        var transientController by remember(workspaceTarget) { mutableStateOf<NativeSessionController?>(null) }
        LaunchedEffect(transientController, focused) {
            transientController?.setSuggestionFocus(focused)
        }
        DisposableEffect(workspaceTarget, lifecycleOwner, lifecycleBinding, transientState) {
            lifecycleOwner.lifecycle.addObserver(lifecycleBinding)
            onDispose {
                transientState.cancelPendingUpload()
                lifecycleOwner.lifecycle.removeObserver(lifecycleBinding)
                lifecycleBinding.detach()
                transientController = null
            }
        }
        AndroidView(
            modifier = modifier,
            factory = { androidContext ->
                ComposeView(androidContext).also { composeView ->
                    NativeSessionController(
                        host,
                        composeView,
                        showChrome = false
                    ).also { native ->
                        native.bind(agentFleetWorkspaceNativeIntent(currentSession))
                        native.setSuggestionFocus(focused)
                        transientController = native
                        lifecycleBinding.attach(
                            lifecycleOwner.lifecycle.currentState,
                            start = native::onStart,
                            stop = native::onStop,
                            close = native::close
                        )
                    }
                }
            }
        )
    }
}

private fun agentFleetWorkspaceNativeIntent(session: FleetSession): Intent = Intent().apply {
    putExtra(AgentFleetContract.EXTRA_COMPOSE_INPUT, AgentFleetContract.supportsComposerInput(session.tool))
    putExtra(AgentFleetContract.EXTRA_NATIVE_SESSION, true)
    putExtra(AgentFleetContract.EXTRA_HOST_ID, session.hostId)
    putExtra(AgentFleetContract.EXTRA_PROJECT, session.project)
    putExtra(AgentFleetContract.EXTRA_INTERNAL_SESSION, session.internalName)
    putExtra(AgentFleetContract.EXTRA_SESSION_NAME, sessionIdentityPresentation(session).primary)
}

private fun presetLabel(preset: WorkspacePreset): String = when (preset) {
    WorkspacePreset.Single -> "1"
    WorkspacePreset.TwoColumns -> "2 columns"
    WorkspacePreset.TwoRows -> "2 rows"
    WorkspacePreset.MainSide -> "Main + side"
    WorkspacePreset.Grid -> "4 grid"
}

private fun presetDescription(preset: WorkspacePreset): String = when (preset) {
    WorkspacePreset.Single -> "single pane"
    WorkspacePreset.TwoColumns -> "two column"
    WorkspacePreset.TwoRows -> "two row"
    WorkspacePreset.MainSide -> "main and side"
    WorkspacePreset.Grid -> "four pane grid"
}
