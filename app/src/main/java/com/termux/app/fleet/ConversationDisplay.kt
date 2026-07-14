package com.termux.app.fleet

sealed class ConversationRow {
    abstract val id: String

    data class Item(val value: ConversationItem) : ConversationRow() {
        override val id: String = value.id
    }

    data class ToolGroup(
        val calls: List<ConversationItem>,
        val continuesIntoOlderHistory: Boolean
    ) : ConversationRow() {
        override val id: String = "tool-group:${calls.first().id}"
    }
}

fun buildConversationRows(items: List<ConversationItem>, hasMore: Boolean): List<ConversationRow> {
    val rows = mutableListOf<ConversationRow>()
    val tools = mutableListOf<ConversationItem>()
    var toolStart = -1
    fun flush() {
        if (tools.isEmpty()) return
        if (tools.size == 1) rows += ConversationRow.Item(tools.single())
        else rows += ConversationRow.ToolGroup(tools.toList(), hasMore && toolStart == 0)
        tools.clear()
        toolStart = -1
    }
    items.forEachIndexed { index, value ->
        if (value.kind == "tool") {
            if (tools.isEmpty()) toolStart = index
            tools += value
        } else {
            flush()
            rows += ConversationRow.Item(value)
        }
    }
    flush()
    return rows
}

fun toolActionLabel(value: String): String = when (value) {
    "command" -> "Command"
    "read" -> "Read"
    "edit" -> "Edit"
    "search" -> "Search"
    "fetch" -> "Fetch"
    "agent" -> "Agent"
    else -> "Tool"
}

fun toolCallTitle(value: ConversationItem): String {
    value.presentation?.title?.takeIf { it.isNotBlank() }?.let { return it }
    val action = toolActionLabel(value.action)
    return listOf(action, value.target).filter { it.isNotBlank() }.joinToString(" ").ifBlank {
        value.tool.ifBlank { "Tool call" }
    }
}

fun shouldGroupToolActions(value: ConversationItem): Boolean = value.presentation?.inputBlocks?.size?.let { it > 1 } == true

fun toolGroupTitle(group: ConversationRow.ToolGroup): String {
    val count = "${group.calls.size}${if (group.continuesIntoOlderHistory) "+" else ""} tool calls"
    val types = group.calls.groupingBy { toolActionLabel(it.action) }.eachCount()
        .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .joinToString(", ") { "${it.key} ${it.value}" }
    return if (types.isBlank()) count else "$count · $types"
}

fun toolGroupState(group: ConversationRow.ToolGroup): String = when {
    group.calls.any { it.state == "error" } -> "error"
    group.calls.any { it.state == "running" || it.state == "pending" } -> "running"
    else -> "complete"
}
