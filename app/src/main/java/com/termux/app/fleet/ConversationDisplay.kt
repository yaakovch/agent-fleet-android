package com.termux.app.fleet

sealed class ConversationRow {
    abstract val id: String
    abstract val composeKey: String

    data class Item(val value: ConversationItem) : ConversationRow() {
        override val id: String = value.id
        override val composeKey: String = "item:${value.id}"
    }

    data class ToolGroup(
        val calls: List<ConversationItem>,
        val continuesIntoOlderHistory: Boolean
    ) : ConversationRow() {
        override val id: String = "tool-group:${calls.first().id}"
        override val composeKey: String = "group:${calls.first().id}"
    }
}

fun buildConversationRows(items: List<ConversationItem>, hasMore: Boolean, view: ConversationView = ConversationView.Detailed): List<ConversationRow> {
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
    val hasTasks = items.any { it.kind == "task_list" }
    val completed = items.filter { it.activitySummary?.state == "complete" }.map { it.turnId }.toSet()
    val progress = items.filter { it.messagePurpose == "progress" }.associate { it.turnId to it.id }
    val lastUser = items.filter { it.role == "user" && it.turnId.isNotBlank() }.associate { it.turnId to it.id }
    val summaries = items.filter { it.activitySummary != null && it.turnId in lastUser }.associateBy { it.turnId }
    val ordered = if (view == ConversationView.Detailed) items else items.flatMap { value ->
        when {
            value.activitySummary != null && value.turnId in summaries -> emptyList()
            lastUser[value.turnId] == value.id && value.turnId in summaries -> listOf(value, summaries.getValue(value.turnId))
            else -> listOf(value)
        }
    }
    ordered.forEachIndexed { index, value ->
        if (view == ConversationView.Conversation) {
            if (value.kind == "status" && value.title in setOf("Working", "Done", "Turn Duration")) return@forEachIndexed
            if (value.messagePurpose == "progress" && (value.turnId in completed || progress[value.turnId] != value.id)) return@forEachIndexed
            if ((value.kind == "error" || value.state == "error") && value.turnId in completed) return@forEachIndexed
            flush()
            rows += ConversationRow.Item(value)
            return@forEachIndexed
        }
        if (hasTasks && value.kind == "status" && value.title in setOf("Working", "Done")) return@forEachIndexed
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

/** At most eight turns, 200 items per turn, and a conservative four MiB text budget. */
fun boundedActivityPages(current: Map<String, ActivityPage>, id: String, page: ActivityPage): Map<String, ActivityPage> {
    val pages = LinkedHashMap(current)
    pages.remove(id)
    pages[id] = page.copy(items = page.items.take(200))
    fun bytes(): Long = pages.values.sumOf { value -> value.items.sumOf { item ->
        4L * (item.id.length + item.title.length + item.text.length + item.detail.length + item.input.length + item.result.length +
            item.presentation?.let { p -> (p.inputBlocks + p.resultBlocks).sumOf { it.content.length + it.title.length } }.orZero()) + 2048L
    } }
    while (pages.size > 8 || bytes() > 4L * 1024 * 1024) pages.remove(pages.keys.first())
    return pages
}
private fun Int?.orZero(): Int = this ?: 0
