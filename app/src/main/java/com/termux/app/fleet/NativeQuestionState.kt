package com.termux.app.fleet

import java.time.Instant

internal data class PendingActions(
    val current: List<ConversationItem>,
    val earlier: List<ConversationItem>
)

/** Earlier is a presentation category, never an inferred answer or cancellation. */
internal fun partitionPendingActions(items: List<ConversationItem>): PendingActions {
    fun time(value: ConversationItem): Instant? = runCatching { Instant.parse(value.timestamp) }.getOrNull()
    val latestUser = items.withIndex().mapNotNull { (index, value) ->
        if (value.kind == "message" && value.role == "user") time(value)?.let { it to index } else null
    }.maxWithOrNull(compareBy<Pair<Instant, Int>> { it.first }.thenBy { it.second })
    val current = mutableListOf<ConversationItem>()
    val earlier = mutableListOf<ConversationItem>()
    items.forEachIndexed { index, value ->
        if (value.kind !in setOf("question", "approval") || value.state == "complete") return@forEachIndexed
        val timestamp = time(value)
        val old = value.kind == "question" && value.source == "codex_async_question" && latestUser != null && timestamp != null &&
            (timestamp < latestUser.first || (timestamp == latestUser.first && index < latestUser.second))
        (if (old) earlier else current).add(value)
    }
    return PendingActions(current, earlier)
}

/** Bounded, session-local receipts survive snapshot replacement without retaining old feed rows. */
internal class CompletedQuestionMemory(private val limit: Int = 256) {
    private val completed = LinkedHashMap<Pair<String, String>, ConversationItem>()

    fun clear() = completed.clear()

    fun reconcile(incoming: List<ConversationItem>, current: List<ConversationItem>): List<ConversationItem> {
        incoming.filter { it.kind == "question" && it.state == "complete" && it.title != "No longer active" }.forEach { value ->
            val previous = current.firstOrNull { it.id == value.id && it.kind == value.kind &&
                it.source == value.source && value.timestamp.isNotBlank() && it.timestamp == value.timestamp }
            val revision = value.revision?.takeIf(String::isNotBlank) ?: previous
                ?.takeIf { value.questions.isNotEmpty() && value.questions == it.questions }
                ?.revision?.takeIf(String::isNotBlank)
            if (revision != null) {
                val received = if (previous != null && previous.revision == revision)
                    mergeConversationItems(listOf(previous), listOf(value.copy(revision = revision))).single()
                else value.copy(revision = revision)
                val key = value.id to revision
                val saved = completed[key]?.let { mergeConversationItems(listOf(it), listOf(received)).single() } ?: received
                completed.remove(key)
                completed[key] = saved
                while (completed.size > limit) completed.remove(completed.keys.first())
            }
        }
        return incoming.map { value ->
            val saved = if (value.kind != "question") null else if (!value.revision.isNullOrBlank()) {
                completed[value.id to value.revision]
            } else if (value.timestamp.isNotBlank() && value.questions.isNotEmpty()) {
                // Older hosts omit revisions on historical rows. Require the full
                // original request identity, and reject ambiguous matches.
                completed.values.filter { it.id == value.id && it.kind == value.kind && it.source == value.source &&
                    it.timestamp == value.timestamp && it.questions == value.questions }.singleOrNull()
            } else null
            if (saved != null) mergeConversationItems(listOf(value), listOf(saved)).single() else value
        }
    }
}
