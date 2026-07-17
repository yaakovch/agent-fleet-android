package com.termux.app.fleet

import com.google.gson.JsonParser

const val LOCAL_SUGGESTION_MAX_MESSAGES = 12
const val LOCAL_SUGGESTION_MAX_CONTEXT_BYTES = 12 * 1024
const val LOCAL_SUGGESTION_MAX_RESULTS = 3
const val LOCAL_SUGGESTION_MAX_RESULT_CHARS = 500

data class LocalSuggestionMessage(val role: String, val text: String)
data class LocalSuggestionTarget(
    val kind: String,
    val itemId: String = "",
    val questionId: String = "",
    val prompt: String = ""
) {
    val key: String get() = if (kind == "composer") "composer" else "question:$itemId:$questionId"
}

fun conversationSuggestionContext(items: List<ConversationItem>): List<LocalSuggestionMessage> =
    boundSuggestionContext(items.asSequence()
        .filter { it.kind == "message" && it.role in setOf("user", "assistant") }
        .map { LocalSuggestionMessage(it.role, cleanSuggestionText(it.text.ifBlank { it.detail })) }
        .filter { it.text.isNotBlank() }
        .toList())

fun boundSuggestionContext(messages: List<LocalSuggestionMessage>): List<LocalSuggestionMessage> {
    val selected = mutableListOf<LocalSuggestionMessage>()
    var bytes = 0
    messages.takeLast(LOCAL_SUGGESTION_MAX_MESSAGES).asReversed().forEach { message ->
        if (message.role !in setOf("user", "assistant")) return@forEach
        val text = cleanSuggestionText(message.text)
        if (text.isBlank()) return@forEach
        val overhead = message.role.toByteArray(Charsets.UTF_8).size + 2
        val available = LOCAL_SUGGESTION_MAX_CONTEXT_BYTES - bytes - overhead
        if (available <= 0) return@forEach
        val bounded = truncateSuggestionUtf8(text, available)
        if (bounded.isBlank()) return@forEach
        selected.add(0, LocalSuggestionMessage(message.role, bounded))
        bytes += overhead + bounded.toByteArray(Charsets.UTF_8).size
    }
    return selected
}

fun canSuggestForComposer(items: List<ConversationItem>, draft: String): Boolean {
    if (draft.isNotBlank()) return false
    val latest = items.lastOrNull {
        it.kind == "message" && it.role in setOf("user", "assistant") && it.text.ifBlank { it.detail }.isNotBlank()
    }
    return latest?.role == "assistant" && latest.state == "complete"
}

fun canSuggestForQuestion(question: ConversationQuestion?, draft: String): Boolean =
    question != null && question.type == "text" && !question.allowOther && draft.isBlank()

fun buildLocalSuggestionPrompt(items: List<ConversationItem>, target: LocalSuggestionTarget): String {
    val conversation = conversationSuggestionContext(items).joinToString("\n") { message ->
        "${message.role.uppercase()}: ${message.text}"
    }
    val question = if (target.kind == "question") "\nSTRUCTURED QUESTION: ${cleanSuggestionText(target.prompt).take(4096)}" else ""
    return """
        Draft possible replies for the human USER in this AI coding conversation.
        Return JSON only: {"suggestions":["..."]}.
        Give 1 to 3 concise, conservative, meaningfully distinct first-person replies.
        Never claim actions, facts, preferences, authorization, or verification the user did not state.
        Do not answer as the assistant. Do not use markdown fences or explanations.

        $conversation$question
    """.trimIndent()
}

fun parseLocalSuggestions(value: String): List<String> {
    val text = value.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val candidates = runCatching {
        val root = JsonParser.parseString(text)
        val array = when {
            root.isJsonObject -> root.asJsonObject.getAsJsonArray("suggestions")
            root.isJsonArray -> root.asJsonArray
            else -> null
        }
        array?.mapNotNull { element -> element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }
    }.getOrNull() ?: text.lineSequence().map { it.replace(Regex("^\\s*(?:[-*]|\\d+[.)])\\s*"), "") }.toList()
    val seen = mutableSetOf<String>()
    return candidates.map { candidate ->
        cleanSuggestionText(candidate).take(LOCAL_SUGGESTION_MAX_RESULT_CHARS).trim()
    }.filter { candidate -> candidate.isNotBlank() && seen.add(candidate.lowercase()) }
        .take(LOCAL_SUGGESTION_MAX_RESULTS)
}

private fun cleanSuggestionText(value: String): String = value.filterNot { character ->
    character.code in 0..8 || character.code in 11..12 || character.code in 14..31 || character.code == 127
}.trim()

private fun truncateSuggestionUtf8(value: String, maximum: Int): String {
    if (value.toByteArray(Charsets.UTF_8).size <= maximum) return value
    var low = 0
    var high = value.length
    while (low < high) {
        val middle = (low + high + 1) / 2
        if (value.take(middle).toByteArray(Charsets.UTF_8).size <= maximum) low = middle else high = middle - 1
    }
    return value.take(low).trimEnd()
}
