package com.termux.app.fleet

import android.graphics.Color as AndroidColor
import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat

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
    onDismissAttention: () -> Unit
) {
    var actionMenu by rememberSaveable { mutableStateOf(false) }
    var confirmKill by rememberSaveable { mutableStateOf(false) }
    var actionSheetId by rememberSaveable { mutableStateOf("") }
    var dismissedActionId by rememberSaveable { mutableStateOf("") }
    var feedNearBottom by remember { mutableStateOf(true) }
    var viewerOpen by remember { mutableStateOf(false) }
    val pendingAction = state.items.lastOrNull {
        it.kind in setOf("question", "approval") && it.state != "complete"
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
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("native-session-screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.sessionLabel, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${prettyAdapter(state.adapter)} · ${state.connection}",
                            fontSize = 13.sp,
                            color = if (state.connection == "Live") ReadyGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                actions = {
                    OutlinedButton(onClick = onToggleTerminal, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)) {
                        Text("Terminal", fontSize = 15.sp)
                    }
                    Box {
                        TextButton(onClick = { actionMenu = true }) { Text("Actions") }
                        DropdownMenu(expanded = actionMenu, onDismissRequest = { actionMenu = false }) {
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
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            if (pendingAction != null) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 5.dp) {
                    Row(
                        Modifier.fillMaxWidth().clickable { actionSheetId = pendingAction.id }.padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(pendingAction.title.ifBlank { if (pendingAction.kind == "question") "Answer needed" else "Approval needed" }, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("Tap to respond", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Open", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (state.sourceMode == "shell" && !aiComposer) {
                ShellCommandBar(onShellCommand, onShellKey, onControlC)
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
                onViewerOpenChanged = { viewerOpen = it }
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
                            onOpenTool = { _, _ -> }, onOpenPlan = {}
                        )
                    }
                }
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
    onViewerOpenChanged: (Boolean) -> Unit
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

    Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)
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
                        onOpenPlan = { item -> viewerItemId = item.id; viewerActionIndex = -1 }
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
    onOpenPlan: (ConversationItem) -> Unit
) {
    when (value.kind) {
        "message" -> MessageCard(value)
        "approval" -> ApprovalCard(value, onApproval)
        "question" -> QuestionCard(value, onQuestion, onOpenTerminal, onCheckAgain)
        "tool" -> ToolCallCard(value, onOpenTool)
        "task_list" -> TaskListCard(value)
        "plan" -> PlanCard(value, onOpenPlan)
        "fallback" -> ExpandableActivityCard(value, monospace = true)
        "shell_command" -> ShellCommandCard(value)
        "shell_output" -> ExpandableActivityCard(value, monospace = true)
        "change" -> ExpandableActivityCard(value, monospace = true)
        else -> ExpandableActivityCard(value, monospace = false)
    }
}

@Composable
private fun MessageCard(value: ConversationItem) {
    val user = value.role == "user"
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(if (user) 0.9f else 1f),
            shape = RoundedCornerShape(if (user) 20.dp else 16.dp),
            colors = CardDefaults.cardColors(containerColor = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (user) Text("You", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                MarkdownText(value.text)
                value.attachments.forEach { Text("📎 $it", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
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
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
            TextButton(onClick = { onOpenTool(value, null) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)) {
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
    val milliseconds = Duration.between(Instant.parse(value.startedAt), Instant.parse(value.completedAt)).toMillis().coerceAtLeast(0)
    if (milliseconds < 1_000) "${milliseconds}ms" else String.format("%.1fs", milliseconds / 1_000.0)
}.getOrDefault("")

@Composable
private fun QuestionCard(
    value: ConversationItem,
    onQuestion: (ConversationItem, List<ConversationAnswer>) -> Unit,
    onOpenTerminal: () -> Unit,
    onCheckAgain: () -> Unit
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

    Card(modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3D4))) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(value.title.ifBlank { "Answer needed" }, color = Color(0xFF352A00), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            when {
                value.state == "running" -> {
                    Text("Sending your answer and waiting for the agent to confirm it…", color = Color(0xFF514500), fontSize = 15.sp)
                    OutlinedButton(onClick = onOpenTerminal) { Text("Open Terminal") }
                }
                value.state == "error" -> {
                    Text(value.text.ifBlank { "The answer was not confirmed. Review it, then retry." }, color = Color(0xFF7A3000), fontSize = 15.sp)
                    if (value.revision != null && value.questions.isNotEmpty()) QuestionForm(value, onQuestion, onOpenTerminal, Modifier.weight(1f), retry = true)
                    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onCheckAgain) { Text("Check again") }
                        OutlinedButton(onClick = onOpenTerminal) { Text("Open Terminal") }
                    }
                }
                value.revision == null || value.questions.isEmpty() -> {
                    Text("This prompt can be reviewed here, but it cannot be answered safely in Native view.", color = Color(0xFF514500), fontSize = 15.sp)
                    OutlinedButton(onClick = onOpenTerminal) { Text("Open Terminal") }
                }
                else -> QuestionForm(value, onQuestion, onOpenTerminal, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun QuestionForm(
    value: ConversationItem,
    onQuestion: (ConversationItem, List<ConversationAnswer>) -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier,
    retry: Boolean = false
) {
    var page by rememberSaveable(value.id) { mutableStateOf(0) }
    var draft by rememberSaveable(value.id) { mutableStateOf(answersToDraft(value.answers)) }
    val question = value.questions[page.coerceIn(0, value.questions.lastIndex)]
    val current = questionDraft(draft, question.id)
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
            Text("${page + 1} of ${value.questions.size}", color = Color(0xFF6C5B00), fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                    modifier = Modifier.fillMaxWidth().clickable(onClick = choose),
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
                    },
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
                        if (it.length <= 8 * 1024 && '\u0000' !in it) draft = updateQuestionDraft(draft, current.copy(text = it))
                    },
                    modifier = Modifier.fillMaxWidth(),
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
                    enabled = validQuestionAnswer(question, latest)
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
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
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
    val blocks = remember(value) { splitMarkdownBlocks(value) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is NativeMarkdownBlock.Prose -> MarkwonText(block.content)
                is NativeMarkdownBlock.Code -> CopyableMarkdownCode(block, index)
            }
        }
    }
}

@Composable
private fun MarkwonText(value: String) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.create(context) }
    val color = MaterialTheme.colorScheme.onSurface
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { TextView(it).apply { setTextIsSelectable(true); textSize = 16f; setBackgroundColor(AndroidColor.TRANSPARENT) } },
        update = { view ->
            view.setTextColor(color.toArgbCompat())
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
