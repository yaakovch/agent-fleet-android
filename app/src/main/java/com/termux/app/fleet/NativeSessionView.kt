package com.termux.app.fleet

import android.graphics.Color as AndroidColor
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
    onControlC: () -> Unit
) {
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
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            if (state.sourceMode == "shell" && !aiComposer) {
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
                onRefreshDirectory = onRefreshDirectory
            )
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
    onRefreshDirectory: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rows = remember(state.items, state.hasMore) { buildConversationRows(state.items, state.hasMore) }
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
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(value.title, Modifier.padding(start = 10.dp).weight(1f), fontFamily = FontFamily.Monospace, fontSize = 15.sp)
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
    val duration = toolDuration(value)
    val status = buildString {
        append(value.tool.ifBlank { toolActionLabel(value.action) })
        append(" · ").append(value.state.replaceFirstChar { it.titlecase() })
        if (duration.isNotBlank()) append(" · ").append(duration)
    }
    ToolDetailSection("Tool / status", status, monospace = false, diff = false)
    value.input.takeIf { it.isNotBlank() }?.let { ToolDetailSection("Input", it, monospace = true, diff = value.action == "edit") }
    value.result.takeIf { it.isNotBlank() }?.let { ToolDetailSection("Result", it, monospace = true, diff = value.action == "edit") }
    if (value.input.isBlank() && value.result.isBlank() && value.detail.isNotBlank()) {
        ToolDetailSection("Details", value.detail, monospace = true, diff = value.action == "edit")
    }
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
                    Text("The answer was not confirmed. Check the live prompt before trying again.", color = Color(0xFF7A3000), fontSize = 15.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun QuestionForm(value: ConversationItem, onQuestion: (ConversationItem, List<ConversationAnswer>) -> Unit, onOpenTerminal: () -> Unit) {
    var page by rememberSaveable(value.id) { mutableStateOf(0) }
    var draft by rememberSaveable(value.id) { mutableStateOf("{}") }
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
            if (question.type == "multi") {
                val checked = option.id !in current.choiceIds
                val choices = if (checked) (current.choiceIds + option.id).distinct() else current.choiceIds - option.id
                draft = updateQuestionDraft(draft, current.copy(choiceIds = choices))
            } else {
                draft = updateQuestionDraft(draft, current.copy(choiceIds = listOf(option.id), text = ""))
            }
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
            onValueChange = { if (it.length <= 8 * 1024 && '\u0000' !in it) draft = updateQuestionDraft(draft, current.copy(text = it)) },
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
            Button(onClick = { page++ }, enabled = validQuestionAnswer(question, current)) { Text("Next") }
        } else {
            val allAnswers = conversationAnswers(value.questions, draft)
            Button(
                onClick = {
                    onQuestion(value, allAnswers.filterIndexed { index, answer ->
                        value.questions[index].required || answer.choiceIds.isNotEmpty() || answer.text.isNotBlank()
                    })
                },
                enabled = value.questions.zip(allAnswers).all { (item, answer) -> validQuestionAnswer(item, answer) }
            ) { Text("Submit") }
        }
    }
}

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
