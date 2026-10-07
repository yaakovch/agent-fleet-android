package com.termux.app.fleet

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import androidx.compose.runtime.staticCompositionLocalOf
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class VerifiedSessionIdentity(val host: String, val session: String, val incarnationId: String,
    val projectRoot: String, val backend: String, val tool: String) {
    fun json() = JSONObject().put("schemaVersion", 1).put("host", host).put("session", session)
        .put("incarnationId", incarnationId).put("projectRoot", projectRoot).put("backend", backend).put("tool", tool)
}

internal fun parseSessionIdentity(raw: String, host: String, session: String): VerifiedSessionIdentity {
    val value = JSONObject(raw)
    require(value.keys().asSequence().toSet() == setOf("schemaVersion", "host", "session", "incarnationId", "projectRoot", "backend", "tool"))
    require(value.opt("schemaVersion") is Number && value.getInt("schemaVersion") == 1 && value.getString("host") == host && value.getString("session") == session)
    val incarnation = value.getString("incarnationId").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    val root = value.getString("projectRoot").also { require(it.isNotEmpty() && it.length <= 32767 && it.none(Char::isISOControl)) }
    val backend = value.getString("backend").also { require(it in setOf("linux", "windows", "termux")) }
    val tool = value.getString("tool").also { require(it in setOf("shell", "codex", "claude", "copilot")) }
    return VerifiedSessionIdentity(host, session, incarnation, root, backend, tool)
}

data class SavedReadingAnchor(val itemId: String, val offset: Int)
data class SavedAnswerDraft(val requestId: String, val form: String, val answers: List<ConversationAnswer>)
data class SavedSessionState(val identity: VerifiedSessionIdentity, val executionTarget: String, val revision: Long = 0,
    val message: String = "", val questions: List<SavedAnswerDraft> = emptyList(), val selectedView: String = "native",
    val followOutput: Boolean = true, val anchor: SavedReadingAnchor? = null)

internal fun sessionStateKey(identity: VerifiedSessionIdentity, target: String): String = sha256(
    JSONArray(listOf(identity.host, target, identity.projectRoot, identity.backend, identity.tool, identity.incarnationId)).toString())

private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

internal fun questionFormFingerprint(questions: List<ConversationQuestion>): String = sha256(JSONArray(questions.map { question ->
    JSONArray(listOf(question.id, question.header, question.prompt, question.type, question.required, question.allowOther,
        JSONArray(question.options.map { JSONArray(listOf(it.id, it.label, it.description)) })))
}).toString())

private fun answersJson(answers: List<ConversationAnswer>) = JSONArray(answers.map {
    JSONObject().put("questionId", it.questionId).put("choiceIds", JSONArray(it.choiceIds)).put("text", it.text)
})

internal fun encodeSavedSession(value: SavedSessionState): String {
    require(value.message.length <= 32768 && '\u0000' !in value.message && value.questions.size <= 256)
    require(value.selectedView in setOf("native", "terminal") && value.revision in 0..9007199254740991L && value.executionTarget.length in 1..160)
    value.questions.forEach { draft ->
        require(draft.requestId.length in 1..160 && draft.form.length in 1..160 && draft.answers.size <= 8)
        require(answersJson(draft.answers).toString().toByteArray(Charsets.UTF_8).size <= 32768)
        require(draft.answers.map { it.questionId }.toSet().size == draft.answers.size)
        draft.answers.forEach { require(it.questionId.length in 1..160 && it.choiceIds.size <= 16 &&
            it.choiceIds.all { choice -> choice.length in 1..160 } && it.choiceIds.toSet().size == it.choiceIds.size && '\u0000' !in it.text) }
    }
    require(value.questions.map { it.requestId to it.form }.toSet().size == value.questions.size)
    value.anchor?.let { require(it.itemId.length in 1..160 && it.offset in -1_000_000..1_000_000) }
    return JSONObject().put("schemaVersion", 1).put("identity", value.identity.json()).put("executionTarget", value.executionTarget)
        .put("revision", value.revision).put("message", value.message).put("selectedView", value.selectedView)
        .put("followOutput", value.followOutput).put("anchor", value.anchor?.let {
            JSONObject().put("itemId", it.itemId).put("offset", it.offset)
        } ?: JSONObject.NULL).put("questions", JSONArray(value.questions.map {
            JSONObject().put("requestId", it.requestId).put("form", it.form).put("answers", answersJson(it.answers))
        })).toString()
}

