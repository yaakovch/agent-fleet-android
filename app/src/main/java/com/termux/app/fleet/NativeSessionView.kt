package com.termux.app.fleet

import android.graphics.Color as AndroidColor
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.noties.markwon.Markwon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat

private val LocalAgentFleetDisplayDensity = staticCompositionLocalOf { AgentFleetDisplayDensity() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeSessionScreen(
    state: NativeSessionUiState,
    aiComposer: Boolean,
    onToggleTerminal: () -> Unit,
    onRetry: () -> Unit,
    onLoadOlder: () -> Unit,
    onApproval: (ConversationItem, ConversationChoice) -> Unit,
    onQuestion: (ConversationItem, List<ConversationAnswer>) -> Unit,
    onShellCommand: (String) -> Unit,
    onShellKey: (String) -> Unit,
    onDirectory: (String) -> Unit,
    onRefreshDirectory: () -> Unit,
    onControlC: () -> Unit,
    onCloseSession: () -> Unit,
    onKillSession: () -> Unit,
    onScheduleContinue: (Long) -> Unit,
    onDismissAttention: () -> Unit,
    onComposerText: (String, Boolean) -> Boolean = { _, _ -> false },
    onAttach: () -> Unit = {},
    inlineComposer: Boolean = false,
    showChrome: Boolean = true,
    applyStatusBarInset: Boolean = true,
    localSuggestionsAvailableOverride: Boolean? = null,
    localSuggestionModeOverride: LocalSuggestionMode? = null,
    localSuggestionDebugFakeOutput: String? = null,
    onRefreshModel: () -> Unit = {},
    onSetModel: (String, String, Boolean, Boolean) -> Unit = { _, _, _, _ -> },
    onCancelModel: () -> Unit = {}
) {
    val context = LocalContext.current
    val displayDensity by AgentFleetDisplayDensityStore.observe(context).collectAsState()
    val systemDensity = LocalDensity.current
    val nativeDensity = remember(systemDensity.density, systemDensity.fontScale, displayDensity.nativeBodySp) {
        Density(
            density = systemDensity.density,
            fontScale = systemDensity.fontScale * displayDensity.nativeBodySp / AgentFleetDisplayDensity.DEFAULT_NATIVE_BODY_SP
        )
    }
    val localSuggestions = remember(
        state.hostId, state.internalSession, localSuggestionsAvailableOverride,
        localSuggestionModeOverride, localSuggestionDebugFakeOutput
    ) {
        NativeLocalSuggestionState(
            context, localSuggestionsAvailableOverride, localSuggestionDebugFakeOutput,
            modeOverride = localSuggestionModeOverride
        )
    }
    DisposableEffect(localSuggestions) { onDispose { localSuggestions.close() } }
    LaunchedEffect(state.revision, state.liveEventSerial) { localSuggestions.clear() }
    var actionSheetId by rememberSaveable { mutableStateOf("") }
    var dismissedActionId by rememberSaveable { mutableStateOf("") }
    var feedNearBottom by remember { mutableStateOf(true) }
    var viewerOpen by remember { mutableStateOf(false) }
    val pendingAction = activePendingAction(state.items)
    val automaticQuestion = pendingAction?.takeIf { it.kind == "question" }
        ?.questions?.firstOrNull()?.takeIf { canSuggestForQuestion(it, "") }
    val automaticQuestionTarget = automaticQuestion?.let {
        LocalSuggestionTarget("question", pendingAction.id, it.id, it.prompt)
    }
    val automaticQuestionKey = automaticQuestionTarget?.let { localSuggestionRevision(state.items, it) } ?: ""
    var observedQuestionSerial by remember(state.hostId, state.internalSession) { mutableStateOf(state.liveEventSerial) }
    var observedQuestionKey by remember(state.hostId, state.internalSession) { mutableStateOf(automaticQuestionKey) }
    LaunchedEffect(state.revision) {
        observedQuestionSerial = state.liveEventSerial
        observedQuestionKey = automaticQuestionKey
    }
    LaunchedEffect(state.liveEventSerial) {
        val start = shouldStartAutomaticSuggestion(
            observedQuestionKey, automaticQuestionKey,
            localSuggestions.mode == LocalSuggestionMode.AUTOMATIC && state.liveEventSerial > observedQuestionSerial,
            historicalFrame = false
        )
        observedQuestionSerial = state.liveEventSerial
        observedQuestionKey = automaticQuestionKey
        if (start && automaticQuestionTarget != null) {
            localSuggestions.request(state.items, automaticQuestionTarget, automatic = true)
        }
    }
    LaunchedEffect(pendingAction?.id, feedNearBottom, viewerOpen, state.focusQuestionSerial) {
        if (pendingAction == null) {
            actionSheetId = ""
            dismissedActionId = ""
        } else if (state.focusQuestionSerial > 0 && pendingAction.id == state.focusQuestionId) {
            dismissedActionId = ""
            actionSheetId = pendingAction.id
        } else if (pendingAction.id != dismissedActionId && feedNearBottom && !viewerOpen) {
            actionSheetId = pendingAction.id
        }
    }
    CompositionLocalProvider(
        LocalAgentFleetDisplayDensity provides displayDensity,
        LocalDensity provides nativeDensity
    ) {
        Scaffold(
        modifier = Modifier.fillMaxSize().testTag("native-session-screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (showChrome) CompositionLocalProvider(LocalDensity provides systemDensity) {
                CompactSessionHeader(
                    state = state,
                    destinationLabel = "Terminal",
                    headerTag = "native-session-header",
                    modifier = if (applyStatusBarInset) {
                        Modifier.windowInsetsPadding(WindowInsets.statusBars)
                    } else {
                        Modifier
                    },
                    aiComposer = aiComposer,
                    onToggleView = onToggleTerminal,
                    onControlC = onControlC,
                    onShellKey = onShellKey,
                    onCloseSession = onCloseSession,
                    onKillSession = onKillSession,
                    onRefreshModel = onRefreshModel,
                    onSetModel = onSetModel,
                    onCancelModel = onCancelModel
                )
            }
        },
        bottomBar = {
            if (pendingAction != null) {
                Surface(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 5.dp
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { actionSheetId = pendingAction.id }.padding(horizontal = 12.dp, vertical = 7.dp).testTag("native-pending-action"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(pendingAction.title.ifBlank { if (pendingAction.kind == "question") "Answer needed" else "Approval needed" }, fontSize = AgentFleetDisplayDensity.DEFAULT_NATIVE_BODY_SP.sp, fontWeight = FontWeight.Bold)
                            Text("Tap to respond", fontSize = (AgentFleetDisplayDensity.DEFAULT_NATIVE_BODY_SP - 3).sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Open", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (state.sourceMode == "shell" && !aiComposer) {
                ShellCommandBar(onShellCommand, onShellKey, onControlC)
            } else if (aiComposer && inlineComposer) {
                NativeAiComposer(
                    state.interactionMode, state.items, state.revision, state.liveEventSerial,
                    localSuggestions, onComposerText, onAttach
                )
            }
        }
    ) { padding ->
        key(state.hostId, state.internalSession) {
            ConversationFeed(
                state = state,
                padding = padding,
                onRetry = onRetry,
                onLoadOlder = onLoadOlder,
                onApproval = onApproval,
                onQuestion = onQuestion,
                onOpenTerminal = onToggleTerminal,
                onDirectory = onDirectory,
                onRefreshDirectory = onRefreshDirectory,
                pinnedActionId = pendingAction?.id,
                onScheduleContinue = onScheduleContinue,
                onDismissAttention = onDismissAttention,
                onNearBottomChanged = { feedNearBottom = it },
                onViewerOpenChanged = { viewerOpen = it },
                localSuggestions = localSuggestions
            )
        }
        }
    if (pendingAction != null && actionSheetId == pendingAction.id) {
        Dialog(
            onDismissRequest = { actionSheetId = ""; dismissedActionId = pendingAction.id },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f),
                shape = RoundedCornerShape(22.dp),
                tonalElevation = 8.dp
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Action needed", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text("Complete this to continue", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { actionSheetId = ""; dismissedActionId = pendingAction.id }) { Text("Close") }
                    }
                    Box(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        ConversationItemCard(
                            pendingAction, onApproval, onQuestion, onToggleTerminal, onRetry,
                            onOpenTool = { _, _ -> }, onOpenPlan = {},
                            localSuggestions = localSuggestions, conversationItems = state.items
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
fun AgentFleetTerminalSessionChrome(
    state: NativeSessionUiState,
    onShowNative: () -> Unit,
    onRefreshModel: () -> Unit,
    onSetModel: (String, String, Boolean, Boolean) -> Unit,
    onCancelModel: () -> Unit,
    aiComposer: Boolean = state.sourceMode == "ai",
    onControlC: () -> Unit = {},
    onShellKey: (String) -> Unit = {},
    onCloseSession: () -> Unit = {},
    onKillSession: () -> Unit = {}
) {
    CompactSessionHeader(
        state = state,
        destinationLabel = "Native",
        headerTag = "terminal-session-chrome",
        aiComposer = aiComposer,
        onToggleView = onShowNative,
        onControlC = onControlC,
        onShellKey = onShellKey,
        onCloseSession = onCloseSession,
        onKillSession = onKillSession,
        onRefreshModel = onRefreshModel,
        onSetModel = onSetModel,
        onCancelModel = onCancelModel
    )
}

@Composable
private fun CompactSessionHeader(
    state: NativeSessionUiState,
    destinationLabel: String,
    headerTag: String,
    aiComposer: Boolean,
    onToggleView: () -> Unit,
    onControlC: () -> Unit,
    onShellKey: (String) -> Unit,
    onCloseSession: () -> Unit,
    onKillSession: () -> Unit,
    onRefreshModel: () -> Unit,
    onSetModel: (String, String, Boolean, Boolean) -> Unit,
    onCancelModel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var actionMenu by rememberSaveable(state.hostId, state.internalSession, destinationLabel) { mutableStateOf(false) }
    var confirmKill by rememberSaveable(state.hostId, state.internalSession, destinationLabel) { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(dimensionResource(com.termux.R.dimen.agent_fleet_compact_session_header_height))
            .testTag(headerTag),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 2.dp
    ) {
        Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 5.dp)) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).testTag("compact-session-identity-row"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.sessionLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    NativeSessionStatusLine(state)
                }
                Box {
                    TextButton(
                        onClick = { actionMenu = true },
                        modifier = Modifier.height(40.dp).testTag("compact-session-actions"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("Actions", fontSize = 11.sp) }
                    DropdownMenu(expanded = actionMenu, onDismissRequest = { actionMenu = false }) {
                        if (aiComposer) {
                            DropdownMenuItem(
                                text = { Text("Ctrl+C") },
                                onClick = { actionMenu = false; onControlC() }
                            )
                            DropdownMenuItem(
                                text = { Text("Shift+Tab") },
                                onClick = { actionMenu = false; onShellKey("SHIFT_TAB") }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Close this view") },
                            onClick = { actionMenu = false; onCloseSession() }
                        )
                        DropdownMenuItem(
                            text = { Text("Kill session", color = MaterialTheme.colorScheme.error) },
                            enabled = !state.attentionBusy,
                            onClick = { actionMenu = false; confirmKill = true }
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(48.dp).testTag("compact-session-control-row"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (state.sourceMode == "ai") {
                    SessionModelControlChip(
                        state = state,
                        onRefreshModel = onRefreshModel,
                        onSetModel = onSetModel,
                        onCancelModel = onCancelModel,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                OutlinedButton(
                    onClick = onToggleView,
                    modifier = Modifier.height(40.dp).testTag("compact-session-view-switch"),
                    shape = RoundedCornerShape(13.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)
                ) { Text(destinationLabel, fontSize = 11.sp) }
            }
        }
    }
    if (confirmKill) {
        AlertDialog(
            onDismissRequest = { confirmKill = false },
            title = { Text("Kill this session?") },
            text = { Text("This stops the tmux session and its running agent. Close this view instead if you want it to keep running.") },
            confirmButton = {
                Button(onClick = { confirmKill = false; onKillSession() }) { Text("Kill session") }
            },
            dismissButton = { TextButton(onClick = { confirmKill = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun NativeSessionStatusLine(state: NativeSessionUiState) {
    val providerWorkingStartedAt = remember(state.adapter, state.items) {
        activeWorkStartedAt(state.adapter, state.items)
    }
    val workingStartedAt = providerWorkingStartedAt ?: state.optimisticWorkStartedAt
    val completedDuration = remember(state.adapter, state.items) {
        latestCompletedWorkDuration(state.adapter, state.items)
    }
    var nowMillis by remember(workingStartedAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(workingStartedAt) {
        while (workingStartedAt != null) {
            nowMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val detail = when {
        workingStartedAt != null -> "Working (${formatWorkingDuration(workingStartedAt, nowMillis)})"
        completedDuration != null -> "Worked for ${formatElapsedDuration(completedDuration)}"
        else -> state.connection
    }
    Text(
        "${prettyAdapter(state.adapter)} · $detail",
        fontSize = 10.sp,
        color = if (workingStartedAt != null || completedDuration != null || state.connection == "Live") ReadyGreen else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("native-session-status")
    )
}

@Composable
private fun SessionModelControlChip(
    state: NativeSessionUiState,
    onRefreshModel: () -> Unit,
    onSetModel: (String, String, Boolean, Boolean) -> Unit,
    onCancelModel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var open by rememberSaveable(state.hostId, state.internalSession) { mutableStateOf(false) }
    val control = state.modelControl
    val pending = control?.pending
    val selection = control?.effective ?: control?.selected
    val label = when {
        pending != null -> "${pending.modelId} · ${pending.effortId} · queued"
        selection != null -> "${selection.modelLabel} · ${selection.effortLabel}"
        else -> "Model · Effort"
    }
    AssistChip(
        onClick = { open = true; onRefreshModel() },
        label = { Text(label, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier.testTag("model-control-chip")
    )
    if (open) ModelControlDialog(
        state = state,
        onDismiss = { open = false },
        onRefresh = onRefreshModel,
        onApply = onSetModel,
        onCancelPending = onCancelModel
    )
}

@Composable
private fun ModelControlDialog(
    state: NativeSessionUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onApply: (String, String, Boolean, Boolean) -> Unit,
    onCancelPending: () -> Unit
) {
    val control = state.modelControl
    val catalog = control?.catalog.orEmpty()
    var modelId by rememberSaveable(state.hostId, state.internalSession) { mutableStateOf("auto") }
    var effortId by rememberSaveable(state.hostId, state.internalSession) { mutableStateOf("automatic") }
    var custom by rememberSaveable(state.hostId, state.internalSession) { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    var confirmHistory by remember { mutableStateOf(false) }
    val requestedModel = control?.pending?.modelId ?: control?.selected?.modelId
    val requestedEffort = control?.pending?.effortId ?: control?.selected?.effortId
    LaunchedEffect(control?.configRevision, catalog.size) {
        if (requestedModel != null) {
            modelId = requestedModel
            effortId = requestedEffort ?: "automatic"
            custom = catalog.none { it.id == requestedModel }
        }
    }
    val selectedModel = catalog.firstOrNull { it.id == modelId }
    val effortOptions = selectedModel?.efforts ?: buildList {
        add(FleetModelEffortOption("automatic", "Automatic"))
        catalog.flatMap { it.efforts }.forEach { option -> if (none { it.id == option.id }) add(option) }
    }
    val effortOptionIds = effortOptions.map { it.id }
    val configurableEffort = effortOptions.any { it.id != "automatic" }
    LaunchedEffect(modelId, effortOptionIds) {
        if (effortId !in effortOptionIds) {
            effortId = selectedModel?.defaultEffort ?: effortOptions.firstOrNull()?.id.orEmpty()
        }
    }
    val hasHistory = state.items.any { item ->
        item.role == "assistant" && (item.state == "complete" || item.completedAt.isNotBlank() || item.kind == "message") &&
            (item.text.isNotBlank() || item.detail.isNotBlank())
    } || state.viewMode != NativeViewMode.Native // Terminal mode does not fetch transcript content; warn conservatively.
    val effective = control?.effective ?: control?.selected
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(.94f).widthIn(max = 560.dp).heightIn(max = 720.dp).testTag("model-control-dialog"),
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 8.dp
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text("${prettyAdapter(state.adapter)} · current session only", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
                        Text(state.sessionLabel, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        if (effective != null) Text(
                            "Effective: ${effective.modelLabel} · ${effective.effortLabel}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                if (control?.pending != null) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Queued for idle", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("${control.pending.modelId} · ${control.pending.effortId}", fontSize = 10.sp)
                        }
                        TextButton(onClick = onCancelPending, enabled = !state.modelControlLoading) { Text("Cancel") }
                    }
                }
                if (catalog.isEmpty()) {
                    Text(
                        state.modelControlError ?: if (state.modelControlLoading) "Loading model options…" else "Model options are unavailable.",
                        color = if (state.modelControlError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onRefresh, enabled = !state.modelControlLoading) { Text("Retry") }
                } else {
                    Text("Model", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Box {
                        OutlinedButton(onClick = { modelMenu = true }, modifier = Modifier.fillMaxWidth(), enabled = !custom) {
                            Text(selectedModel?.label ?: "Choose model", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            catalog.forEach { model -> DropdownMenuItem(
                                text = { Column { Text(model.label); if (model.description.isNotBlank()) Text(model.description, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                                onClick = { modelId = model.id; effortId = model.defaultEffort; custom = false; modelMenu = false }
                            ) }
                        }
                    }
                    if (control?.customAllowed == true) {
                        TextButton(onClick = { custom = !custom; if (!custom) catalog.firstOrNull()?.let { modelId = it.id; effortId = it.defaultEffort } }) {
                            Text(if (custom) "✓ Other model ID" else "Other model ID")
                        }
                        if (custom) OutlinedTextField(
                            value = modelId, onValueChange = { modelId = it.trim().take(160) },
                            label = { Text("Provider model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text("Reasoning effort", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Box {
                        OutlinedButton(onClick = { effortMenu = true }, modifier = Modifier.fillMaxWidth(), enabled = configurableEffort) {
                            Text(effortOptions.firstOrNull { it.id == effortId }?.label ?: "Automatic")
                        }
                        DropdownMenu(expanded = effortMenu, onDismissRequest = { effortMenu = false }) {
                            effortOptions.forEach { effort -> DropdownMenuItem(
                                text = { Text(effort.label) }, onClick = { effortId = effort.id; effortMenu = false }
                            ) }
                        }
                    }
                    Text(
                        if (hasHistory) "Changing model or effort can reduce prompt-cache reuse and change cost for the rest of this session."
                        else "This changes only the running session; defaults are unchanged.",
                        color = if (hasHistory) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    state.modelControlError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                    control?.detail?.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = {
                                if (hasHistory) confirmHistory = true else onApply(modelId, effortId, custom, true)
                            },
                            enabled = !state.modelControlLoading && modelId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,159}")) &&
                                effortId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}"))
                        ) { Text(if (state.modelControlLoading) "Working…" else "Apply") }
                    }
                }
            }
        }
    }
    if (confirmHistory) AlertDialog(
        onDismissRequest = { confirmHistory = false },
        title = { Text("Change this running session?") },
        text = { Text("The new selection can reduce prompt-cache reuse and change cost. The agent will not restart.") },
        dismissButton = { TextButton(onClick = { confirmHistory = false }) { Text("Keep current") } },
        confirmButton = { Button(onClick = { confirmHistory = false; onApply(modelId, effortId, custom, true) }) { Text("Apply") } }
    )
}

@Composable
private fun NativeAiComposer(
    interactionMode: String,
    conversationItems: List<ConversationItem>,
    conversationRevision: String,
    liveEventSerial: Long,
    localSuggestions: NativeLocalSuggestionState,
    onComposerText: (String, Boolean) -> Boolean,
    onAttach: () -> Unit
) {
    var value by rememberSaveable { mutableStateOf("") }
    var observedLiveSerial by remember { mutableStateOf(liveEventSerial) }
    var observedSuggestionKey by remember { mutableStateOf("") }
    val automaticTarget = LocalSuggestionTarget("composer")
    val automaticKey = if (canSuggestForComposer(conversationItems, value)) {
        localSuggestionRevision(conversationItems, automaticTarget)
    } else ""
    LaunchedEffect(conversationRevision) {
        observedLiveSerial = liveEventSerial
        observedSuggestionKey = automaticKey
    }
    LaunchedEffect(liveEventSerial) {
        val start = shouldStartAutomaticSuggestion(
            observedSuggestionKey, automaticKey,
            localSuggestions.mode == LocalSuggestionMode.AUTOMATIC && liveEventSerial > observedLiveSerial,
            historicalFrame = false
        )
        observedLiveSerial = liveEventSerial
        observedSuggestionKey = automaticKey
        if (start) localSuggestions.request(conversationItems, automaticTarget, automatic = true)
    }
    Surface(
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 5.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onAttach, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp)) {
                    Text("Attach", fontSize = 12.sp)
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        if (it.length <= 32_768 && '\u0000' !in it) {
                            value = it
                            if (it.isNotBlank()) localSuggestions.clear()
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("native-message-input"),
                    placeholder = { Text(if (interactionMode == "plan") "Plan message…" else "Message…") },
                    minLines = 1,
                    maxLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp)
                )
                TextButton(enabled = value.isNotEmpty(), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp), onClick = {
                    if (onComposerText(value, false)) value = ""
                }) { Text("Insert", fontSize = 12.sp) }
                Button(contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp), onClick = {
                    if (onComposerText(value, true)) value = ""
                }) { Text(if (value.isEmpty()) "Enter" else "Send", fontSize = 12.sp) }
            }
            if (localSuggestions.targetKey == "composer") {
                LocalSuggestionChoices(
                    localSuggestions,
                    onUse = { value = it; localSuggestions.clear() },
                    onRegenerate = { localSuggestions.request(conversationItems, automaticTarget, automatic = localSuggestions.automatic) }
                )
            }
            if (localSuggestions.available && canSuggestForComposer(conversationItems, value)) {
                TextButton(
                    onClick = { localSuggestions.request(conversationItems, automaticTarget) },
                    modifier = Modifier.align(Alignment.End).testTag("local-suggest-composer"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) { Text(if (localSuggestions.mode == LocalSuggestionMode.AUTOMATIC) "Regenerate" else "Suggest locally", fontSize = 12.sp) }
            }
        }
    }
}

internal class NativeLocalSuggestionState(
    context: android.content.Context,
    private val availableOverride: Boolean? = null,
    debugFakeOutput: String? = null,
    private val modeOverride: LocalSuggestionMode? = null
) : AutoCloseable {
    private val app = context.applicationContext
    private val client = LocalSuggestionClient(app, debugFakeOutput)
    private var serial = 0L
    var targetKey by mutableStateOf("")
        private set
    var loading by mutableStateOf(false)
        private set
    var values by mutableStateOf<List<String>>(emptyList())
        private set
    var error by mutableStateOf("")
        private set
    var automatic by mutableStateOf(false)
        private set
    val mode: LocalSuggestionMode get() = modeOverride ?: when (availableOverride) {
        true -> LocalSuggestionMode.MANUAL
        false -> LocalSuggestionMode.OFF
        null -> LocalSuggestionPreferences.mode(app)
    }
    val available: Boolean get() = mode != LocalSuggestionMode.OFF

    fun request(items: List<ConversationItem>, target: LocalSuggestionTarget, automatic: Boolean = false) {
        clear()
        val requestSerial = ++serial
        targetKey = target.key
        loading = true
        this.automatic = automatic
        client.generate(buildLocalSuggestionPrompt(items, target)) { result ->
            if (requestSerial != serial) return@generate
            loading = false
            result.onSuccess { values = it }
                .onFailure { error = if (automatic) "Couldn’t prepare replies locally." else it.message ?: "Local suggestion failed." }
        }
    }

    fun clear() {
        serial++
        client.cancel()
        targetKey = ""
        loading = false
        values = emptyList()
        error = ""
        automatic = false
    }

    override fun close() { clear(); client.close() }
}

@Composable
internal fun LocalSuggestionChoices(
    state: NativeLocalSuggestionState,
    onUse: (String) -> Unit,
    onRegenerate: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("local-suggestion-results"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            when {
                state.loading -> Text(if (state.automatic) "Preparing replies locally…" else "Thinking locally…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                state.error.isNotBlank() -> {
                    Text(state.error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                    TextButton(onClick = onRegenerate, modifier = Modifier.testTag("local-suggestion-retry")) { Text("Retry") }
                }
                else -> {
                    Text("Local suggestions · tap to edit", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    state.values.forEachIndexed { index, value ->
                        OutlinedButton(
                            onClick = { onUse(value) },
                            modifier = Modifier.fillMaxWidth().testTag("local-suggestion-$index")
                        ) { Text(value, modifier = Modifier.fillMaxWidth()) }
                    }
                    TextButton(onClick = onRegenerate, modifier = Modifier.testTag("local-suggestion-regenerate")) { Text("Regenerate") }
                }
            }
            TextButton(onClick = state::clear, modifier = Modifier.align(Alignment.End)) {
                Text(if (state.loading) "Cancel" else "Dismiss")
            }
        }
    }
}

@Composable
private fun ConversationFeed(
    state: NativeSessionUiState,
    padding: PaddingValues,
    onRetry: () -> Unit,
    onLoadOlder: () -> Unit,
    onApproval: (ConversationItem, ConversationChoice) -> Unit,
    onQuestion: (ConversationItem, List<ConversationAnswer>) -> Unit,
    onOpenTerminal: () -> Unit,
    onDirectory: (String) -> Unit,
    onRefreshDirectory: () -> Unit,
    pinnedActionId: String?,
    onScheduleContinue: (Long) -> Unit,
    onDismissAttention: () -> Unit,
    onNearBottomChanged: (Boolean) -> Unit,
    onViewerOpenChanged: (Boolean) -> Unit,
    localSuggestions: NativeLocalSuggestionState
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rows = remember(state.items, state.hasMore, pinnedActionId) {
        buildConversationRows(state.items.filterNot { it.id == pinnedActionId }, state.hasMore)
    }
    var expandedToolIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var handledLiveSerial by remember { mutableStateOf(state.liveEventSerial) }
    var showNewMessages by rememberSaveable { mutableStateOf(false) }
    var viewerItemId by rememberSaveable { mutableStateOf("") }
    var viewerActionIndex by rememberSaveable { mutableStateOf(-1) }
    val nearBottom by remember {
        derivedStateOf { nearConversationBottom(listState.firstVisibleItemIndex) }
    }
    val nearHistoryStart by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            nearConversationHistoryStart(layout.visibleItemsInfo.lastOrNull()?.index ?: -1, layout.totalItemsCount)
        }
    }

    LaunchedEffect(state.revision) {
        if (state.revision.isNotBlank()) {
            listState.scrollToItem(0)
            handledLiveSerial = state.liveEventSerial
            showNewMessages = false
        }
    }
    LaunchedEffect(state.liveEventSerial) {
        if (state.liveEventSerial > handledLiveSerial) {
            handledLiveSerial = state.liveEventSerial
            if (nearBottom) listState.animateScrollToItem(0)
            else showNewMessages = true
        }
    }
    LaunchedEffect(state.focusQuestionSerial) {
        if (state.focusQuestionSerial > 0 && state.focusQuestionId.isNotBlank()) {
            val reversedIndex = rows.asReversed().indexOfFirst { row ->
                row is ConversationRow.Item && row.value.id == state.focusQuestionId
            }
            if (reversedIndex >= 0) listState.animateScrollToItem(reversedIndex + 1)
        }
    }
    LaunchedEffect(nearBottom) {
        if (nearBottom) showNewMessages = false
        onNearBottomChanged(nearBottom)
    }
    LaunchedEffect(viewerItemId) { onViewerOpenChanged(viewerItemId.isNotBlank()) }
    LaunchedEffect(nearHistoryStart, state.hasMore, state.loadingOlder, state.olderLoadError, state.historyLimitReached) {
        if (shouldRequestOlderMessages(nearHistoryStart, state.hasMore, state.loadingOlder, state.olderLoadError, state.historyLimitReached)) {
            onLoadOlder()
        }
    }

    Box(Modifier.fillMaxSize().padding(padding).testTag("native-conversation-feed")) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)
        ) {
            item("bottom-space") { Spacer(Modifier.height(6.dp)) }
            state.attention?.let { attention ->
                item("attention:${attention.id}") {
                    LimitAttentionCard(state, attention, onScheduleContinue, onDismissAttention)
                }
            }
            items(rows.asReversed(), key = { "conversation:${it.id}" }) { row ->
                when (row) {
                    is ConversationRow.Item -> ConversationItemCard(
                        row.value, onApproval, onQuestion, onOpenTerminal, onRetry,
                        onOpenTool = { item, index -> viewerItemId = item.id; viewerActionIndex = index ?: -1 },
                        onOpenPlan = { item -> viewerItemId = item.id; viewerActionIndex = -1 },
                        localSuggestions = localSuggestions, conversationItems = state.items
                    )
                    is ConversationRow.ToolGroup -> ToolGroupCard(
                        row,
                        expanded = row.calls.any { it.id in expandedToolIds },
                        onExpandedChange = { expanded ->
                            expandedToolIds = if (expanded) (expandedToolIds + row.calls.map { it.id }).distinct()
                            else expandedToolIds - row.calls.map { it.id }.toSet()
                        },
                        onOpenTool = { item, index -> viewerItemId = item.id; viewerActionIndex = index ?: -1 }
                    )
                }
            }
            if (state.items.isEmpty() && state.error == null) {
                item("empty") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        Text(if (state.connection == "Live") "No visible conversation yet" else state.connection, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 17.sp)
                    }
                }
            }
            state.error?.let { message ->
                item("error") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(message, fontSize = 16.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onRetry) { Text("Retry") }
                                Text("Terminal remains available", modifier = Modifier.align(Alignment.CenterVertically), fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
            if (state.sourceMode == "shell") {
                item("folders") { DirectoryCard(state, onDirectory, onRefreshDirectory) }
            }
            if (state.hasMore || state.loadingOlder || state.olderLoadError != null || state.historyLimitReached) {
                item("history-trigger") {
                    when {
                        state.loadingOlder -> Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            Text("Loading earlier messages…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                        state.olderLoadError != null -> Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(state.olderLoadError, Modifier.weight(1f), fontSize = 14.sp)
                                TextButton(onClick = onLoadOlder) { Text("Retry") }
                            }
                        }
                        state.historyLimitReached -> Text(
                            "Native history limit reached. Terminal has the complete transcript.",
                            Modifier.fillMaxWidth().padding(8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                        else -> Spacer(Modifier.height(1.dp))
                    }
                }
            }
        }
        if (showNewMessages) {
            Button(
                onClick = {
                    showNewMessages = false
                    scope.launch { listState.animateScrollToItem(0) }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(14.dp),
                shape = RoundedCornerShape(18.dp)
            ) { Text("New messages ↓", fontSize = 15.sp) }
        }
    }
    state.items.firstOrNull { it.id == viewerItemId }?.let { item ->
        ConversationViewerDialog(
            item = item,
            actionIndex = viewerActionIndex.takeIf { it >= 0 },
            onDismiss = { viewerItemId = ""; viewerActionIndex = -1 }
        )
    }
}

internal fun nearConversationBottom(firstVisibleItemIndex: Int): Boolean = firstVisibleItemIndex <= 2

internal fun nearConversationHistoryStart(lastVisibleItemIndex: Int, totalItems: Int): Boolean =
    totalItems > 0 && lastVisibleItemIndex >= (totalItems - 3).coerceAtLeast(0)

internal fun shouldRequestOlderMessages(
    nearHistoryStart: Boolean,
    hasMore: Boolean,
    loading: Boolean,
    error: String?,
    limitReached: Boolean
): Boolean = nearHistoryStart && hasMore && !loading && error == null && !limitReached

@Composable
private fun LimitAttentionCard(
    state: NativeSessionUiState,
    attention: FleetAttention,
    onScheduleContinue: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val defaultTime = defaultLimitScheduleTime(attention.resetAt, System.currentTimeMillis())
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE8D6))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(
                "${attention.agent.replaceFirstChar { it.titlecase() }} usage limit reached",
                color = Color(0xFF542000),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Continue can be sent automatically after the limit resets${formatLimitTime(attention.resetAt)?.let { " at $it" }.orEmpty()}.",
                color = Color(0xFF6A3414),
                fontSize = 15.sp
            )
            state.attentionError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            }
            Button(
                onClick = { onScheduleContinue(defaultTime) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.attentionBusy
            ) { Text(if (state.attentionBusy) "Working…" else "Schedule Continue") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        showLimitDateTimePicker(context, defaultTime) { selected ->
                            onScheduleContinue(selected.coerceAtLeast(System.currentTimeMillis() + 1_000))
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !state.attentionBusy
                ) { Text("Change time") }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    enabled = !state.attentionBusy
                ) { Text("Dismiss") }
            }
        }
    }
}

internal fun defaultLimitScheduleTime(resetAt: String?, now: Long): Long {
    val reset = runCatching {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        resetAt?.let { parser.parse(it)?.time }
    }.getOrNull()
    return maxOf(now + 60_000, (reset ?: now) + 60_000)
}

private fun formatLimitTime(value: String?): String? = runCatching {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    value?.let { parser.parse(it) }?.let { SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(it.time)) }
}.getOrNull()

internal fun showLimitDateTimePicker(context: android.content.Context, initialEpochMs: Long, onSelected: (Long) -> Unit) {
    val initial = Calendar.getInstance().apply { timeInMillis = initialEpochMs }
    DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val selected = Calendar.getInstance().apply {
                        set(Calendar.YEAR, year)
                        set(Calendar.MONTH, month)
                        set(Calendar.DAY_OF_MONTH, day)
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onSelected(selected.timeInMillis)
                },
                initial.get(Calendar.HOUR_OF_DAY),
                initial.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(context)
            ).show()
        },
        initial.get(Calendar.YEAR),
        initial.get(Calendar.MONTH),
        initial.get(Calendar.DAY_OF_MONTH)
    ).show()
}

@Composable
private fun ConversationItemCard(
    value: ConversationItem,
    onApproval: (ConversationItem, ConversationChoice) -> Unit,
    onQuestion: (ConversationItem, List<ConversationAnswer>) -> Unit,
    onOpenTerminal: () -> Unit,
    onCheckAgain: () -> Unit,
    onOpenTool: (ConversationItem, Int?) -> Unit,
    onOpenPlan: (ConversationItem) -> Unit,
    localSuggestions: NativeLocalSuggestionState,
    conversationItems: List<ConversationItem>
) {
    when (value.kind) {
        "message" -> MessageCard(value)
        "approval" -> ApprovalCard(value, onApproval)
        "question" -> QuestionCard(value, onQuestion, onOpenTerminal, onCheckAgain, localSuggestions, conversationItems)
        "tool" -> ToolCallCard(value, onOpenTool)
        "task_list" -> TaskListCard(value)
        "plan" -> PlanCard(value, onOpenPlan)
        "fallback" -> ExpandableActivityCard(value, monospace = true)
        "shell_command" -> ShellCommandCard(value)
        "shell_output" -> ExpandableActivityCard(value, monospace = true)
        "change" -> ExpandableActivityCard(value, monospace = true)
        else -> ExpandableActivityCard(
            if (value.kind == "status" && value.title in setOf("Done", "Turn Duration")) {
                completedWorkDurationEndingAt(value.id, conversationItems)?.let { duration ->
                    value.copy(title = "Worked for ${formatElapsedDuration(duration)}")
                } ?: value
            } else value,
            monospace = false
        )
    }
}

@Composable
private fun MessageCard(value: ConversationItem) {
    val user = value.role == "user"
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        if (user) {
            Card(
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(0.9f),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("You", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    MarkdownText(value.text)
                    value.attachments.forEach { Text("📎 $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        } else {
            Column(
                Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text("Codex", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ReadyGreen)
                MarkdownText(value.text)
                value.attachments.forEach { Text("📎 $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}

@Composable
private fun ApprovalCard(value: ConversationItem, onApproval: (ConversationItem, ConversationChoice) -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3D4))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(value.title.ifBlank { "Approval needed" }, color = Color(0xFF352A00), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (value.text.isNotBlank()) Text(value.text, color = Color(0xFF514500), fontSize = 15.sp)
            if (value.state == "pending") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    value.choices.forEach { choice ->
                        if (choice.id == "deny") OutlinedButton(onClick = { onApproval(value, choice) }) { Text(choice.label) }
                        else Button(onClick = { onApproval(value, choice) }) { Text(choice.label) }
                    }
                }
            } else {
                Text(value.title, color = Color(0xFF514500), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ShellCommandCard(value: ConversationItem) {
    val clipboard = LocalClipboardManager.current
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(value.title, Modifier.padding(start = 10.dp).weight(1f), fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            TextButton(onClick = { clipboard.setText(AnnotatedString(value.title)) }) { Text("Copy") }
        }
    }
}

@Composable
private fun TaskListCard(value: ConversationItem) {
    var expanded by rememberSaveable(value.id, "tasks") { mutableStateOf(false) }
    val complete = value.tasks.isNotEmpty() && value.tasks.all { it.state == "completed" }
    if (complete && !expanded) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable { expanded = true }.testTag("task-list-${value.id}"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✓", color = ReadyGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("All ${value.tasks.size} tasks complete", Modifier.padding(start = 10.dp).weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Show", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }
        }
        return
    }
    val active = value.tasks.indexOfFirst { it.state == "in_progress" }.takeIf { it >= 0 }
        ?: value.tasks.indexOfFirst { it.state == "pending" }.coerceAtLeast(0)
    val visible = if (!expanded && value.tasks.size > 6) {
        val start = (active - 2).coerceIn(0, value.tasks.size - 6)
        value.tasks.subList(start, start + 6)
    } else value.tasks
    Card(modifier = Modifier.testTag("task-list-${value.id}"), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (complete) "Completed tasks" else "Current work", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            if (value.text.isNotBlank()) Text(value.text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            visible.forEach { task ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        when (task.state) { "completed" -> "✓"; "in_progress" -> "●"; else -> "○" },
                        color = when (task.state) { "completed" -> ReadyGreen; "in_progress" -> Color(0xFF55D6E2); else -> MaterialTheme.colorScheme.onSurfaceVariant },
                        fontSize = 14.sp
                    )
                    Column(Modifier.padding(start = 9.dp).weight(1f)) {
                        Text(
                            if (task.state == "in_progress" && task.activeTitle.isNotBlank()) task.activeTitle else task.title,
                            fontSize = 15.sp,
                            fontWeight = if (task.state == "in_progress") FontWeight.Bold else FontWeight.Normal,
                            color = if (task.state == "completed") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (task.detail.isNotBlank()) Text(task.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (value.tasks.size > visible.size || complete) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show all ${value.tasks.size}") }
            }
        }
    }
}

@Composable
private fun PlanCard(value: ConversationItem, onOpenPlan: (ConversationItem) -> Unit) {
    val preview = remember(value.text) {
        val lines = value.text.lineSequence().take(12).toList()
        lines.joinToString("\n").take(4_000) + if (lines.size < value.text.lines().size) "\n…" else ""
    }
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Plan", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            MarkdownText(preview)
            Button(onClick = { onOpenPlan(value) }, modifier = Modifier.align(Alignment.End)) { Text("Open plan") }
        }
    }
}

@Composable
private fun ConversationViewerDialog(item: ConversationItem, actionIndex: Int?, onDismiss: () -> Unit) {
    var wrap by rememberSaveable(item.id, "viewer-wrap") { mutableStateOf(false) }
    var showRaw by rememberSaveable(item.id, "viewer-raw") { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.97f).fillMaxHeight(0.92f),
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 9.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (item.kind == "plan") "Plan" else toolCallTitle(item), fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item.kind != "plan") Text(item.state.replaceFirstChar { it.titlecase() }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.kind != "plan") TextButton(onClick = { wrap = !wrap }) { Text(if (wrap) "No wrap" else "Wrap") }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(if (item.kind == "plan") item.text else semanticToolBlocks(item).joinToString("\n\n") { it.content }))
                    }) { Text("Copy") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (item.kind == "plan") {
                        MarkdownText(item.text)
                    } else {
                        val inputs = item.presentation?.inputBlocks.orEmpty().ifEmpty {
                            item.input.takeIf { it.isNotBlank() }?.let { listOf(ToolPresentationBlock("Input", "json", it)) }.orEmpty()
                        }
                        val selected = actionIndex?.let { index -> inputs.getOrNull(index)?.let(::listOf) } ?: inputs
                        val results = item.presentation?.resultBlocks.orEmpty().ifEmpty {
                            item.result.takeIf { it.isNotBlank() }?.let { listOf(ToolPresentationBlock("Result", "terminal", it)) }.orEmpty()
                        }
                        (selected + results).forEach { block -> ToolSemanticSection(block, wrap = wrap) }
                        if (item.input.isNotBlank() || item.result.isNotBlank() || item.detail.isNotBlank()) {
                            TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Hide raw data" else "Raw data") }
                        }
                        if (showRaw) {
                            item.input.takeIf { it.isNotBlank() }?.let { ToolSemanticSection(ToolPresentationBlock("Raw input", "json", it), wrap = wrap) }
                            item.result.takeIf { it.isNotBlank() }?.let { ToolSemanticSection(ToolPresentationBlock("Raw result", "json", it), wrap = wrap) }
                            if (item.input.isBlank() && item.result.isBlank() && item.detail.isNotBlank()) ToolSemanticSection(ToolPresentationBlock("Raw details", "json", item.detail), wrap = wrap)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolGroupCard(
    group: ConversationRow.ToolGroup,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenTool: (ConversationItem, Int?) -> Unit
) {
    var visibleCount by rememberSaveable(group.calls.first().id) { mutableStateOf(25) }
    val state = toolGroupState(group)
    val errors = group.calls.count { it.state == "error" }
    val running = group.calls.count { it.state == "running" || it.state == "pending" }
    Card(
        modifier = Modifier.testTag("tool-group-${group.calls.first().id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state == "error") MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(statusGlyph(state), fontSize = 15.sp, color = statusColor(state))
                Column(Modifier.padding(start = 9.dp).weight(1f)) {
                    Text(toolGroupTitle(group), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    if (errors > 0 || running > 0) {
                        Text(
                            listOfNotNull(errors.takeIf { it > 0 }?.let { "$it failed" }, running.takeIf { it > 0 }?.let { "$it running" }).joinToString(" · "),
                            fontSize = 13.sp,
                            color = if (errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = { onExpandedChange(!expanded) }) { Text(if (expanded) "Hide" else "Show") }
            }
            if (expanded) {
                group.calls.take(visibleCount).forEachIndexed { index, call ->
                    ToolCallRow(call, index + 1, onOpenTool)
                }
                if (visibleCount < group.calls.size) {
                    TextButton(onClick = { visibleCount = (visibleCount + 25).coerceAtMost(group.calls.size) }) {
                        Text("Show ${minOf(25, group.calls.size - visibleCount)} more")
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCallCard(value: ConversationItem, onOpenTool: (ConversationItem, Int?) -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        ToolCallRow(value, null, onOpenTool)
    }
}

@Composable
private fun ToolCallRow(value: ConversationItem, number: Int?, onOpenTool: (ConversationItem, Int?) -> Unit) {
    val duration = toolDuration(value)
    val metadata = listOfNotNull(
        value.tool.takeIf { it.isNotBlank() } ?: toolActionLabel(value.action),
        value.state.replaceFirstChar { it.titlecase() },
        duration.takeIf { it.isNotBlank() }
    ).joinToString(" · ")
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(statusGlyph(value.state), fontSize = 14.sp, color = statusColor(value.state))
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text(
                    listOfNotNull(number?.let { "$it." }, toolCallTitle(value)).joinToString(" "),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(metadata, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { onOpenTool(value, null) }, modifier = Modifier.testTag("tool-details-${value.id}"), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)) {
                Text("Details", fontSize = 13.sp)
            }
        }
        val inputs = value.presentation?.inputBlocks.orEmpty()
        if (inputs.size > 1) {
            inputs.take(8).forEachIndexed { index, block ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenTool(value, index) },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${index + 1}. ${block.title}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(toolPreviewText(block), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("Details", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (inputs.size > 8) Text("${inputs.size - 8} more actions in Details", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val preview = value.presentation?.resultBlocks?.firstOrNull()
                ?: inputs.firstOrNull()
                ?: semanticToolBlocks(value).firstOrNull()
            preview?.let { ToolFeedPreview(it) }
        }
    }
}

internal fun toolPreviewText(block: ToolPresentationBlock): String {
    val lines = block.content.lineSequence().take(6).toList()
    val value = lines.joinToString("\n").take(360)
    return value + if (value.length < block.content.length) "…" else ""
}

@Composable
private fun ToolFeedPreview(block: ToolPresentationBlock) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(block.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(9.dp), color = Color(0xFF101820)) {
            Text(
                toolPreviewText(block),
                Modifier.fillMaxWidth().padding(10.dp),
                color = Color(0xFFD7E1EA),
                fontFamily = if (block.kind in setOf("code", "terminal", "path", "json", "diff")) FontFamily.Monospace else FontFamily.Default,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ToolSemanticSection(block: ToolPresentationBlock, showHeading: Boolean = true, wrap: Boolean = true) {
    val clipboard = LocalClipboardManager.current
    val background = Color(0xFF101820)
    val foreground = Color(0xFFD7E1EA)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            if (showHeading) Text(block.title, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { clipboard.setText(AnnotatedString(block.content)) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 1.dp)) {
                Text("Copy", fontSize = 12.sp)
            }
        }
        Surface(shape = RoundedCornerShape(10.dp), color = background) {
            val shouldWrap = wrap || block.kind in setOf("terminal", "text", "markdown")
            val bodyModifier = if (shouldWrap) Modifier.fillMaxWidth().padding(11.dp)
            else Modifier.horizontalScroll(rememberScrollState()).padding(11.dp)
            if (block.kind == "diff") {
                Text(diffText(block.content), bodyModifier, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, softWrap = shouldWrap)
            } else {
                Text(
                    block.content,
                    bodyModifier,
                    color = foreground,
                    fontFamily = if (block.kind in setOf("code", "terminal", "path")) FontFamily.Monospace else FontFamily.Default,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    softWrap = shouldWrap
                )
            }
        }
    }
}

private fun semanticToolBlocks(value: ConversationItem): List<ToolPresentationBlock> {
    val supplied = value.presentation?.let { it.inputBlocks + it.resultBlocks }.orEmpty()
    if (supplied.isNotEmpty()) return supplied
    val values = listOf("Input" to value.input, "Result" to value.result, "Details" to value.detail).filter { it.second.isNotBlank() }
    return values.flatMap { (fallback, raw) ->
        runCatching {
            val trimmed = raw.trimStart()
            if (!trimmed.startsWith("{")) return@runCatching listOf(ToolPresentationBlock(fallback, if (fallback == "Result") "terminal" else "code", raw))
            val objectValue = JSONObject(raw)
            objectValue.keys().asSequence().take(32).map { key ->
                val child = objectValue.get(key)
                val content = when (child) {
                    is JSONObject -> child.toString(2)
                    is JSONArray -> child.toString(2)
                    else -> child.toString()
                }
                val kind = when (key.lowercase()) {
                    "cmd", "command", "script" -> "code"
                    "path", "file", "file_path", "filename" -> "path"
                    else -> if (key.contains("diff", true) || key.contains("patch", true)) "diff" else "text"
                }
                ToolPresentationBlock(humanToolField(key), kind, content)
            }.toList()
        }.getOrElse { listOf(ToolPresentationBlock(fallback, if (fallback == "Result") "terminal" else "code", raw)) }
    }
}

private fun humanToolField(value: String): String = value
    .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
    .replace('_', ' ')
    .replace('-', ' ')
    .split(' ')
    .filter { it.isNotBlank() }
    .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }

private fun diffText(value: String): AnnotatedString = buildAnnotatedString {
    value.lines().forEachIndexed { index, line ->
        val color = when {
            line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF52C788)
            line.startsWith("-") && !line.startsWith("---") -> Color(0xFFFF7B72)
            else -> Color.Unspecified
        }
        withStyle(SpanStyle(color = color)) { append(line) }
        if (index < value.lines().lastIndex) append('\n')
    }
}

private fun toolDuration(value: ConversationItem): String = runCatching {
    if (value.startedAt.isBlank() || value.completedAt.isBlank()) return@runCatching ""
    val started = parseConversationTimestamp(value.startedAt) ?: return@runCatching ""
    val completed = parseConversationTimestamp(value.completedAt) ?: return@runCatching ""
    val milliseconds = (completed - started).coerceAtLeast(0)
    if (milliseconds < 1_000) "${milliseconds}ms" else String.format("%.1fs", milliseconds / 1_000.0)
}.getOrDefault("")

internal fun activeWorkStartedAt(adapter: String, items: List<ConversationItem>): Long? {
    if (adapter !in setOf("codex", "claude", "copilot")) return null
    fun time(value: ConversationItem): Long? = parseConversationTimestamp(value.startedAt.ifBlank { value.timestamp })
    fun lifecycleEnd(value: ConversationItem): Boolean =
        (value.kind == "status" && value.title in setOf("Done", "Turn Duration") && value.state != "running") ||
            value.kind == "error" || value.state == "error" ||
            (value.kind in setOf("question", "approval") && value.state != "complete")

    val lastLifecycleEnd = items.indexOfLast(::lifecycleEnd)
    val lastWorking = items.indexOfLast {
        it.kind == "status" && it.title == "Working" && it.state == "running"
    }
    if (lastWorking > lastLifecycleEnd) {
        val userStart = items.indices.lastOrNull { index ->
            index > lastLifecycleEnd && index <= lastWorking &&
                items[index].kind == "message" && items[index].role == "user"
        }
        return time(items[userStart ?: lastWorking]) ?: time(items[lastWorking])
    }

    val lastUser = items.indexOfLast { it.kind == "message" && it.role == "user" }
    if (lastUser < 0) return null
    val lastStop = items.indexOfLast { value ->
        lifecycleEnd(value) || (value.kind == "message" && value.role == "assistant")
    }
    val lastContinuation = items.indexOfLast { value ->
        when (value.kind) {
            "tool", "activity", "change" -> value.state != "error"
            "task_list" -> value.tasks.any { it.state != "completed" }
            else -> false
        }
    }
    return if (maxOf(lastUser, lastContinuation) > lastStop) time(items[lastUser]) else null
}

internal fun optimisticWorkAfterComposerSend(
    previous: Long?, text: String, appendEnter: Boolean, sent: Boolean, nowMillis: Long
): Long? = if (sent && appendEnter && text.isNotBlank()) nowMillis else previous

internal fun optimisticWorkAfterEvent(previous: Long?, value: ConversationItem): Long? {
    if (previous == null) return null
    val authoritativeStart = value.kind == "status" && value.title == "Working" && value.state == "running"
    val lifecycleEnd =
        (value.kind == "status" && value.title in setOf("Done", "Turn Duration") && value.state != "running") ||
            value.kind == "error" || value.state == "error" ||
            (value.kind in setOf("question", "approval") && value.state != "complete")
    return if (authoritativeStart || lifecycleEnd) null else previous
}

internal fun reconcileOptimisticWork(
    previous: Long?, adapter: String, items: List<ConversationItem>
): Long? {
    if (previous == null || activeWorkStartedAt(adapter, items) != null) return null
    val completedAt = items.asReversed().firstNotNullOfOrNull { value ->
        if (
            value.kind == "status" && value.title in setOf("Done", "Turn Duration") &&
            value.state != "running"
        ) parseConversationTimestamp(value.completedAt.ifBlank { value.timestamp }) else null
    }
    return if (completedAt != null && completedAt >= previous) null else previous
}

internal fun completedWorkDurationEndingAt(endId: String, items: List<ConversationItem>): Long? {
    fun time(value: ConversationItem): Long? = parseConversationTimestamp(
        value.completedAt.ifBlank { value.startedAt.ifBlank { value.timestamp } }
    )
    fun lifecycleEnd(value: ConversationItem): Boolean =
        (value.kind == "status" && value.title in setOf("Done", "Turn Duration") && value.state != "running") ||
            value.kind == "error" || value.state == "error" ||
            (value.kind in setOf("question", "approval") && value.state != "complete")

    val endIndex = items.indexOfLast { it.id == endId && lifecycleEnd(it) }
    if (endIndex < 0) return null
    val providerStarted = parseConversationTimestamp(items[endIndex].startedAt)
    val providerCompleted = parseConversationTimestamp(items[endIndex].completedAt)
    if (providerStarted != null && providerCompleted != null && providerCompleted >= providerStarted) {
        return providerCompleted - providerStarted
    }
    val previousEnd = (endIndex - 1 downTo 0).firstOrNull { lifecycleEnd(items[it]) } ?: -1
    val lastWorking = (endIndex - 1 downTo previousEnd + 1).firstOrNull { index ->
        items[index].kind == "status" && items[index].title == "Working"
    }
    val userLimit = lastWorking ?: endIndex
    val lastUser = (userLimit downTo previousEnd + 1).firstOrNull { index ->
        items[index].kind == "message" && items[index].role == "user"
    }
    val startIndex = lastUser ?: lastWorking ?: return null
    val started = parseConversationTimestamp(
        items[startIndex].startedAt.ifBlank { items[startIndex].timestamp }
    ) ?: return null
    val completed = time(items[endIndex]) ?: return null
    return (completed - started).takeIf { it >= 0L }
}

internal fun latestCompletedWorkDuration(adapter: String, items: List<ConversationItem>): Long? {
    if (adapter !in setOf("codex", "claude", "copilot")) return null
    val end = items.lastOrNull {
        it.kind == "status" && it.title in setOf("Done", "Turn Duration") && it.state != "running"
    } ?: return null
    return completedWorkDurationEndingAt(end.id, items)
}

internal fun formatWorkingDuration(startedAtMillis: Long, nowMillis: Long): String {
    return formatElapsedDuration((nowMillis - startedAtMillis).coerceAtLeast(0L))
}

internal fun formatElapsedDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

internal fun parseConversationTimestamp(value: String): Long? {
    val normalized = value.replace(Regex("\\.(\\d{3})\\d+(?=Z|[+-])"), ".$1")
    for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX")) {
        val parsed = runCatching {
            SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(normalized)?.time
        }.getOrNull()
        if (parsed != null) return parsed
    }
    return null
}

@Composable
private fun QuestionCard(
    value: ConversationItem,
    onQuestion: (ConversationItem, List<ConversationAnswer>) -> Unit,
    onOpenTerminal: () -> Unit,
    onCheckAgain: () -> Unit,
    localSuggestions: NativeLocalSuggestionState,
    conversationItems: List<ConversationItem>
) {
    var expandedAnswer by rememberSaveable(value.id) { mutableStateOf(false) }
    if (value.state == "complete") {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓", color = ReadyGreen, fontWeight = FontWeight.Bold)
                    Text("Answered", Modifier.padding(start = 9.dp).weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { expandedAnswer = !expandedAnswer }) { Text(if (expandedAnswer) "Hide" else "Show") }
                }
                if (expandedAnswer) {
                    value.questions.forEach { Text(it.prompt, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                    val summary = questionAnswerSummary(value)
                    if (summary.isNotBlank()) Text(summary, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }

    Card(modifier = Modifier.fillMaxSize().testTag("question-${value.id}"), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3D4))) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(value.title.ifBlank { "Answer needed" }, color = Color(0xFF352A00), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            when {
                value.state == "running" -> {
                    Text("Sending your answer and waiting for the agent to confirm it…", color = Color(0xFF514500), fontSize = 15.sp)
                    OutlinedButton(onClick = onOpenTerminal) { Text("Open Terminal") }
                }
                value.state == "error" -> {
                    Text(value.text.ifBlank { "The answer was not confirmed. Review it, then retry." }, color = Color(0xFF7A3000), fontSize = 15.sp)
                    if (value.revision != null && value.questions.isNotEmpty()) QuestionForm(value, onQuestion, onOpenTerminal, localSuggestions, conversationItems, Modifier.weight(1f), retry = true)
                    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onCheckAgain) { Text("Check again") }
                        OutlinedButton(onClick = onOpenTerminal) { Text("Open Terminal") }
                    }
                }
                value.revision == null || value.questions.isEmpty() -> {
                    Text("This prompt can be reviewed here, but it cannot be answered safely in Native view.", color = Color(0xFF514500), fontSize = 15.sp)
                    OutlinedButton(onClick = onOpenTerminal) { Text("Open Terminal") }
                }
                else -> QuestionForm(value, onQuestion, onOpenTerminal, localSuggestions, conversationItems, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun QuestionForm(
    value: ConversationItem,
    onQuestion: (ConversationItem, List<ConversationAnswer>) -> Unit,
    onOpenTerminal: () -> Unit,
    localSuggestions: NativeLocalSuggestionState,
    conversationItems: List<ConversationItem>,
    modifier: Modifier = Modifier,
    retry: Boolean = false
) {
    var page by rememberSaveable(value.id) { mutableStateOf(0) }
    var draft by rememberSaveable(value.id) { mutableStateOf(answersToDraft(value.answers)) }
    val question = value.questions[page.coerceIn(0, value.questions.lastIndex)]
    val current = questionDraft(draft, question.id)
    val suggestionTarget = LocalSuggestionTarget("question", value.id, question.id, question.prompt)
    var observedPage by remember(value.id) { mutableStateOf(page) }
    LaunchedEffect(page) {
        val changed = page != observedPage
        observedPage = page
        if (changed && localSuggestions.mode == LocalSuggestionMode.AUTOMATIC && canSuggestForQuestion(question, current.text)) {
            localSuggestions.request(conversationItems, suggestionTarget, automatic = true)
        }
    }
    val options = if (question.type == "boolean" && question.options.isEmpty()) listOf(
        ConversationQuestionOption("true", "Yes", ""), ConversationQuestionOption("false", "No", "")
    ) else question.options
    fun sendAnswers(nextDraft: String): Boolean {
        val allAnswers = conversationAnswers(value.questions, nextDraft)
        if (!value.questions.zip(allAnswers).all { (item, answer) -> validQuestionAnswer(item, answer) }) return false
        onQuestion(value, allAnswers.filterIndexed { index, answer ->
            value.questions[index].required || answer.choiceIds.isNotEmpty() || answer.text.isNotBlank()
        })
        return true
    }
    fun advanceOrSend(nextDraft: String, answer: ConversationAnswer) {
        if (!validQuestionAnswer(question, answer)) return
        if (page < value.questions.lastIndex) page++ else sendAnswers(nextDraft)
    }
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(end = 3.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("${page + 1} of ${value.questions.size}", modifier = Modifier.testTag("question-page"), color = Color(0xFF6C5B00), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (question.header.isNotBlank()) Text(question.header, color = Color(0xFF352A00), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(question.prompt, color = Color(0xFF352A00), fontSize = 16.sp)
            options.forEach { option ->
                val selected = option.id in current.choiceIds
                val choose: () -> Unit = {
                    val updated = if (question.type == "multi") {
                        val choices = if (!selected) (current.choiceIds + option.id).distinct() else current.choiceIds - option.id
                        current.copy(choiceIds = choices)
                    } else current.copy(choiceIds = listOf(option.id), text = "")
                    val nextDraft = updateQuestionDraft(draft, updated)
                    draft = nextDraft
                    when (questionTapAction(question, updated, page < value.questions.lastIndex)) {
                        "advance" -> page++
                        "submit" -> sendAnswers(nextDraft)
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = choose).testTag("question-option-${question.id}-${option.id}"),
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) Color(0xFFFFE29A) else Color(0xFFFFF9EA)
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(option.label, color = Color(0xFF352A00), fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                            if (option.description.isNotBlank()) Text(option.description, color = Color(0xFF6C5B00), fontSize = 13.sp)
                        }
                        if (selected) Text("✓", color = Color(0xFF426800), fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (question.allowOther && question.type != "text") {
                val selected = "__other__" in current.choiceIds
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable {
                        val choices = if (question.type == "multi") {
                            if (!selected) (current.choiceIds + "__other__").distinct() else current.choiceIds - "__other__"
                        } else if (!selected) listOf("__other__") else emptyList()
                        draft = updateQuestionDraft(draft, current.copy(choiceIds = choices, text = if (!selected) current.text else ""))
                    }.testTag("question-option-${question.id}-other"),
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) Color(0xFFFFE29A) else Color(0xFFFFF9EA)
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp)) {
                        Text("Other", Modifier.weight(1f), color = Color(0xFF352A00), fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        if (selected) Text("✓", color = Color(0xFF426800), fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (question.type == "text" || "__other__" in current.choiceIds) {
                OutlinedTextField(
                    value = current.text,
                    onValueChange = {
                        if (it.length <= 8 * 1024 && '\u0000' !in it) {
                            draft = updateQuestionDraft(draft, current.copy(text = it))
                            if (it.isNotBlank()) localSuggestions.clear()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("question-text-${question.id}"),
                    placeholder = { Text("Type your answer…") },
                    minLines = 2,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        val updated = questionDraft(draft, question.id)
                        advanceOrSend(draft, updated)
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFF352A00), unfocusedTextColor = Color(0xFF352A00),
                        cursorColor = Color(0xFF6C5B00), focusedBorderColor = Color(0xFF6C5B00),
                        unfocusedBorderColor = Color(0xFF9C8740), focusedPlaceholderColor = Color(0xFF6C5B00),
                        unfocusedPlaceholderColor = Color(0xFF6C5B00)
                    )
                )
                if (localSuggestions.targetKey == suggestionTarget.key) {
                    LocalSuggestionChoices(
                        localSuggestions,
                        onUse = { suggestion -> draft = updateQuestionDraft(draft, current.copy(text = suggestion)); localSuggestions.clear() },
                        onRegenerate = {
                            localSuggestions.request(conversationItems, suggestionTarget, automatic = localSuggestions.automatic)
                        }
                    )
                } else if (localSuggestions.available && canSuggestForQuestion(question, current.text)) {
                    OutlinedButton(
                        onClick = { localSuggestions.request(conversationItems, suggestionTarget) },
                        modifier = Modifier.testTag("local-suggest-question-${question.id}")
                    ) { Text(if (localSuggestions.mode == LocalSuggestionMode.AUTOMATIC) "Regenerate" else "Suggest") }
                }
            }
        }
        val latest = questionDraft(draft, question.id)
        val needsAction = question.type in setOf("multi", "text") || "__other__" in latest.choiceIds
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page > 0) OutlinedButton(onClick = { page-- }) { Text("Back") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onOpenTerminal) { Text("Terminal") }
            if (needsAction) {
                Button(
                    onClick = { advanceOrSend(draft, questionDraft(draft, question.id)) },
                    enabled = validQuestionAnswer(question, latest),
                    modifier = Modifier.testTag("question-submit")
                ) { Text(if (retry && page == value.questions.lastIndex) "Retry" else if (question.type == "multi") "Done" else "Send") }
            } else Text("Tap an answer", color = Color(0xFF6C5B00), fontSize = 13.sp)
        }
    }
}

private fun answersToDraft(answers: List<ConversationAnswer>): String = JSONObject().apply {
    answers.forEach { answer ->
        put(answer.questionId, JSONObject().put("choices", JSONArray(answer.choiceIds)).put("text", answer.text))
    }
}.toString()

private fun questionDraft(value: String, questionId: String): ConversationAnswer = runCatching {
    val objectValue = JSONObject(value).optJSONObject(questionId) ?: JSONObject()
    val choices = objectValue.optJSONArray("choices")
    ConversationAnswer(
        questionId,
        if (choices == null) emptyList() else List(choices.length()) { choices.getString(it) },
        objectValue.optString("text")
    )
}.getOrDefault(ConversationAnswer(questionId, emptyList(), ""))

private fun updateQuestionDraft(value: String, answer: ConversationAnswer): String {
    val root = runCatching { JSONObject(value) }.getOrDefault(JSONObject())
    root.put(answer.questionId, JSONObject().put("choices", JSONArray(answer.choiceIds)).put("text", answer.text))
    return root.toString()
}

private fun conversationAnswers(questions: List<ConversationQuestion>, value: String): List<ConversationAnswer> =
    questions.map { questionDraft(value, it.id) }

private fun validQuestionAnswer(question: ConversationQuestion, answer: ConversationAnswer): Boolean {
    if (!question.required && answer.choiceIds.isEmpty() && answer.text.isBlank()) return true
    return when (question.type) {
        "text" -> answer.text.isNotBlank()
        else -> answer.choiceIds.isNotEmpty() && ("__other__" !in answer.choiceIds || answer.text.isNotBlank())
    }
}

internal fun shouldAdvanceQuestion(question: ConversationQuestion, answer: ConversationAnswer, hasNext: Boolean): Boolean =
    hasNext && validQuestionAnswer(question, answer)

internal fun questionTapAction(question: ConversationQuestion, answer: ConversationAnswer, hasNext: Boolean): String = when {
    question.type !in setOf("single", "boolean") || !validQuestionAnswer(question, answer) -> "wait"
    hasNext -> "advance"
    else -> "submit"
}

private fun questionAnswerSummary(value: ConversationItem): String {
    if (value.answers.isNotEmpty()) return value.answers.joinToString("\n") { answer ->
        val question = value.questions.firstOrNull { it.id == answer.questionId }
        val labels = answer.choiceIds.map { id -> question?.options?.firstOrNull { it.id == id }?.label ?: id }
        listOf(labels.joinToString(", "), answer.text).filter { it.isNotBlank() }.joinToString(": ")
    }
    return value.result.ifBlank { value.detail }.take(1_000)
}

@Composable
private fun ExpandableActivityCard(value: ConversationItem, monospace: Boolean) {
    var expanded by rememberSaveable(value.id) { mutableStateOf(false) }
    val hasBody = value.text.isNotBlank() || value.detail.isNotBlank()
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(statusGlyph(value.state), fontSize = 15.sp, color = statusColor(value.state))
                Text(value.title.ifBlank { defaultTitle(value) }, Modifier.padding(start = 9.dp).weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (hasBody) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(if (expanded) "Hide" else "Details", fontSize = 14.sp)
                }
            }
            if (expanded) {
                val body = listOf(value.text, value.detail).filter { it.isNotBlank() }.joinToString("\n\n")
                if (monospace) Text(body, fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp)
                else MarkdownText(body)
                val clipboard = LocalClipboardManager.current
                TextButton(onClick = { clipboard.setText(AnnotatedString(body)) }) { Text("Copy") }
            }
        }
    }
}

@Composable
private fun DirectoryCard(state: NativeSessionUiState, onDirectory: (String) -> Unit, onRefresh: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Current folder", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.cwd.ifBlank { "Loading…" }, fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                AssistChip(onClick = { onDirectory("..") }, label = { Text("↑ Up") })
                state.directories.forEach { entry ->
                    AssistChip(onClick = { onDirectory(entry.name) }, label = { Text("${if (entry.symlink) "↗" else "📁"} ${entry.name}") })
                }
            }
            if (state.directoryTruncated) Text("Showing the first 200 folders", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ShellCommandBar(onCommand: (String) -> Unit, onKey: (String) -> Unit, onControlC: () -> Unit) {
    var command by rememberSaveable { mutableStateOf("") }
    Surface(
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { if (it.length <= 32_768 && '\u0000' !in it) command = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Command…") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                Button(
                    onClick = { val value = command; if (value.isNotBlank()) { onCommand(value); command = "" } },
                    enabled = command.isNotBlank(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Run") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                AssistChip(onClick = { onKey("UP") }, label = { Text("History") })
                AssistChip(onClick = { onKey("TAB") }, label = { Text("Tab") })
                AssistChip(onClick = onControlC, label = { Text("Ctrl+C") })
                listOf("ls", "pwd", "git status", "cd ~").forEach { quick -> AssistChip(onClick = { onCommand(quick) }, label = { Text(quick) }) }
            }
        }
    }
}

@Composable
private fun MarkdownText(value: String) {
    val density = LocalAgentFleetDisplayDensity.current
    val blocks = remember(value) { splitMarkdownBlocks(value) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is NativeMarkdownBlock.Prose -> MarkwonText(block.content, density.nativeBodySp)
                is NativeMarkdownBlock.Code -> CopyableMarkdownCode(block, index)
            }
        }
    }
}

@Composable
private fun MarkwonText(value: String, textSizeSp: Int) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.create(context) }
    val color = MaterialTheme.colorScheme.onSurface
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { TextView(it).apply { setTextIsSelectable(true); setBackgroundColor(AndroidColor.TRANSPARENT) } },
        update = { view ->
            view.setTextColor(color.toArgbCompat())
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp.toFloat())
            view.includeFontPadding = false
            markwon.setMarkdown(view, value)
        }
    )
}

private sealed interface NativeMarkdownBlock {
    data class Prose(val content: String) : NativeMarkdownBlock
    data class Code(val language: String, val content: String) : NativeMarkdownBlock
}

private fun splitMarkdownBlocks(value: String): List<NativeMarkdownBlock> {
    if (!value.contains("```")) return listOf(NativeMarkdownBlock.Prose(value))
    val result = mutableListOf<NativeMarkdownBlock>()
    val prose = StringBuilder()
    val code = StringBuilder()
    var language = ""
    var inside = false
    value.lineSequence().forEach { line ->
        if (line.startsWith("```")) {
            if (inside) {
                result += NativeMarkdownBlock.Code(language, code.toString().trimEnd('\n'))
                code.clear()
                language = ""
            } else {
                if (prose.isNotEmpty()) {
                    result += NativeMarkdownBlock.Prose(prose.toString().trimEnd('\n'))
                    prose.clear()
                }
                language = line.removePrefix("```").trim().take(32)
            }
            inside = !inside
        } else if (inside) {
            code.append(line).append('\n')
        } else {
            prose.append(line).append('\n')
        }
    }
    if (inside) {
        prose.append("```").append(language).append('\n').append(code)
    }
    if (prose.isNotEmpty()) result += NativeMarkdownBlock.Prose(prose.toString().trimEnd('\n'))
    return result.ifEmpty { listOf(NativeMarkdownBlock.Prose(value)) }
}

@Composable
private fun CopyableMarkdownCode(block: NativeMarkdownBlock.Code, index: Int) {
    val clipboard = LocalClipboardManager.current
    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF101820)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    block.language.ifBlank { "Command / code" },
                    Modifier.weight(1f),
                    color = Color(0xFF9FB0C0),
                    fontSize = 12.sp
                )
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(block.content)) },
                    modifier = Modifier.testTag("markdown-copy-$index"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) { Text("Copy", color = Color(0xFFD7E1EA), fontSize = 12.sp) }
            }
            Text(
                block.content,
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                color = Color(0xFFD7E1EA),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        }
    }
}

private fun Color.toArgbCompat(): Int = AndroidColor.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)

private fun prettyAdapter(value: String): String = when (value) {
    "codex" -> "Codex"
    "claude" -> "Claude Code"
    "copilot" -> "Copilot"
    "fallback" -> "Shell"
    else -> value.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

private fun defaultTitle(value: ConversationItem): String = when (value.kind) {
    "tool" -> "Tool activity"
    "change" -> "Files changed"
    "error" -> "Error"
    "shell_output" -> "Result"
    else -> "Activity"
}

private fun statusGlyph(value: String): String = when (value) {
    "running" -> "●"
    "error" -> "!"
    else -> "✓"
}

@Composable
private fun statusColor(value: String): Color = when (value) {
    "running" -> MaterialTheme.colorScheme.primary
    "error" -> MaterialTheme.colorScheme.error
    else -> ReadyGreen
}

private val ReadyGreen = Color(0xFF14845B)
