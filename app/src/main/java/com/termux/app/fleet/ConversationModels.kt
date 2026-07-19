package com.termux.app.fleet

data class ConversationChoice(val id: String, val label: String)

data class ProviderActivity(
    val label: String,
    val elapsedSeconds: Long,
    val observedAt: String,
    val receivedAtMillis: Long = System.currentTimeMillis()
)

data class ConversationQuestionOption(val id: String, val label: String, val description: String)

data class ConversationQuestion(
    val id: String,
    val header: String,
    val prompt: String,
    val type: String,
    val required: Boolean,
    val allowOther: Boolean,
    val options: List<ConversationQuestionOption>
)

data class ConversationAnswer(val questionId: String, val choiceIds: List<String>, val text: String)
data class ConversationTask(
    val id: String,
    val title: String,
    val activeTitle: String,
    val detail: String,
    val state: String
)

data class ToolPresentationBlock(val title: String, val kind: String, val content: String)
data class ToolPresentation(
    val title: String,
    val subtitle: String,
    val previewLines: Int,
    val inputBlocks: List<ToolPresentationBlock>,
    val resultBlocks: List<ToolPresentationBlock>
)

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
    val revision: String? = null,
    val action: String = "",
    val target: String = "",
    val input: String = "",
    val result: String = "",
    val startedAt: String = "",
    val completedAt: String = "",
    val questions: List<ConversationQuestion> = emptyList(),
    val answers: List<ConversationAnswer> = emptyList(),
    val presentation: ToolPresentation? = null,
    val source: String = "",
    val turnId: String = "",
    val taskListId: String = "",
    val updateMode: String = "",
    val tasks: List<ConversationTask> = emptyList()
)

sealed class ConversationFrame {
    data class Snapshot(
        val session: String,
        val adapter: String,
        val mode: String,
        val interactionMode: String,
        val revision: String,
        val items: List<ConversationItem>,
        val nextCursor: String?,
        val hasMore: Boolean,
        val providerActivity: ProviderActivity?,
        val hasProviderActivity: Boolean
    ) : ConversationFrame()

    data class Event(val session: String, val adapter: String, val item: ConversationItem) : ConversationFrame()
    data class Status(
        val session: String,
        val adapter: String,
        val status: String,
        val interactionMode: String,
        val providerActivity: ProviderActivity?,
        val hasProviderActivity: Boolean
    ) : ConversationFrame()
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

internal fun shouldRunConversationStream(
    visible: Boolean,
    enabled: Boolean,
    localSession: Boolean,
    @Suppress("UNUSED_PARAMETER") viewMode: NativeViewMode
): Boolean = visible && enabled && !localSession

internal fun conversationStatusUpdate(
    currentConnection: String,
    currentInteractionMode: String,
    status: String,
    interactionMode: String
): Pair<String, String>? {
    val nextConnection = if (status == "ready") "Live" else status.replace('_', ' ')
    val nextInteractionMode = interactionMode.takeUnless { it == "unknown" } ?: currentInteractionMode
    return if (nextConnection == currentConnection && nextInteractionMode == currentInteractionMode) null
    else nextConnection to nextInteractionMode
}

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
    val interactionMode: String = "unknown",
    val connection: String = "Connecting…",
    val revision: String = "",
    val items: List<ConversationItem> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val loadingOlder: Boolean = false,
    val olderLoadError: String? = null,
    val historyLimitReached: Boolean = false,
    val liveEventSerial: Long = 0,
    val optimisticWorkStartedAt: Long? = null,
    val providerActivity: ProviderActivity? = null,
    val providerActivityAuthoritative: Boolean = false,
    val focusQuestionId: String = "",
    val focusQuestionSerial: Long = 0,
    val viewMode: NativeViewMode = NativeViewMode.Native,
    val error: String? = null,
    val attention: FleetAttention? = null,
    val attentionBusy: Boolean = false,
    val attentionError: String? = null,
    val cwd: String = "",
    val directories: List<NativeDirectoryEntry> = emptyList(),
    val directoryTruncated: Boolean = false,
    val modelControl: FleetModelControlState? = null,
    val modelControlLoading: Boolean = false,
    val modelControlError: String? = null
)