internal fun decodeSavedSession(raw: String, identity: VerifiedSessionIdentity, target: String): SavedSessionState {
    val value = JSONObject(raw)
    require(value.keys().asSequence().toSet() == setOf("schemaVersion", "identity", "executionTarget", "revision", "message", "questions", "selectedView", "followOutput", "anchor"))
    require(value.opt("schemaVersion") is Number && value.getInt("schemaVersion") == 1 && parseSessionIdentity(value.getJSONObject("identity").toString(), identity.host, identity.session) == identity)
    require(value.opt("revision") is Number && value.getDouble("revision") == value.getLong("revision").toDouble())
    require(value.getString("executionTarget") == target)
    val questions = value.getJSONArray("questions")
    val state = SavedSessionState(identity, target, value.getLong("revision"), value.getString("message"),
        List(questions.length()) { index ->
            val draft = questions.getJSONObject(index)
            require(draft.keys().asSequence().toSet() == setOf("requestId", "form", "answers"))
            val answers = draft.getJSONArray("answers")
            SavedAnswerDraft(draft.getString("requestId"), draft.getString("form"), List(answers.length()) { answerIndex ->
                val answer = answers.getJSONObject(answerIndex)
                require(answer.keys().asSequence().toSet() == setOf("questionId", "choiceIds", "text"))
                val choices = answer.getJSONArray("choiceIds")
                ConversationAnswer(answer.getString("questionId"), List(choices.length()) { choices.getString(it) }, answer.getString("text"))
            })
        }, value.getString("selectedView"), value.getBoolean("followOutput"), value.optJSONObject("anchor")?.let {
            require(it.keys().asSequence().toSet() == setOf("itemId", "offset"))
            require(it.opt("offset") is Number && it.getDouble("offset") == it.getInt("offset").toDouble())
            SavedReadingAnchor(it.getString("itemId"), it.getInt("offset"))
        })
    encodeSavedSession(state)
    return state
}

/** Revisioned in-memory owner; the single writer debounces and atomically commits private files. */
class SavedSessionBinding internal constructor(private val directory: File) {
    val state = MutableStateFlow<SavedSessionState?>(null)
    val restored = MutableStateFlow<SavedSessionState?>(null)
    val message = MutableStateFlow("")
    val ready = MutableStateFlow(false)
    val saveError = MutableStateFlow("")
    val liveQuestions = MutableStateFlow<Map<String, String>>(emptyMap())
    private var pendingMessage: String? = null
    private var pendingView: String? = null
    private val pendingQuestions = linkedMapOf<Pair<String, String>, SavedAnswerDraft>()
    private val pendingQuestionClears = mutableSetOf<Pair<String, String?>>()
    private val retained = mutableMapOf<String, SavedSessionState>()
    private var resolution = 0L
    private var save: ScheduledFuture<*>? = null
    private val lock = Any()

    internal fun isCurrentResolution(token: Long): Boolean = synchronized(lock) { token == resolution }

    internal fun beginResolution(): Long = synchronized(lock) {
        state.value?.let { previous ->
            retained[sessionStateKey(previous.identity, previous.executionTarget)] = previous
            SavedSessionStates.writer.execute { writeState(previous) }
            state.value = null
            message.value = ""
        }
        save?.cancel(false); save = null
        restored.value = null
        liveQuestions.value = emptyMap()
        ready.value = false
        ++resolution
    }

    internal fun resolutionFailed(token: Long) = synchronized(lock) {
        if (token == resolution) ready.value = true
    }

    internal fun install(identity: VerifiedSessionIdentity, target: String, token: Long? = null) {
        synchronized(lock) { if (token != null && token != resolution) return }
        val file = File(directory, sessionStateKey(identity, target) + ".json")
        val current = state.value
        if (current?.identity == identity && current.executionTarget == target) { ready.value = true; return }
        val loaded = synchronized(lock) { retained[sessionStateKey(identity, target)] } ?: runCatching {
            require(file.length() <= 16 * 1024 * 1024)
            decodeSavedSession(AtomicFile(file).openRead().bufferedReader().use { it.readText() }, identity, target)
        }.getOrDefault(SavedSessionState(identity, target))
        synchronized(lock) {
            if (token != null && token != resolution) return
            state.value?.let { previous -> SavedSessionStates.writer.execute { writeState(previous) } }
            save?.cancel(false); save = null
            val pending = loaded.copy(message = pendingMessage ?: loaded.message,
                selectedView = pendingView ?: loaded.selectedView,
                questions = (loaded.questions.filterNot { (it.requestId to it.form) in pendingQuestions ||
                    (it.requestId to it.form) in pendingQuestionClears || (it.requestId to null) in pendingQuestionClears } + pendingQuestions.values))
            val next = if (pending != loaded) pending.copy(revision = loaded.revision + 1) else loaded
            state.value = next
            restored.value = loaded
            message.value = next.message
            ready.value = true
            if (pending != loaded) scheduleWrite()
            pendingMessage = null
            pendingView = null
            pendingQuestions.clear()
            pendingQuestionClears.clear()
        }
    }

