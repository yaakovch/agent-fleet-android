package com.termux.app.fleet

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.termux.app.AgentFleetTheme
import com.termux.app.TermuxActivity
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class NativeSessionController(
    private val activity: TermuxActivity,
    private val composeView: ComposeView
) {
    private companion object {
        const val HISTORY_PAGE_SIZE = 20
        const val MAX_LOADED_ITEMS = 2_000
    }

    private val main = Handler(Looper.getMainLooper())
    private val appRoot = activity.filesDir.parentFile ?: File("/data/data/com.termux")
    private val prefix = File(appRoot, "files/usr")
    private val home = File(appRoot, "files/home")
    private val uiState = mutableStateOf(NativeSessionUiState("Session", "", ""))
    @Volatile private var visible = false
    @Volatile private var generation = 0
    private var enabled = false
    private var aiComposer = false
    private var localSession = false
    @Volatile private var streamProcess: Process? = null
    private var retryIndex = 0
    private var lastFallbackText = ""
    private var pendingShellId: String? = null

    init {
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        composeView.setContent {
            AgentFleetTheme {
                NativeSessionScreen(
                    state = uiState.value,
                    aiComposer = aiComposer,
                    onToggleTerminal = ::toggleTerminal,
                    onRetry = ::restartNow,
                    onLoadOlder = ::loadOlder,
                    onApproval = ::respondApproval,
                    onShellCommand = ::sendShellCommand,
                    onShellKey = activity::sendAgentFleetKey,
                    onDirectory = ::openDirectory,
                    onRefreshDirectory = ::refreshDirectories,
                    onControlC = { activity.sendAgentFleetControlC() }
                )
            }
        }
    }

    fun bind(intent: Intent?) {
        generation++
        stopProcess()
        val host = intent?.getStringExtra(AgentFleetContract.EXTRA_HOST_ID).orEmpty()
        val session = intent?.getStringExtra(AgentFleetContract.EXTRA_INTERNAL_SESSION).orEmpty()
        val label = intent?.getStringExtra(AgentFleetContract.EXTRA_SESSION_NAME).orEmpty().ifBlank { session }
        aiComposer = intent?.getBooleanExtra(AgentFleetContract.EXTRA_COMPOSE_INPUT, false) == true
        localSession = intent?.getBooleanExtra(AgentFleetContract.EXTRA_LOCAL_SESSION, false) == true
        enabled = intent?.getBooleanExtra(AgentFleetContract.EXTRA_NATIVE_SESSION, false) == true &&
            NativeSessionSettings.isEnabled(activity) && (localSession || (host.isNotBlank() && session.isNotBlank()))
        uiState.value = NativeSessionUiState(
            label.ifBlank { if (localSession) "Local shell" else "Session" },
            host,
            if (localSession) "local" else session,
            adapter = if (localSession) "shell" else "connecting",
            sourceMode = if (localSession) "shell" else "ai",
            connection = if (localSession) "Live" else "Connecting…",
            cwd = if (localSession) home.absolutePath else ""
        )
        applyViewMode(if (enabled) NativeViewMode.Native else NativeViewMode.ManualTerminal)
        if (enabled && localSession) refreshDirectories()
        if (visible && enabled && !localSession) startStream()
    }

    fun onStart() {
        visible = true
        if (enabled && !localSession && streamProcess == null) startStream()
    }

    fun onStop() {
        visible = false
        generation++
        main.removeCallbacksAndMessages(null)
        stopProcess()
    }

    fun close() {
        visible = false
        generation++
        main.removeCallbacksAndMessages(null)
        stopProcess()
    }

    fun onTerminalScreenChanged(alternateScreen: Boolean) {
        if (!enabled) return
        main.post {
            val mode = terminalScreenViewMode(uiState.value.sourceMode, alternateScreen, uiState.value.viewMode)
            if (mode != uiState.value.viewMode) applyViewMode(mode)
        }
    }

    fun onLocalTerminalTextChanged(text: String) {
        if (!enabled || !localSession || text.length > 131_072 || '\u0000' in text) return
        main.post { applyShellFallback(text) }
    }

    fun wantsLocalTerminalText(): Boolean = enabled && localSession

    fun onWorkingDirectoryChanged(path: String) {
        if (!enabled || path.length > 4096 || '\u0000' in path) return
        main.post {
            uiState.value = uiState.value.copy(cwd = path)
            if (uiState.value.sourceMode == "shell") refreshDirectories()
        }
    }

    fun onShellIntegrationEvent(marker: String, data: String) {
        if (!enabled || uiState.value.sourceMode != "shell") return
        main.post {
            if (marker == "D") {
                val identifier = pendingShellId
                if (identifier != null) {
                    val existing = uiState.value.items.firstOrNull { it.id == identifier }
                    if (existing != null) {
                        val success = data.toIntOrNull()?.let { it == 0 } ?: true
                        uiState.value = uiState.value.copy(items = mergeConversationItems(
                            uiState.value.items,
                            listOf(existing.copy(state = if (success) "complete" else "error"))
                        ))
                    }
                    main.postDelayed({ if (pendingShellId == identifier) pendingShellId = null }, 2_500)
                }
                refreshDirectories()
            }
        }
    }

    private fun toggleTerminal() {
        when (uiState.value.viewMode) {
            NativeViewMode.Native -> applyViewMode(NativeViewMode.ManualTerminal)
            NativeViewMode.AutomaticTerminal, NativeViewMode.ManualTerminal -> applyViewMode(NativeViewMode.Native)
        }
    }

    fun showNative() {
        if (enabled) applyViewMode(NativeViewMode.Native)
    }

    private fun applyViewMode(mode: NativeViewMode) {
        uiState.value = uiState.value.copy(viewMode = mode)
        val native = enabled && mode == NativeViewMode.Native
        composeView.visibility = if (native) View.VISIBLE else View.GONE
        activity.setAgentFleetNativeView(enabled, native, mode == NativeViewMode.AutomaticTerminal, aiComposer)
    }

    private fun executable(name: String): File? = sequenceOf(
        File(home, ".local/bin/$name"),
        File(prefix, "bin/$name")
    ).firstOrNull { it.canExecute() }

    private fun environment(process: ProcessBuilder): ProcessBuilder = process.apply {
        directory(home)
        environment()["HOME"] = home.absolutePath
        environment()["PREFIX"] = prefix.absolutePath
        environment()["PATH"] = listOf(File(home, ".local/bin"), File(prefix, "bin"), File(prefix, "bin/applets")).joinToString(":")
    }

    private fun conversationCommand(action: String, extra: List<String> = emptyList()): List<String> {
        val bash = executable("bash") ?: error("Bash is unavailable")
        val wtmux = executable("wtmux") ?: error("wtmux is unavailable")
        return listOf(
            bash.absolutePath, wtmux.absolutePath, "conversation", action,
            "--host", uiState.value.hostId,
            "--session", uiState.value.internalSession
        ) + extra
    }

    private fun startStream() {
        if (!visible || !enabled || streamProcess != null) return
        val token = generation
        uiState.value = uiState.value.copy(connection = if (retryIndex == 0) "Connecting…" else "Reconnecting…", error = null)
        thread(name = "native-session-stream", isDaemon = true) {
            try {
                val process = environment(ProcessBuilder(conversationCommand("stream", listOf("--limit", HISTORY_PAGE_SIZE.toString())))).start()
                streamProcess = process
                thread(name = "native-session-stderr", isDaemon = true) {
                    process.errorStream.use { input ->
                        val buffer = ByteArray(4 * 1024)
                        var total = 0
                        while (total < 64 * 1024) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                        }
                    }
                }
                process.inputStream.bufferedReader().use { reader ->
                    while (token == generation) {
                        val line = reader.readLine() ?: break
                        if (line.length > 256 * 1024) {
                            postError(token, "The host sent an oversized conversation frame.")
                            process.destroyForcibly()
                            break
                        }
                        val frame = runCatching { ConversationStreamParser.parseFrame(line) }.getOrNull()
                        if (frame == null) {
                            postError(token, "The host sent an invalid conversation frame.")
                            process.destroyForcibly()
                            break
                        }
                        main.post { if (token == generation) applyFrame(frame) }
                    }
                }
                process.waitFor()
                if (token == generation) main.post { streamEnded(token) }
            } catch (_: Exception) {
                if (token == generation) postError(token, "The native conversation stream is unavailable.")
            } finally {
                if (token == generation) streamProcess = null
            }
        }
    }

    private fun postError(token: Int, message: String) {
        main.post {
            if (token == generation) {
                uiState.value = uiState.value.copy(connection = "Offline", error = message)
                scheduleRetry(token)
            }
        }
    }

    private fun streamEnded(token: Int) {
        streamProcess = null
        if (!visible || token != generation) return
        if (uiState.value.error == null) uiState.value = uiState.value.copy(connection = "Disconnected")
        scheduleRetry(token)
    }

    private fun scheduleRetry(token: Int) {
        if (!visible || !enabled || token != generation) return
        val delays = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
        val delay = delays[retryIndex.coerceAtMost(delays.lastIndex)]
        retryIndex = (retryIndex + 1).coerceAtMost(delays.lastIndex)
        main.postDelayed({ if (visible && enabled && token == generation && streamProcess == null) startStream() }, delay)
    }

    private fun restartNow() {
        generation++
        stopProcess()
        retryIndex = 0
        uiState.value = uiState.value.copy(
            error = null,
            olderLoadError = null,
            loadingOlder = false,
            historyLimitReached = false,
            connection = "Connecting…"
        )
        if (visible) startStream()
    }

    private fun stopProcess() {
        val process = streamProcess
        process?.destroy()
        if (process?.isAlive == true) process.destroyForcibly()
        streamProcess = null
    }

    private fun applyFrame(frame: ConversationFrame) {
        when (frame) {
            is ConversationFrame.Snapshot -> {
                if (frame.session != uiState.value.internalSession) return
                retryIndex = 0
                var incoming = frame.items
                if (frame.mode == "shell") {
                    incoming.firstOrNull { it.kind == "fallback" }?.let { lastFallbackText = it.text }
                    incoming = incoming.filterNot { it.kind == "fallback" }
                }
                uiState.value = uiState.value.copy(
                    adapter = frame.adapter,
                    sourceMode = frame.mode,
                    connection = "Live",
                    revision = frame.revision,
                    items = mergeConversationItems(emptyList(), incoming),
                    nextCursor = frame.nextCursor,
                    hasMore = frame.hasMore,
                    loadingOlder = false,
                    olderLoadError = null,
                    historyLimitReached = false,
                    error = null
                )
                if (frame.mode == "shell") refreshDirectories()
            }
            is ConversationFrame.Event -> {
                if (frame.session != uiState.value.internalSession) return
                if (uiState.value.sourceMode == "shell" && frame.item.kind == "fallback") {
                    applyShellFallback(frame.item.text)
                } else {
                    val isNew = uiState.value.items.none { it.id == frame.item.id }
                    uiState.value = uiState.value.copy(
                        items = mergeConversationItems(uiState.value.items, listOf(frame.item)),
                        connection = "Live",
                        liveEventSerial = uiState.value.liveEventSerial + if (isNew) 1 else 0
                    )
                }
            }
            is ConversationFrame.Status -> {
                if (frame.session != uiState.value.internalSession) return
                if (frame.status == "reload_required") restartNow()
                else uiState.value = uiState.value.copy(connection = if (frame.status == "ready") "Live" else frame.status.replace('_', ' '))
            }
            is ConversationFrame.Error -> {
                uiState.value = uiState.value.copy(connection = "Unavailable", error = frame.message)
            }
        }
    }

    private fun applyShellFallback(text: String) {
        val commandId = pendingShellId
        if (commandId != null && text != lastFallbackText) {
            val output = when {
                text.startsWith(lastFallbackText) -> text.removePrefix(lastFallbackText).trim()
                else -> text.lines().takeLast(80).joinToString("\n").trim()
            }
            if (output.isNotBlank()) {
                val result = ConversationItem(
                    id = "$commandId-output", kind = "shell_output", timestamp = "", role = "", title = "Result",
                    text = output, detail = "", state = "complete", tool = "shell", attachments = emptyList(), choices = emptyList()
                )
                uiState.value = uiState.value.copy(items = mergeConversationItems(uiState.value.items, listOf(result)))
                uiState.value = uiState.value.copy(liveEventSerial = uiState.value.liveEventSerial + 1)
                pendingShellId = null
            }
        }
        lastFallbackText = text
    }

    private fun loadOlder() {
        val cursor = uiState.value.nextCursor ?: return
        if (uiState.value.loadingOlder) return
        val remaining = MAX_LOADED_ITEMS - uiState.value.items.size
        if (remaining <= 0) {
            uiState.value = uiState.value.copy(hasMore = false, historyLimitReached = true)
            return
        }
        val requestLimit = minOf(HISTORY_PAGE_SIZE, remaining)
        uiState.value = uiState.value.copy(loadingOlder = true, olderLoadError = null)
        val token = generation
        runOneShot(conversationCommand("stream", listOf("--cursor", cursor, "--limit", requestLimit.toString(), "--no-follow"))) { output ->
            val frame = runCatching { ConversationStreamParser.parseFrame(output.lineSequence().first { it.isNotBlank() }) }.getOrNull()
            if (token != generation) return@runOneShot
            when (frame) {
                is ConversationFrame.Snapshot -> {
                    val merged = mergeConversationItems(uiState.value.items, frame.items, prepend = true)
                    val limitReached = merged.size >= MAX_LOADED_ITEMS && frame.hasMore
                    uiState.value = uiState.value.copy(
                        items = merged,
                        nextCursor = if (limitReached) null else frame.nextCursor,
                        hasMore = frame.hasMore && !limitReached,
                        loadingOlder = false,
                        olderLoadError = null,
                        historyLimitReached = limitReached
                    )
                }
                is ConversationFrame.Error -> {
                    if (frame.code == "cursor_expired") restartNow()
                    else uiState.value = uiState.value.copy(loadingOlder = false, olderLoadError = frame.message)
                }
                else -> uiState.value = uiState.value.copy(
                    loadingOlder = false,
                    olderLoadError = "Earlier messages could not be loaded."
                )
            }
        }
    }

    private fun respondApproval(value: ConversationItem, choice: ConversationChoice) {
        val revision = value.revision ?: return
        val running = value.copy(state = "running", title = "Sending approval…")
        uiState.value = uiState.value.copy(items = mergeConversationItems(uiState.value.items, listOf(running)))
        runOneShot(conversationCommand("approve", listOf(
            "--approval", value.id,
            "--choice", choice.id,
            "--revision", revision,
            "--idempotency-key", UUID.randomUUID().toString()
        ))) { output ->
            val delivered = runCatching { JSONObject(output.lineSequence().last { it.isNotBlank() }).optString("status") == "delivered" }.getOrDefault(false)
            val updated = value.copy(state = if (delivered) "complete" else "error", title = if (delivered) "Approval sent" else "Approval changed—open Terminal")
            uiState.value = uiState.value.copy(items = mergeConversationItems(uiState.value.items, listOf(updated)))
        }
    }

    private fun refreshDirectories() {
        if (uiState.value.sourceMode != "shell") return
        if (localSession) {
            val token = generation
            val cwd = uiState.value.cwd.ifBlank { home.absolutePath }
            thread(name = "native-session-local-directory", isDaemon = true) {
                val snapshot = runCatching {
                    val directory = File(cwd)
                    val allDirectories = directory.listFiles().orEmpty().filter { it.isDirectory }
                    val entries = allDirectories
                        .sortedWith(compareBy<File>({ !it.name.startsWith(".") }, { it.name.lowercase() }))
                        .take(200)
                        .map { NativeDirectoryEntry(it.name.take(512), it.isSymbolicLink()) }
                    Triple(directory.canonicalPath, entries, allDirectories.size > 200)
                }.getOrNull()
                main.post {
                    if (token == generation && snapshot != null) uiState.value = uiState.value.copy(
                        cwd = snapshot.first,
                        directories = snapshot.second,
                        directoryTruncated = snapshot.third
                    )
                }
            }
            return
        }
        val token = generation
        runOneShot(conversationCommand("directory")) { output ->
            val snapshot = runCatching { ConversationStreamParser.parseDirectory(output.lineSequence().last { it.isNotBlank() }) }.getOrNull() ?: return@runOneShot
            if (token == generation && snapshot.session == uiState.value.internalSession) {
                uiState.value = uiState.value.copy(cwd = snapshot.cwd, directories = snapshot.entries, directoryTruncated = snapshot.truncated)
            }
        }
    }

    private fun File.isSymbolicLink(): Boolean = runCatching {
        absoluteFile != canonicalFile
    }.getOrDefault(false)

    private fun openDirectory(name: String) {
        val target = when (name) {
            ".." -> "cd .."
            else -> "cd -- '${name.replace("'", "'\"'\"'")}'"
        }
        sendShellCommand(target)
        main.postDelayed(::refreshDirectories, 800)
    }

    private fun sendShellCommand(command: String) {
        val value = command.trim()
        if (value.isEmpty() || value.length > 32_768 || !activity.sendAgentFleetComposerText(value, true)) return
        val identifier = "shell-${UUID.randomUUID()}"
        pendingShellId = identifier
        val item = ConversationItem(
            id = identifier, kind = "shell_command", timestamp = "", role = "user", title = value,
            text = "", detail = "", state = "running", tool = "shell", attachments = emptyList(), choices = emptyList()
        )
        uiState.value = uiState.value.copy(items = mergeConversationItems(uiState.value.items, listOf(item)))
        uiState.value = uiState.value.copy(liveEventSerial = uiState.value.liveEventSerial + 1)
        main.postDelayed(::refreshDirectories, 800)
    }

    private fun runOneShot(command: List<String>, onResult: (String) -> Unit) {
        val token = generation
        thread(name = "native-session-action", isDaemon = true) {
            val output = runCatching {
                val process = environment(ProcessBuilder(command)).redirectErrorStream(false).start()
                val errorReader = thread(name = "native-session-action-stderr", isDaemon = true) {
                    process.errorStream.use { input ->
                        val chunk = ByteArray(4 * 1024)
                        var total = 0
                        while (total < 64 * 1024) {
                            val count = input.read(chunk)
                            if (count < 0) break
                            total += count
                        }
                    }
                }
                val buffer = ByteArrayOutputStream()
                process.inputStream.use { input ->
                    val chunk = ByteArray(8 * 1024)
                    while (buffer.size() <= 512 * 1024) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        buffer.write(chunk, 0, count)
                    }
                }
                if (!process.waitFor(12, TimeUnit.SECONDS)) process.destroyForcibly()
                errorReader.join(1_000)
                buffer.toString(Charsets.UTF_8.name())
            }.getOrDefault("")
            main.post { if (token == generation) onResult(output) }
        }
    }
}
