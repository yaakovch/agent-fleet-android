package com.termux.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.app.fleet.AgentFleetContract
import com.termux.app.fleet.AgentFleetComposer
import com.termux.app.fleet.AndroidWorkspaceState
import com.termux.app.fleet.FleetSession
import com.termux.app.fleet.FleetSnapshot
import com.termux.app.fleet.NativeSessionController
import com.termux.app.fleet.NativeSessionHost
import com.termux.app.fleet.WorkspaceDirection
import com.termux.app.fleet.WorkspaceNode
import com.termux.app.fleet.WorkspacePane
import com.termux.app.fleet.WorkspacePreset
import com.termux.app.fleet.WorkspaceReducer
import com.termux.app.fleet.WorkspaceSplit
import com.termux.app.fleet.WorkspaceTerminalBroker
import com.termux.app.fleet.WorkspaceTerminalViewClient
import com.termux.app.fleet.WorkspaceViewMode
import com.termux.app.fleet.isFleetSessionAvailable
import com.termux.app.fleet.workspacePanes
import com.termux.app.fleet.buildAgentFleetComposerText
import com.termux.shared.terminal.TermuxTerminalSessionClientBase
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.util.Locale

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
                    modifier = Modifier.fillMaxWidth().testTag("desktop-nav-${item.label.lowercase(Locale.US)}"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp, horizontal = 4.dp)
                ) { Text(item.glyph, fontSize = 17.sp) }
                else TextButton(
                    onClick = { onSection(item) },
                    modifier = Modifier.fillMaxWidth().testTag("desktop-nav-${item.label.lowercase(Locale.US)}")
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
    onMoreSession: (FleetSession) -> Unit,
    onRefresh: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val hosts = snapshot?.hosts?.associateBy { it.id }.orEmpty()
    val filtered = sessions.filter { session ->
        query.isBlank() || listOf(session.name, session.project, session.tool, hosts[session.hostId]?.name.orEmpty())
            .any { it.contains(query.trim(), ignoreCase = true) }
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
                                            Text(session.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                if (available) "${hosts[session.hostId]?.name ?: session.hostId} · ${session.tool}" else "Unavailable",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                        TextButton(onClick = { onMoreSession(session) }) { Text("•••") }
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
                                        }) { Text("Split →") }
                                        TextButton(enabled = available && workspacePanes(state.layout.root).size < 4, onClick = {
                                            var next = WorkspaceReducer.split(state.layout, state.layout.focusedPaneId, WorkspaceDirection.Column)
                                            next = WorkspaceReducer.assign(next, next.focusedPaneId, session.id)
                                            onStateChange(state.copy(layout = next))
                                        }) { Text("Split ↓") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Surface(Modifier.width(48.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
                TextButton(onClick = { onStateChange(state.copy(railCollapsed = false)) }) { Text("›", fontSize = 24.sp) }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Workspace", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                WorkspacePreset.values().forEach { preset ->
                    OutlinedButton(onClick = {
                        val old = workspacePanes(state.layout.root).mapNotNull { it.sessionId }.toSet()
                        val layout = WorkspaceReducer.preset(state.layout, preset)
                        val retained = workspacePanes(layout.root).mapNotNull { it.sessionId }.toSet()
                        (old - retained).forEach(broker::detach)
                        onStateChange(state.copy(layout = layout))
                    }) { Text(presetLabel(preset)) }
                }
            }
            WorkspaceTree(
                node = state.layout.root,
                focusedPaneId = state.layout.focusedPaneId,
                snapshot = snapshot,
                sessions = sessions,
                broker = broker,
                modifier = Modifier.weight(1f),
                onFocus = { onStateChange(state.copy(layout = WorkspaceReducer.focus(state.layout, it))) },
                onView = { pane, mode -> onStateChange(state.copy(layout = WorkspaceReducer.setView(state.layout, pane, mode))) },
                onSplit = { pane, direction -> onStateChange(state.copy(layout = WorkspaceReducer.split(state.layout, pane, direction))) },
                onClose = { pane ->
                    workspacePanes(state.layout.root).firstOrNull { it.id == pane }?.sessionId?.let(broker::detach)
                    onStateChange(state.copy(layout = WorkspaceReducer.close(state.layout, pane)))
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
    onFocus: (String) -> Unit,
    onView: (String, WorkspaceViewMode) -> Unit,
    onSplit: (String, WorkspaceDirection) -> Unit,
    onClose: (String) -> Unit,
    onResize: (String, Float) -> Unit
) {
    when (node) {
        is WorkspacePane -> WorkspacePaneView(
            node, node.id == focusedPaneId, snapshot, sessions, broker, modifier,
            onFocus, onView, onSplit, onClose
        )
        is WorkspaceSplit -> {
            val horizontal = node.direction == WorkspaceDirection.Row
            if (horizontal) Row(modifier) {
                WorkspaceTree(node.first, focusedPaneId, snapshot, sessions, broker, Modifier.weight(node.ratio).fillMaxHeight(), onFocus, onView, onSplit, onClose, onResize)
                SplitHandle(node, horizontal = true, onResize)
                WorkspaceTree(node.second, focusedPaneId, snapshot, sessions, broker, Modifier.weight(1f - node.ratio).fillMaxHeight(), onFocus, onView, onSplit, onClose, onResize)
            } else Column(modifier) {
                WorkspaceTree(node.first, focusedPaneId, snapshot, sessions, broker, Modifier.weight(node.ratio).fillMaxWidth(), onFocus, onView, onSplit, onClose, onResize)
                SplitHandle(node, horizontal = false, onResize)
                WorkspaceTree(node.second, focusedPaneId, snapshot, sessions, broker, Modifier.weight(1f - node.ratio).fillMaxWidth(), onFocus, onView, onSplit, onClose, onResize)
            }
        }
    }
}

@Composable
private fun SplitHandle(split: WorkspaceSplit, horizontal: Boolean, onResize: (String, Float) -> Unit) {
    val modifier = if (horizontal) Modifier.width(10.dp).fillMaxHeight() else Modifier.height(10.dp).fillMaxWidth()
    Box(modifier.background(MaterialTheme.colorScheme.background).pointerInput(split.id, split.ratio) {
        var ratio = split.ratio
        detectDragGestures { change, drag ->
            change.consume()
            ratio = (ratio + if (horizontal) drag.x / 900f else drag.y / 700f).coerceIn(0.2f, 0.8f)
            onResize(split.id, ratio)
        }
    })
}

@Composable
private fun WorkspacePaneView(
    pane: WorkspacePane,
    focused: Boolean,
    snapshot: FleetSnapshot?,
    sessions: List<FleetSession>,
    broker: WorkspaceTerminalBroker,
    modifier: Modifier,
    onFocus: (String) -> Unit,
    onView: (String, WorkspaceViewMode) -> Unit,
    onSplit: (String, WorkspaceDirection) -> Unit,
    onClose: (String) -> Unit
) {
    val session = pane.sessionId?.let { id -> sessions.firstOrNull { it.id == id } ?: snapshot?.sessions?.firstOrNull { it.id == id } }
    val available = session != null && snapshot?.let { isFleetSessionAvailable(it, session) } == true
    Surface(
        modifier = modifier
            .padding(2.dp)
            .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .testTag("workspace-pane-${pane.id}"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = { onFocus(pane.id) }, modifier = Modifier.weight(1f)) {
                    Text(session?.name ?: "Empty pane", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(enabled = available, onClick = { onView(pane.id, WorkspaceViewMode.Native) }) { Text("Native") }
                TextButton(enabled = available, onClick = { onView(pane.id, WorkspaceViewMode.Terminal) }) { Text("Terminal") }
                TextButton(onClick = { onSplit(pane.id, WorkspaceDirection.Row) }) { Text("↔") }
                TextButton(onClick = { onSplit(pane.id, WorkspaceDirection.Column) }) { Text("↕") }
                TextButton(onClick = { onClose(pane.id) }) { Text("×") }
            }
            when {
                session == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Choose a session from the rail", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                !available -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Host unavailable", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("This last-known session will reconnect or become ended after the host returns.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                pane.viewMode == WorkspaceViewMode.Terminal -> EmbeddedTerminal(session, broker, Modifier.weight(1f).fillMaxWidth())
                else -> EmbeddedNative(
                    session,
                    broker,
                    Modifier.weight(1f).fillMaxWidth(),
                    onTerminal = { onView(pane.id, WorkspaceViewMode.Terminal) },
                    onClose = { onClose(pane.id) }
                )
            }
        }
    }
}

@Composable
private fun EmbeddedTerminal(session: FleetSession, broker: WorkspaceTerminalBroker, modifier: Modifier) {
    val binding by broker.state(session.id)
    LaunchedEffect(session.id) { broker.attach(session) }
    val context = LocalContext.current
    var view by remember(session.id) { mutableStateOf<TerminalView?>(null) }
    val observer = remember(session.id, binding.terminal) {
        object : TermuxTerminalSessionClientBase() {
            override fun onTextChanged(changedSession: TerminalSession) { view?.post { view?.onScreenUpdated() } }
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
    if (terminal == null) Box(modifier, contentAlignment = Alignment.Center) {
        Text(binding.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { androidContext ->
            TerminalView(androidContext, null).apply {
                setTerminalViewClient(WorkspaceTerminalViewClient())
                setTextSize(26)
                setBackgroundColor(AndroidColor.BLACK)
                isFocusableInTouchMode = true
                view = this
            }
        },
        update = { terminalView ->
            view = terminalView
            terminalView.attachSession(terminal)
            terminalView.onScreenUpdated()
        }
    )
}

@Composable
private fun EmbeddedNative(
    session: FleetSession,
    broker: WorkspaceTerminalBroker,
    modifier: Modifier,
    onTerminal: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(session.id) { broker.attach(session) }
    var attachments by remember(session.id) { mutableStateOf<List<String>>(emptyList()) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            AgentFleetComposer.uploadWorkspaceImages(context.applicationContext, uris, session) { result ->
                result.onSuccess { paths -> attachments = (attachments + paths).distinct().take(8) }
            }
        }
    }
    var controller by remember(session.id) { mutableStateOf<NativeSessionController?>(null) }
    DisposableEffect(session.id) {
        onDispose { controller?.close(); controller = null }
    }
    AndroidView(
        modifier = modifier,
        factory = { androidContext ->
            androidx.compose.ui.platform.ComposeView(androidContext).also { composeView ->
                val host = object : NativeSessionHost {
                    override val nativeContext: Context = context.applicationContext
                    override val nativeInlineComposer: Boolean = true
                    override fun sendAgentFleetComposerText(text: String, appendEnter: Boolean): Boolean {
                        val composed = buildAgentFleetComposerText(text, attachments)
                        val sent = broker.write(session.id, composed + if (appendEnter) "\r" else "")
                        if (sent) attachments = emptyList()
                        return sent
                    }
                    override fun sendAgentFleetControlC(): Boolean = broker.key(session.id, "CTRL_C")
                    override fun sendAgentFleetKey(key: String): Boolean = broker.key(session.id, key)
                    override fun pickAgentFleetImages() { imagePicker.launch("image/*") }
                    override fun setAgentFleetNativeView(nativeAvailable: Boolean, nativeView: Boolean, automaticTerminal: Boolean, aiComposer: Boolean) {
                        if (nativeAvailable && !nativeView) onTerminal()
                    }
                    override fun closeAgentFleetSessionTab() { broker.detach(session.id); onClose() }
                }
                controller = NativeSessionController(host, composeView).also { native ->
                    native.bind(Intent().apply {
                        putExtra(AgentFleetContract.EXTRA_COMPOSE_INPUT, session.tool in setOf("codex", "claude", "copilot"))
                        putExtra(AgentFleetContract.EXTRA_NATIVE_SESSION, true)
                        putExtra(AgentFleetContract.EXTRA_HOST_ID, session.hostId)
                        putExtra(AgentFleetContract.EXTRA_PROJECT, session.project)
                        putExtra(AgentFleetContract.EXTRA_INTERNAL_SESSION, session.internalName)
                        putExtra(AgentFleetContract.EXTRA_SESSION_NAME, session.name)
                    })
                    native.onStart()
                }
            }
        }
    )
}

private fun presetLabel(preset: WorkspacePreset): String = when (preset) {
    WorkspacePreset.Single -> "1"
    WorkspacePreset.TwoColumns -> "2 columns"
    WorkspacePreset.TwoRows -> "2 rows"
    WorkspacePreset.MainSide -> "Main + side"
    WorkspacePreset.Grid -> "4 grid"
}
