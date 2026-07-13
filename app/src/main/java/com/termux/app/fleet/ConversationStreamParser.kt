package com.termux.app.fleet

import org.json.JSONObject

object ConversationStreamParser {
    private const val MAX_FRAME_CHARS = 256 * 1024
    private val itemKinds = setOf("message", "activity", "tool", "change", "approval", "status", "error", "attachment", "fallback", "shell_command", "shell_output")
    private val itemStates = setOf("", "pending", "running", "complete", "error")

    fun parseFrame(line: String): ConversationFrame {
        require(line.length in 2..MAX_FRAME_CHARS) { "Conversation frame is invalid" }
        val root = JSONObject(line)
        require(root.getInt("protocolVersion") == 1) { "Conversation protocol is unsupported" }
        return when (root.getString("type")) {
            "conversation.snapshot" -> ConversationFrame.Snapshot(
                session = safe(root.getString("session"), 160),
                adapter = safe(root.getString("adapter"), 32),
                mode = safe(root.getString("mode"), 32),
                revision = safe(root.getString("revision"), 160),
                items = root.getJSONArray("items").let { array -> List(array.length()) { parseItem(array.getJSONObject(it)) } },
                nextCursor = root.optString("nextCursor").takeIf { it.isNotBlank() },
                hasMore = root.getBoolean("hasMore")
            )
            "conversation.event" -> ConversationFrame.Event(
                safe(root.getString("session"), 160),
                safe(root.getString("adapter"), 32),
                parseItem(root.getJSONObject("item"))
            )
            "conversation.status", "conversation.heartbeat" -> ConversationFrame.Status(
                safe(root.getString("session"), 160),
                safe(root.getString("adapter"), 32),
                safe(root.getString("status"), 64)
            )
            "conversation.error" -> root.getJSONObject("error").let {
                ConversationFrame.Error(safe(it.getString("code"), 64), safe(it.getString("message"), 512))
            }
            else -> error("Unknown conversation frame")
        }
    }

    fun parseDirectory(line: String): NativeDirectorySnapshot {
        require(line.length in 2..MAX_FRAME_CHARS)
        val root = JSONObject(line)
        require(root.getInt("protocolVersion") == 1 && root.getString("type") == "directory.snapshot")
        val values = root.getJSONArray("entries")
        return NativeDirectorySnapshot(
            session = safe(root.getString("session"), 160),
            cwd = safe(root.getString("cwd"), 4096),
            entries = List(values.length()) { index ->
                values.getJSONObject(index).let { NativeDirectoryEntry(safe(it.getString("name"), 512), it.getBoolean("symlink")) }
            },
            truncated = root.getBoolean("truncated")
        )
    }

    private fun parseItem(value: JSONObject): ConversationItem {
        val kind = safe(value.getString("kind"), 32)
        require(kind in itemKinds)
        val state = safe(value.getString("state"), 16)
        require(state in itemStates)
        val attachments = value.getJSONArray("attachments")
        val choices = value.getJSONArray("choices")
        return ConversationItem(
            id = safe(value.getString("id"), 160),
            kind = kind,
            timestamp = safe(value.getString("timestamp"), 64),
            role = safe(value.getString("role"), 16),
            title = safe(value.getString("title"), 240),
            text = safe(value.getString("text"), 64 * 1024, multiline = true),
            detail = safe(value.getString("detail"), 128 * 1024, multiline = true),
            state = state,
            tool = safe(value.getString("tool"), 120),
            attachments = List(attachments.length()) { safe(attachments.getString(it), 512) },
            choices = List(choices.length()) { index -> choices.getJSONObject(index).let { ConversationChoice(safe(it.getString("id"), 64), safe(it.getString("label"), 120)) } },
            revision = value.optString("revision").takeIf { it.isNotBlank() }?.let { safe(it, 160) }
        )
    }

    private fun safe(value: String, maximum: Int, multiline: Boolean = false): String {
        require(value.length <= maximum && '\u0000' !in value)
        if (!multiline) require(value.none { it.code < 32 || it.code == 127 })
        return value
    }
}

internal fun mergeConversationItems(current: List<ConversationItem>, incoming: List<ConversationItem>, prepend: Boolean = false): List<ConversationItem> {
    val values = LinkedHashMap<String, ConversationItem>()
    if (prepend) incoming.forEach { values[it.id] = it }
    current.forEach { values[it.id] = it }
    if (!prepend) incoming.forEach { values[it.id] = it }
    return values.values.toList().takeLast(2_000)
}
