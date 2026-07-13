package com.termux.app.fleet

import android.graphics.Color as AndroidColor
import android.widget.TextView
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.noties.markwon.Markwon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeSessionScreen(
    state: NativeSessionUiState,
    aiComposer: Boolean,
    onToggleTerminal: () -> Unit,
    onRetry: () -> Unit,
    onLoadOlder: () -> Unit,
    onApproval: (ConversationItem, ConversationChoice) -> Unit,
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
        ConversationFeed(
            state = state,
            padding = padding,
            onRetry = onRetry,
            onLoadOlder = onLoadOlder,
            onApproval = onApproval,
            onDirectory = onDirectory,
            onRefreshDirectory = onRefreshDirectory
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
    onDirectory: (String) -> Unit,
    onRefreshDirectory: () -> Unit
) {
    val listState = rememberLazyListState()
    val wasNearBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= state.items.lastIndex - 2 } ?: true
    LaunchedEffect(state.items.size) {
        if (wasNearBottom && state.items.isNotEmpty()) listState.animateScrollToItem(state.items.lastIndex)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        state = listState,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.hasMore) {
            item("older") {
                TextButton(onClick = onLoadOlder, enabled = !state.loadingOlder, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.loadingOlder) "Loading…" else "Load earlier conversation")
                }
            }
        }
        if (state.sourceMode == "shell") {
            item("folders") { DirectoryCard(state, onDirectory, onRefreshDirectory) }
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
        if (state.items.isEmpty() && state.error == null) {
            item("empty") {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Text(if (state.connection == "Live") "No visible conversation yet" else state.connection, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 17.sp)
                }
            }
        }
        items(state.items, key = { it.id }) { value -> ConversationItemCard(value, onApproval) }
        item("bottom-space") { Spacer(Modifier.height(6.dp)) }
    }
}

@Composable
private fun ConversationItemCard(value: ConversationItem, onApproval: (ConversationItem, ConversationChoice) -> Unit) {
    when (value.kind) {
        "message" -> MessageCard(value)
        "approval" -> ApprovalCard(value, onApproval)
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
