package com.termux.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.app.fleet.FleetHost
import com.termux.app.fleet.FleetLoadState
import com.termux.app.fleet.FleetRuntime
import com.termux.app.fleet.FleetSession
import com.termux.app.fleet.FleetSnapshot
import com.termux.app.fleet.FleetUnavailableException
import java.util.concurrent.Executors

class AgentFleetActivity : ComponentActivity() {
    private val fleetState = mutableStateOf<FleetLoadState>(FleetLoadState.Loading)
    private val fleetExecutor = Executors.newSingleThreadExecutor()
    private lateinit var fleetRuntime: FleetRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fleetRuntime = FleetRuntime(applicationContext)
        setContent {
            AgentFleetTheme {
                AgentFleetApp(
                    fleetState = fleetState.value,
                    onRefresh = ::refreshFleet,
                    onOpenSession = ::openFleetSession,
                    onRenameSession = ::renameFleetSession,
                    onScheduleContinue = ::scheduleContinue,
                    onKillSession = ::killFleetSession,
                    onCopyAttachCommand = ::copyAttachCommand,
                    onOpenClassicTerminal = {
                        startActivity(Intent(this, TermuxActivity::class.java))
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshFleet()
    }

    override fun onDestroy() {
        fleetExecutor.shutdownNow()
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
            fleetRuntime.openSession(session)
        } catch (error: FleetUnavailableException) {
            Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun renameFleetSession(session: FleetSession, name: String) = mutateFleet("Session renamed") { snapshot ->
        fleetRuntime.renameSession(snapshot, session, name)
    }

    private fun scheduleContinue(session: FleetSession, delayMs: Long) = mutateFleet("Continue scheduled") { snapshot ->
        fleetRuntime.scheduleContinue(snapshot, session, System.currentTimeMillis() + delayMs)
    }

    private fun killFleetSession(session: FleetSession) = mutateFleet("Session stopped") { snapshot ->
        fleetRuntime.killSession(snapshot, session)
    }

    private fun copyAttachCommand(session: FleetSession) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("wtmux attach command", fleetRuntime.attachCommand(session)))
        Toast.makeText(this, "Attach command copied", Toast.LENGTH_SHORT).show()
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

enum class FleetSection(val label: String, val glyph: String) {
    Sessions("Sessions", "▣"),
    Terminal("Terminal", ">_"),
    Limits("Limits", "%"),
    More("More", "•••")
}

data class LimitFixture(
    val label: String,
    val fiveHourRemaining: Int,
    val weeklyRemaining: Int,
    val reset: String,
    val status: String
)

object AgentFleetFixtures {
    val limits = listOf(
        LimitFixture("Codex 2", 76, 42, "5h resets 14:05", "Ready"),
        LimitFixture("Codex 3", 0, 68, "Available again 02:05", "Limited"),
        LimitFixture("Claude Code", 34, 57, "5h resets 16:40", "Ready")
    )
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
    onRefresh: () -> Unit,
    onOpenSession: (FleetSession) -> Unit,
    onRenameSession: (FleetSession, String) -> Unit,
    onScheduleContinue: (FleetSession, Long) -> Unit,
    onKillSession: (FleetSession) -> Unit,
    onCopyAttachCommand: (FleetSession) -> Unit,
    onOpenClassicTerminal: () -> Unit
) {
    var section by rememberSaveable { mutableStateOf(FleetSection.Sessions) }
    var actionSession by rememberSaveable { mutableStateOf<String?>(null) }
    var renameSession by rememberSaveable { mutableStateOf<String?>(null) }
    var scheduleSession by rememberSaveable { mutableStateOf<String?>(null) }
    var killSession by rememberSaveable { mutableStateOf<String?>(null) }
    val currentSnapshot = (fleetState as? FleetLoadState.Ready)?.snapshot
    val sessionsById = currentSnapshot?.sessions?.associateBy { it.id }.orEmpty()

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
            FleetSection.Sessions -> SessionsScreen(padding, fleetState, onRefresh, onOpenSession, { actionSession = it.id }, onOpenClassicTerminal)
            FleetSection.Terminal -> TerminalScreen(padding, onOpenClassicTerminal)
            FleetSection.Limits -> LimitsScreen(padding)
            FleetSection.More -> MoreScreen(padding)
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
}

@Composable
private fun SessionsScreen(
    padding: PaddingValues,
    fleetState: FleetLoadState,
    onRefresh: () -> Unit,
    onOpenSession: (FleetSession) -> Unit,
    onMoreSession: (FleetSession) -> Unit,
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
                Button(onClick = onOpenTerminal, shape = RoundedCornerShape(14.dp)) { Text("New", fontSize = 16.sp) }
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
                FleetUnavailableCard(fleetState.reason, onRefresh, onOpenTerminal)
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(hostName) })
                AssistChip(onClick = {}, label = { Text(session.tool) })
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
private fun FleetUnavailableCard(reason: String, onRefresh: () -> Unit, onOpenTerminal: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Fleet is not connected", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(reason, fontSize = 16.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRefresh, shape = RoundedCornerShape(14.dp)) { Text("Try again", fontSize = 16.sp) }
                OutlinedButton(onClick = onOpenTerminal, shape = RoundedCornerShape(14.dp)) { Text("Open terminal", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun TerminalScreen(padding: PaddingValues, onOpenClassicTerminal: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).testTag("terminal-screen"),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Terminal workspace", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Persistent local and remote tabs will live here.", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = TerminalNavy)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(ReadyGreen)
                        Spacer(Modifier.size(10.dp))
                        Text("wtmux · Android companion", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("$ codex\n› Continue building the mobile shell_", color = Color(0xFFD8E3FF), fontSize = 17.sp, lineHeight = 25.sp)
                    Button(onClick = onOpenClassicTerminal, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Text("Open Classic Terminal", fontSize = 17.sp)
                    }
                }
            }
        }
        item {
            FeatureCard("Android Compose input", "Multiline editing, voice input, image attachments, and an explicit Send action.")
        }
        item {
            FeatureCard("Classic Terminal input", "Direct PTY keys, extra rows, modifiers, gestures, hardware keyboard, and mouse.")
        }
    }
}

@Composable
private fun LimitsScreen(padding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).testTag("limits-screen"),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("AI limits", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Trusted host sources · refreshed just now", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = WarningAmber.copy(alpha = 0.13f)), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Codex 3 reached its 5h limit", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Gaming desktop · available again at 02:05", fontSize = 16.sp)
                    Button(onClick = {}, shape = RoundedCornerShape(14.dp)) { Text("Schedule Continue · 02:06") }
                }
            }
        }
        items(AgentFleetFixtures.limits, key = { it.label }) { limit -> LimitCard(limit) }
    }
}