    fun updateMessage(value: String) {
        require(value.length <= 32768 && '\u0000' !in value)
        synchronized(lock) {
            message.value = value
            if (state.value == null) pendingMessage = value
            else updateLocked { it.copy(message = value) }
        }
    }

    fun updateQuestion(item: ConversationItem, answers: List<ConversationAnswer>) {
        val draft = SavedAnswerDraft(item.id, questionFormFingerprint(item.questions), answers)
        require(answers.size <= 8 && answersJson(answers).toString().toByteArray(Charsets.UTF_8).size <= 32768)
        synchronized(lock) {
            if (state.value == null) pendingQuestions[item.id to draft.form] = draft
            else updateLocked { previous ->
                previous.copy(questions = previous.questions.filterNot { it.requestId == item.id && it.form == draft.form } + draft)
            }
        }
    }

    fun clearMessage() { updateMessage(""); flush() }
    fun clearQuestion(id: String, form: String? = null) {
        synchronized(lock) {
            if (state.value == null) pendingQuestionClears.add(id to form)
            pendingQuestions.keys.removeAll { it.first == id && (form == null || it.second == form) }
            updateLocked { it.copy(questions = it.questions.filterNot { draft -> draft.requestId == id && (form == null || draft.form == form) }) }
        }
        flush()
    }
    fun updatePosition(follow: Boolean, anchor: SavedReadingAnchor?) = update { it.copy(followOutput = follow, anchor = anchor) }
    fun updateView(view: String) {
        require(view in setOf("native", "terminal"))
        synchronized(lock) {
            if (state.value == null) pendingView = view
            else updateLocked { it.copy(selectedView = view) }
        }
    }

    private fun update(transform: (SavedSessionState) -> SavedSessionState) = synchronized(lock) { updateLocked(transform) }
    private fun updateLocked(transform: (SavedSessionState) -> SavedSessionState) {
        val previous = state.value ?: return
        val changed = transform(previous)
        if (changed == previous) return
        state.value = changed.copy(revision = previous.revision + 1)
        scheduleWrite()
    }
    private fun scheduleWrite() {
        save?.cancel(false)
        save = SavedSessionStates.writer.schedule(::writeCurrent, 300, TimeUnit.MILLISECONDS)
    }
    fun flush() { synchronized(lock) { save?.cancel(false); save = null }; SavedSessionStates.writer.execute(::writeCurrent) }
    private fun writeCurrent() = synchronized(lock) {
        val current = state.value ?: return@synchronized
        writeState(current)
    }
    private fun writeState(current: SavedSessionState) {
        try {
            check(directory.isDirectory || directory.mkdirs())
            val file = AtomicFile(File(directory, sessionStateKey(current.identity, current.executionTarget) + ".json"))
            val bytes = encodeSavedSession(current).toByteArray(Charsets.UTF_8)
            val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
            saveError.value = ""
        } catch (_: Exception) {
            saveError.value = "This draft could not be saved. Keep this session open and try again."
        }
    }
}

val LocalSavedSessionBinding = staticCompositionLocalOf<SavedSessionBinding?> { null }

object SavedSessionStates {
    internal val writer = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "saved-session-writer").apply { isDaemon = true } }
    private val resolver = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "session-identity-resolver").apply { isDaemon = true } }
    private val bindings = mutableMapOf<String, SavedSessionBinding>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized fun find(host: String, session: String): SavedSessionBinding? = bindings[host + "\u0000" + session]

    @Synchronized fun resolve(context: Context, host: String, session: String, target: String,
        onResolved: (SavedSessionBinding) -> Unit = {}): SavedSessionBinding {
        val key = host + "\u0000" + session
        val binding = bindings.getOrPut(key) { SavedSessionBinding(File(context.filesDir, "session-state-v1")) }
        val token = binding.beginResolution()
        resolver.execute {
            runCatching {
                val identity = FleetRuntime(context.applicationContext).sessionIdentity(host, session)
                binding.install(identity, target, token)
            }.onFailure { binding.resolutionFailed(token) }
            main.post { if (binding.isCurrentResolution(token)) onResolved(binding) }
        }
        return binding
    }
    @Synchronized fun flush() { bindings.values.forEach { it.flush() } }
}
