package com.termux.app.fleet

import android.graphics.Color as AndroidColor
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.viewinterop.AndroidView
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
    val pendingAction = state.items.lastOrNull {
        it.kind in setOf("question", "approval") && it.state != "complete"
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
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        ConversationItemCard(
                            pendingAction,
                            onApproval,
                            onQuestion,
                            onToggleTerminal,
                            onRetry
                        )
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
                onDismissAttention = onDismissAttention
            )
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
    onDismissAttention: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rows = remember(state.items, state.hasMore, pinnedActionId) {
        buildConversationRows(state.items.filterNot { it.id == pinnedActionId }, state.hasMore)
    }
    var expandedToolIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var handledLiveSerial by remember { mutableStateOf(state.liveEventSerial) }
    var showNewMessages by rememberSaveable { mutableStateOf(false) }
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
    }
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
                    is ConversationRow.Item -> ConversationItemCard(row.value, onApproval, onQuestion, onOpenTerminal, onRetry)
                    is ConversationRow.ToolGroup -> ToolGroupCard(
                        row,
                        expanded = row.calls.any { it.id in expandedToolIds },
                        onExpandedChange = { expanded ->
                            expandedToolIds = if (expanded) (expandedToolIds + row.calls.map { it.id }).distinct()
                            else expandedToolIds - row.calls.map { it.id }.toSet()
                        }
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
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onScheduleContinue(defaultTime) },
                    enabled = !state.attentionBusy
                ) { Text(if (state.attentionBusy) "Working…" else "Schedule Continue") }
                OutlinedButton(
                    onClick = {
                        showLimitDateTimePicker(context, defaultTime) { selected ->
                            onScheduleContinue(selected.coerceAtLeast(System.currentTimeMillis() + 1_000))
                        }
                    },
                    enabled = !state.attentionBusy
                ) { Text("Change time") }
                TextButton(onClick = onDismiss, enabled = !state.attentionBusy) { Text("Dismiss") }
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
    onCheckAgain: () -> Unit
) {
    when (value.kind) {
        "message" -> MessageCard(value)
        "approval" -> ApprovalCard(value, onApproval)
        "question" -> QuestionCard(value, onQuestion, onOpenTerminal, onCheckAgain)
        "tool" -> ToolCallCard(value)
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
private fun ToolGroupCard(
    group: ConversationRow.ToolGroup,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
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
                    ToolCallRow(call, index + 1)
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
private fun ToolCallCard(value: ConversationItem) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        ToolCallRow(value, null)
    }
}

@Composable
private fun ToolCallRow(value: ConversationItem, number: Int?) {
    var expanded by rememberSaveable(value.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(statusGlyph(value.state), fontSize = 14.sp, color = statusColor(value.state))
            Text(
                listOfNotNull(number?.let { "$it." }, toolCallTitle(value)).joinToString(" "),
                Modifier.padding(start = 8.dp).weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)) {
                Text(if (expanded) "Hide" else "Details", fontSize = 13.sp)
            }
        }
        if (expanded) ToolCallDetails(value)
    }
}

