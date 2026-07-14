package com.termux.app.fleet

import org.json.JSONObject

object ConversationStreamParser {
    private const val MAX_FRAME_CHARS = 256 * 1024
    private val itemKinds = setOf("message", "activity", "tool", "question", "change", "approval", "status", "error", "attachment", "fallback", "shell_command", "shell_output", "task_list", "plan")
    private val itemStates = setOf("", "pending", "running", "complete", "error")

    fun parseFrame(line: String): ConversationFrame {
        require(line.length in 2..MAX_FRAME_CHARS) { "Conversation frame is invalid" }
        val root = JSONObject(line)
        require(root.getInt("protocolVersion") == 2) { "Conversation protocol is unsupported" }
        return when (root.getString("type")) {
            "conversation.snapshot" -> ConversationFrame.Snapshot(
                session = safe(root.getString("session"), 160),
                adapter = safe(root.getString("adapter"), 32),
                mode = safe(root.getString("mode"), 32),
                interactionMode = interactionMode(root),
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
                safe(root.getString("status"), 64),
                interactionMode(root)
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
        require(root.getInt("protocolVersion") == 2 && root.getString("type") == "directory.snapshot")
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
        val questions = value.optJSONArray("questions")
        val answers = value.optJSONArray("answers")
        val presentation = value.optJSONObject("presentation")
        val tasks = value.optJSONArray("tasks")
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
            revision = value.optString("revision").takeIf { it.isNotBlank() }?.let { safe(it, 160) },
            action = safe(value.optString("action"), 32),
            target = safe(value.optString("target"), 160),
            input = safe(value.optString("input"), 128 * 1024, multiline = true),
            result = safe(value.optString("result"), 128 * 1024, multiline = true),
            startedAt = safe(value.optString("startedAt"), 64),
            completedAt = safe(value.optString("completedAt"), 64),
            questions = if (questions == null) emptyList() else List(questions.length().coerceAtMost(16)) { index ->
                val question = questions.getJSONObject(index)
                val options = question.optJSONArray("options")
                ConversationQuestion(
                    id = safe(question.getString("id"), 80),
                    header = safe(question.optString("header"), 120),
                    prompt = safe(question.getString("prompt"), 2_000, multiline = true),
                    type = safe(question.getString("type"), 16).also { require(it in setOf("single", "multi", "text", "boolean")) },
                    required = question.optBoolean("required", true),
                    allowOther = question.optBoolean("allowOther", false),
                    options = if (options == null) emptyList() else List(options.length().coerceAtMost(32)) { optionIndex ->
                        options.getJSONObject(optionIndex).let { option ->
                            ConversationQuestionOption(
                                safe(option.getString("id"), 80),
                                safe(option.getString("label"), 160),
                                safe(option.optString("description"), 320, multiline = true)
                            )
                        }
                    }
                )
            },
            answers = if (answers == null) emptyList() else List(answers.length().coerceAtMost(16)) { index ->
                val answer = answers.getJSONObject(index)
                val selected = answer.optJSONArray("choiceIds")
                ConversationAnswer(
                    safe(answer.getString("questionId"), 80),
                    if (selected == null) emptyList() else List(selected.length().coerceAtMost(32)) { safe(selected.getString(it), 80) },
                    safe(answer.optString("text"), 8 * 1024, multiline = true)
                )
            },
            presentation = presentation?.let(::parsePresentation),
            source = safe(value.optString("source"), 64),
            turnId = safe(value.optString("turnId"), 160),
            taskListId = safe(value.optString("taskListId"), 160),
            updateMode = safe(value.optString("updateMode"), 16).also { require(it in setOf("", "replace", "merge")) },
            tasks = if (tasks == null) emptyList() else List(tasks.length().coerceAtMost(64)) { index ->
                val task = tasks.getJSONObject(index)
                ConversationTask(
                    safe(task.getString("id"), 160),
                    safe(task.getString("title"), 1_000, multiline = true),
                    safe(task.optString("activeTitle"), 1_000, multiline = true),
                    safe(task.optString("detail"), 4_000, multiline = true),
                    safe(task.getString("state"), 32).also { require(it in setOf("pending", "in_progress", "completed")) }
                )
            }
        )
    }

    private fun parsePresentation(value: JSONObject): ToolPresentation {
        require(value.optInt("version") == 1)
        fun blocks(name: String): List<ToolPresentationBlock> {
            val values = value.optJSONArray(name) ?: return emptyList()
            return List(values.length().coerceAtMost(32)) { index ->
                values.getJSONObject(index).let { block ->
                    ToolPresentationBlock(
                        safe(block.getString("title"), 80),
                        safe(block.getString("kind"), 16),
                        safe(block.getString("content"), 24 * 1024, multiline = true)
                    )
                }
            }
        }
        return ToolPresentation(
            safe(value.getString("title"), 80),
            safe(value.optString("subtitle"), 160),
            value.optInt("previewLines", 12).coerceIn(1, 50),
            blocks("inputBlocks"),
            blocks("resultBlocks")
        )
    }

    private fun interactionMode(root: JSONObject): String = safe(root.optString("interactionMode", "unknown"), 16).also {
        require(it in setOf("plan", "default", "unknown"))
    }

    private fun safe(value: String, maximum: Int, multiline: Boolean = false): String {
        require(value.length <= maximum && '\u0000' !in value)
        if (!multiline) require(value.none { it.code < 32 || it.code == 127 })
        return value
    }
}

internal fun mergeConversationItems(current: List<ConversationItem>, incoming: List<ConversationItem>, prepend: Boolean = false): List<ConversationItem> {
    val values = LinkedHashMap<String, ConversationItem>()
    fun add(value: ConversationItem) {
        values[value.id] = values[value.id]?.let { mergeConversationItem(it, value) } ?: value
    }
    if (prepend) incoming.forEach(::add)
    current.forEach(::add)
    if (!prepend) incoming.forEach(::add)
    return values.values.toList().takeLast(2_000)
}

private fun mergeConversationItem(first: ConversationItem, second: ConversationItem): ConversationItem {
    if (first.kind == "task_list" && second.kind == "task_list") {
        val tasks = if (second.updateMode == "replace") second.tasks else {
            val values = LinkedHashMap(first.tasks.associateBy { it.id })
            second.tasks.forEach { task ->
                val old = values[task.id]
                values[task.id] = if (old == null) task else task.copy(
                    title = task.title.ifBlank { old.title },
                    activeTitle = task.activeTitle.ifBlank { old.activeTitle },
                    detail = task.detail.ifBlank { old.detail }
                )
            }
            values.values.toList()
        }
        return second.copy(text = second.text.ifBlank { first.text }, tasks = tasks)
    }
    val question = first.kind == "question" || second.kind == "question"
    val lifecycle = question || first.kind == "tool" || second.kind == "tool"
    if (!lifecycle) return second
    fun value(newer: String, older: String): String = newer.ifBlank { older }
    val state = when {
        question && (first.state == "complete" || second.state == "complete") -> "complete"
        first.state == "error" || second.state == "error" -> "error"
        first.state == "complete" || second.state == "complete" -> "complete"
        first.state == "running" || second.state == "running" -> "running"
        else -> value(second.state, first.state)
    }
    return second.copy(
        kind = if (question) "question" else "tool",
        timestamp = listOf(first.timestamp, second.timestamp).filter { it.isNotBlank() }.minOrNull().orEmpty(),
        title = if (state == "complete" && question) "Answered" else value(first.title, second.title),
        text = value(second.text, first.text),
        detail = value(second.detail, first.detail),
        state = state,
        tool = value(first.tool, second.tool),
        attachments = (first.attachments + second.attachments).distinct(),
        choices = if (second.choices.isNotEmpty()) second.choices else first.choices,
        revision = second.revision ?: first.revision,
        action = value(first.action, second.action),
        target = value(first.target, second.target),
        input = value(first.input, second.input),
        result = value(second.result, first.result),
        startedAt = value(first.startedAt, second.startedAt),
        completedAt = value(second.completedAt, first.completedAt),
        questions = if (second.questions.isNotEmpty()) second.questions else first.questions,
        answers = if (second.answers.isNotEmpty()) second.answers else first.answers,
        presentation = when {
            first.presentation == null -> second.presentation
            second.presentation == null -> first.presentation
            else -> first.presentation.copy(
                title = first.presentation.title.ifBlank { second.presentation.title },
                subtitle = first.presentation.subtitle.ifBlank { second.presentation.subtitle },
                inputBlocks = if (first.presentation.inputBlocks.isNotEmpty()) first.presentation.inputBlocks else second.presentation.inputBlocks,
                resultBlocks = if (second.presentation.resultBlocks.isNotEmpty()) second.presentation.resultBlocks else first.presentation.resultBlocks
            )
        }
    )
}