@Composable
private fun LimitCard(limit: LimitFixture) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(limit.label, modifier = Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(limit.status, color = if (limit.status == "Ready") ReadyGreen else WarningAmber, fontWeight = FontWeight.Bold)
            }
            LimitBar("5h", limit.fiveHourRemaining)
            LimitBar("Weekly", limit.weeklyRemaining)
            Text(limit.reset, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
        }
    }
}

@Composable
private fun LimitBar(label: String, remaining: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.size(width = 58.dp, height = 24.dp), fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(
            progress = remaining / 100f,
            modifier = Modifier.weight(1f).height(9.dp),
            color = if (remaining == 0) WarningAmber else ReadyGreen,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Text("$remaining%", modifier = Modifier.padding(start = 10.dp), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MoreScreen(padding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).testTag("more-screen"),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Text("More", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { FeatureCard("Schedules", "2 pending · guarded delivery runs on the destination host") }
        item { FeatureCard("Fleet health", "2 of 2 hosts healthy · registry synced 1 minute ago") }
        item { FeatureCard("Pair or restore", "Scan an invitation or verify restored wtmux state") }
        item { FeatureCard("Terminal appearance", "System theme · 16sp · Android Compose for AI") }
        item { FeatureCard("Diagnostics", "Runtime, bridge, package, transport, and update checks") }
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
private val TerminalNavy = Color(0xFF11182A)

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
            onRefresh = {},
            onOpenSession = {},
            onRenameSession = { _, _ -> },
            onScheduleContinue = { _, _ -> },
            onKillSession = {},
            onCopyAttachCommand = {},
            onOpenClassicTerminal = {}
        )
    }
}