@Composable
private fun ToolCallDetails(value: ConversationItem) {
    var showAll by rememberSaveable(value.id, "semantic") { mutableStateOf(false) }
    var showRaw by rememberSaveable(value.id, "raw") { mutableStateOf(false) }
    val duration = toolDuration(value)
    val status = buildString {
        append(value.tool.ifBlank { toolActionLabel(value.action) })
        append(" · ").append(value.state.replaceFirstChar { it.titlecase() })
        if (duration.isNotBlank()) append(" · ").append(duration)
    }
    ToolDetailSection("Tool / status", status, monospace = false, diff = false)
    value.presentation?.title?.takeIf { it.isNotBlank() }?.let {
        Text(it, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (value.presentation.subtitle.isNotBlank()) Text(value.presentation.subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val blocks = remember(value.presentation, value.input, value.result, value.detail) { semanticToolBlocks(value) }
    val previewLines = value.presentation?.previewLines ?: 12
    val visible = remember(blocks, showAll, previewLines) { if (showAll) blocks else previewToolBlocks(blocks, previewLines) }
    visible.forEach { block -> ToolSemanticSection(block) }
    if (blocks.sumOf { it.content.lines().size } > previewLines) {
        TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show less" else "Show all") }
    }
    val hasRaw = value.input.isNotBlank() || value.result.isNotBlank() || value.detail.isNotBlank()
    if (hasRaw) {
        TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Hide raw data" else "Raw data") }
    }
    if (showRaw) {
        value.input.takeIf { it.isNotBlank() }?.let { ToolDetailSection("Raw input", it, monospace = true, diff = false) }
        value.result.takeIf { it.isNotBlank() }?.let { ToolDetailSection("Raw result", it, monospace = true, diff = false) }
        if (value.input.isBlank() && value.result.isBlank() && value.detail.isNotBlank()) {
            ToolDetailSection("Raw details", value.detail, monospace = true, diff = false)
        }
    }
}

@Composable
private fun ToolSemanticSection(block: ToolPresentationBlock) {
    val clipboard = LocalClipboardManager.current
    val background = Color(0xFF101820)
    val foreground = Color(0xFFD7E1EA)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(block.title, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { clipboard.setText(AnnotatedString(block.content)) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 1.dp)) {
                Text("Copy", fontSize = 12.sp)
            }
        }
        Surface(shape = RoundedCornerShape(10.dp), color = background) {
            if (block.kind == "diff") {
                Text(diffText(block.content), Modifier.fillMaxWidth().padding(11.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
            } else {
                Text(
                    block.content,
                    Modifier.fillMaxWidth().padding(11.dp),
                    color = foreground,
                    fontFamily = if (block.kind in setOf("code", "terminal", "path")) FontFamily.Monospace else FontFamily.Default,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
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

private fun previewToolBlocks(blocks: List<ToolPresentationBlock>, lineLimit: Int): List<ToolPresentationBlock> {
    var remaining = lineLimit
    val result = mutableListOf<ToolPresentationBlock>()
    for (block in blocks) {
        if (remaining <= 0) break
        val lines = block.content.lines()
        val selected = lines.take(remaining)
        result += block.copy(content = selected.joinToString("\n") + if (selected.size < lines.size) "\n…" else "")
        remaining -= selected.size
    }
    return result
}

@Composable
private fun ToolDetailSection(label: String, content: String, monospace: Boolean, diff: Boolean) {
    val body = remember(content) { prettyToolContent(content) }
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { clipboard.setText(AnnotatedString(content)) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 1.dp)) {
                Text("Copy", fontSize = 12.sp)
            }
        }
        if (diff) {
            Text(diffText(body), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
        } else {
            Text(body, fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

private fun prettyToolContent(value: String): String = runCatching {
    if (value.length > 64 * 1024) return@runCatching value
    when {
        value.trimStart().startsWith("{") -> JSONObject(value).toString(2)
        value.trimStart().startsWith("[") -> JSONArray(value).toString(2)
        else -> value
    }
}.getOrDefault(value)

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

    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3D4))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(value.title.ifBlank { "Answer needed" }, color = Color(0xFF352A00), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            when {
                value.state == "running" -> {
                    Text("Sending your answer and waiting for the agent to confirm it…", color = Color(0xFF514500), fontSize = 15.sp)
                    OutlinedButton(onClick = onOpenTerminal) { Text("Open Terminal") }
                }
                value.state == "error" -> {
                    Text(value.text.ifBlank { "The answer was not confirmed. Review it, then retry." }, color = Color(0xFF7A3000), fontSize = 15.sp)
                    if (value.revision != null && value.questions.isNotEmpty()) QuestionForm(value, onQuestion, onOpenTerminal, retry = true)
                    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onCheckAgain) { Text("Check again") }
                        OutlinedButton(onClick = onOpenTerminal) { Text("Open Terminal") }
                    }
                }
                value.revision == null || value.questions.isEmpty() -> {
                    Text("This prompt can be reviewed here, but it cannot be answered safely in Native view.", color = Color(0xFF514500), fontSize = 15.sp)
                    OutlinedButton(onClick = onOpenTerminal) { Text("Open Terminal") }
                }
                else -> QuestionForm(value, onQuestion, onOpenTerminal)
            }
        }
    }
}

@Composable
private fun QuestionForm(
    value: ConversationItem,
    onQuestion: (ConversationItem, List<ConversationAnswer>) -> Unit,
    onOpenTerminal: () -> Unit,
    retry: Boolean = false
) {
    var page by rememberSaveable(value.id) { mutableStateOf(0) }
    var draft by rememberSaveable(value.id) { mutableStateOf(answersToDraft(value.answers)) }
    val question = value.questions[page.coerceIn(0, value.questions.lastIndex)]
    val current = questionDraft(draft, question.id)
    val options = if (question.type == "boolean" && question.options.isEmpty()) listOf(
        ConversationQuestionOption("true", "Yes", ""), ConversationQuestionOption("false", "No", "")
    ) else question.options
    Text("${page + 1} of ${value.questions.size}", color = Color(0xFF6C5B00), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    if (question.header.isNotBlank()) Text(question.header, color = Color(0xFF352A00), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Text(question.prompt, color = Color(0xFF352A00), fontSize = 16.sp)
    options.forEach { option ->
        val choose: () -> Unit = {
            val updated = if (question.type == "multi") {
                val checked = option.id !in current.choiceIds
                val choices = if (checked) (current.choiceIds + option.id).distinct() else current.choiceIds - option.id
                current.copy(choiceIds = choices)
            } else {
                current.copy(choiceIds = listOf(option.id), text = "")
            }
            draft = updateQuestionDraft(draft, updated)
            if (shouldAdvanceQuestion(question, updated, page < value.questions.lastIndex)) page++
        }
        Row(Modifier.fillMaxWidth().clickable(onClick = choose), verticalAlignment = Alignment.CenterVertically) {
            if (question.type == "multi") {
                Checkbox(
                    checked = option.id in current.choiceIds,
                    onCheckedChange = { choose() }
                )
            } else {
                RadioButton(
                    selected = current.choiceIds.singleOrNull() == option.id,
                    onClick = choose
                )
            }
            Column(Modifier.padding(start = 5.dp).weight(1f)) {
                Text(option.label, color = Color(0xFF352A00), fontSize = 15.sp)
                if (option.description.isNotBlank()) Text(option.description, color = Color(0xFF6C5B00), fontSize = 13.sp)
            }
        }
    }
    if (question.allowOther && question.type != "text") {
        val chooseOther: () -> Unit = {
            val checked = "__other__" !in current.choiceIds
            val choices = if (question.type == "multi") {
                if (checked) (current.choiceIds + "__other__").distinct() else current.choiceIds - "__other__"
            } else if (checked) listOf("__other__") else emptyList()
            draft = updateQuestionDraft(draft, current.copy(choiceIds = choices, text = if (checked) current.text else ""))
        }
        Row(Modifier.fillMaxWidth().clickable(onClick = chooseOther), verticalAlignment = Alignment.CenterVertically) {
            if (question.type == "multi") {
                Checkbox(
                    checked = "__other__" in current.choiceIds,
                    onCheckedChange = { chooseOther() }
                )
            } else {
                RadioButton(
                    selected = current.choiceIds.singleOrNull() == "__other__",
                    onClick = chooseOther
                )
            }
            Text("Other", color = Color(0xFF352A00), fontSize = 15.sp)
        }
    }
    if (question.type == "text" || "__other__" in current.choiceIds) {
        OutlinedTextField(
            value = current.text,
            onValueChange = {
                if (it.length <= 8 * 1024 && '\u0000' !in it) {
                    val updated = current.copy(text = it)
                    draft = updateQuestionDraft(draft, updated)
                    if (shouldAdvanceQuestion(question, updated, page < value.questions.lastIndex)) page++
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Type your answer…") },
            minLines = 2,
            maxLines = 5,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color(0xFF352A00),
                unfocusedTextColor = Color(0xFF352A00),
                cursorColor = Color(0xFF6C5B00),
                focusedBorderColor = Color(0xFF6C5B00),
                unfocusedBorderColor = Color(0xFF9C8740),
                focusedPlaceholderColor = Color(0xFF6C5B00),
                unfocusedPlaceholderColor = Color(0xFF6C5B00)
            )
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (page > 0) OutlinedButton(onClick = { page-- }) { Text("Back") }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onOpenTerminal) { Text("Terminal") }
        if (page < value.questions.lastIndex) {
            Text("Your first valid answer continues", color = Color(0xFF6C5B00), fontSize = 13.sp)
        } else {
            val allAnswers = conversationAnswers(value.questions, draft)
            Button(
                onClick = {
                    onQuestion(value, allAnswers.filterIndexed { index, answer ->
                        value.questions[index].required || answer.choiceIds.isNotEmpty() || answer.text.isNotBlank()
                    })
                },
                enabled = value.questions.zip(allAnswers).all { (item, answer) -> validQuestionAnswer(item, answer) }
            ) { Text(if (retry) "Retry" else "Submit") }
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
