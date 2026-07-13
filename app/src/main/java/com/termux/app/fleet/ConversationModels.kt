package com.termux.app.fleet

data class ConversationChoice(val id: String, val label: String)

data class ConversationItem(
    val id: String,
    val kind: String,
    val timestamp: String,
    val role: String,
    val title: String,
    val text: String,
    val detail: String,
    val state: String,
    val tool: String,
    val attachments: List<String>,
    val choices: List<ConversationChoice>,
    val revision: String? = null
)

sealed class ConversationFrame {
    data class Snapshot(
        val session: String,
        val adapter: String,
        val mode: String,
        val revision: String,
        val items: List<ConversationItem>,
        val nextCursor: String?,
        val hasMore: Boolean
    ) : ConversationFrame()

    data class Event(val session: String, val adapter: String, val item: ConversationItem) : ConversationFrame()
    data class Status(val session: String, val adapter: String, val status: String) : ConversationFrame()
    data class Error(val code: String, val message: String) : ConversationFrame()
}

data class NativeDirectoryEntry(val name: String, val symlink: Boolean)
data class NativeDirectorySnapshot(
    val session: String,
    val cwd: String,
    val entries: List<NativeDirectoryEntry>,
    val truncated: Boolean
)

enum class NativeViewMode { Native, AutomaticTerminal, ManualTerminal }

internal fun terminalScreenViewMode(sourceMode: String, alternateScreen: Boolean, current: NativeViewMode): NativeViewMode = when {
    alternateScreen && sourceMode == "shell" && current == NativeViewMode.Native -> NativeViewMode.AutomaticTerminal
    !alternateScreen && current == NativeViewMode.AutomaticTerminal -> NativeViewMode.Native
    else -> current
}

data class NativeSessionUiState(
    val sessionLabel: String,
    val hostId: String,
    val internalSession: String,
    val adapter: String = "connecting",
    val sourceMode: String = "ai",
    val connection: String = "Connecting…",
    val revision: String = "",
    val items: List<ConversationItem> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val loadingOlder: Boolean = false,
    val olderLoadError: String? = null,
    val historyLimitReached: Boolean = false,
    val liveEventSerial: Long = 0,
    val viewMode: NativeViewMode = NativeViewMode.Native,
    val error: String? = null,
    val cwd: String = "",
    val directories: List<NativeDirectoryEntry> = emptyList(),
    val directoryTruncated: Boolean = false
)
