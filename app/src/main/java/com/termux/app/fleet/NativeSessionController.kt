package com.termux.app.fleet

import android.content.Intent
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.termux.app.AgentFleetTheme
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

interface NativeSessionHost {
    val nativeContext: Context
    val nativeInlineComposer: Boolean
    fun sendAgentFleetComposerText(text: String, appendEnter: Boolean): Boolean
    fun sendAgentFleetControlC(): Boolean
    fun sendAgentFleetKey(key: String): Boolean
    fun pickAgentFleetImages()
    fun setAgentFleetNativeView(nativeAvailable: Boolean, nativeView: Boolean, automaticTerminal: Boolean, aiComposer: Boolean)
    fun closeAgentFleetSessionTab()
}

class NativeSessionController @JvmOverloads constructor(
    private val activity: NativeSessionHost,
    private val composeView: ComposeView,
    private val showChrome: Boolean = true,
    private val terminalChromeView: ComposeView? = null
) {
    private companion object {
        const val HISTORY_PAGE_SIZE = 20
        const val MAX_LOADED_ITEMS = 2_000
    }

    private val main = Handler(Looper.getMainLooper())
    private val appRoot = requireNotNull(activity.nativeContext.filesDir.parentFile) {
        "Agent Fleet data directory is unavailable"
    }
    private val prefix = File(appRoot, "files/usr")
    private val home = File(appRoot, "files/home")
    private val fleetRuntime = FleetRuntime(activity.nativeContext.applicationContext)
    private val uiState = mutableStateOf(NativeSessionUiState("Session", "", ""))
    @Volatile private var fleetSnapshot: FleetSnapshot? = null
    @Volatile private var visible = false
    @Volatile private var generation = 0
    private var enabled = false
    private var aiComposer = false
    private var localSession = false
    private var workspaceSessionId = ""
    private var composerTarget = ""
    @Volatile private var streamProcess: Process? = null
    @Volatile private var retryBlocked = false
    private var retryIndex = 0
    private var lastFallbackText = ""
    private var pendingShellId: String? = null
    private var dismissedAttentionId: String? = null
    @Volatile private var modelRequestActive = false
    private val modelPoll = object : Runnable {
        override fun run() {
            if (visible && enabled && !localSession) refreshModelControl(includeCatalog = false, showLoading = false)
            if (visible) main.postDelayed(this, 10_000)
        }
    }

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
                    onQuestion = ::respondQuestion,
                    onShellCommand = ::sendShellCommand,
                    onShellKey = activity::sendAgentFleetKey,
                    onDirectory = ::openDirectory,
                    onRefreshDirectory = ::refreshDirectories,
                    onControlC = { activity.sendAgentFleetControlC() },
                    onCloseSession = ::closeSession,
                    onKillSession = ::killSession,
                    onScheduleContinue = ::scheduleLimitContinue,
                    onDismissAttention = ::dismissAttention,
                    onComposerText = activity::sendAgentFleetComposerText,
                    onAttach = activity::pickAgentFleetImages,
                    inlineComposer = activity.nativeInlineComposer,
                    showChrome = showChrome,
                    onRefreshModel = { refreshModelControl(includeCatalog = true, showLoading = true) },
                    onSetModel = ::setModelControl,
                    onCancelModel = ::cancelModelControl
                )
            }
        }
        terminalChromeView?.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        terminalChromeView?.setContent {
            AgentFleetTheme {
                AgentFleetTerminalSessionChrome(
                    state = uiState.value,
                    onShowNative = ::showNative,
                    onRefreshModel = { refreshModelControl(includeCatalog = true, showLoading = true) },
                    onSetModel = ::setModelControl,
                    onCancelModel = ::cancelModelControl
                )
            }
        }
    }

    fun bind(intent: Intent?) {
        generation++
        stopProcess()
        FleetSnapshotStore.removeObserver(this)
        fleetSnapshot = null
        dismissedAttentionId = null
        retryBlocked = false
        val host = intent?.getStringExtra(AgentFleetContract.EXTRA_HOST_ID).orEmpty()
        val session = intent?.getStringExtra(AgentFleetContract.EXTRA_INTERNAL_SESSION).orEmpty()
        val label = intent?.getStringExtra(AgentFleetContract.EXTRA_SESSION_NAME).orEmpty().ifBlank { session }
        aiComposer = intent?.getBooleanExtra(AgentFleetContract.EXTRA_COMPOSE_INPUT, false) == true
        localSession = intent?.getBooleanExtra(AgentFleetContract.EXTRA_LOCAL_SESSION, false) == true
        workspaceSessionId = intent?.getStringExtra(AgentFleetContract.EXTRA_WORKSPACE_SESSION_ID).orEmpty()
        val project = intent?.getStringExtra(AgentFleetContract.EXTRA_PROJECT).orEmpty()
        composerTarget = listOf(host, project, if (localSession) "local" else session).joinToString(":")
        enabled = intent?.getBooleanExtra(AgentFleetContract.EXTRA_NATIVE_SESSION, false) == true &&
            NativeSessionSettings.isEnabled(activity.nativeContext) && (localSession || (host.isNotBlank() && session.isNotBlank()))
        uiState.value = NativeSessionUiState(
            label.ifBlank { if (localSession) "Local shell" else "Session" },
            host,
            if (localSession) "local" else session,
            adapter = if (localSession) "shell" else "connecting",
            sourceMode = if (localSession) "shell" else "ai",
            connection = if (localSession) "Live" else "Connecting…",
            cwd = if (localSession) home.absolutePath else ""
        )
        updateComposerState()
        val requestedSurface = intent?.getStringExtra(AgentFleetContract.EXTRA_INITIAL_SURFACE)
        val storedSurface = if (workspaceSessionId.isNotBlank()) {
            DrawerSessionStore(activity.nativeContext.applicationContext).surfaceFor(workspaceSessionId)
        } else DrawerSessionSurface.Native
        val initialMode = when {
            !enabled -> NativeViewMode.ManualTerminal
            requestedSurface == AgentFleetContract.SURFACE_TERMINAL -> NativeViewMode.ManualTerminal
            requestedSurface == AgentFleetContract.SURFACE_NATIVE -> NativeViewMode.Native
            storedSurface == DrawerSessionSurface.Terminal -> NativeViewMode.ManualTerminal
            else -> NativeViewMode.Native
        }
        applyViewMode(initialMode)
        if (enabled && localSession) refreshDirectories()
        if (visible && enabled) {
            observeFleet()
        }
    }

    fun onStart() {
        visible = true
        if (enabled) observeFleet()
        if (shouldRunStream() && streamProcess == null) startStream()
        main.removeCallbacks(modelPoll)
        main.post(modelPoll)
    }

    fun onStop() {
        visible = false
        LocalSuggestionRuntime.shutdown(activity.nativeContext.applicationContext)
        FleetSnapshotStore.removeObserver(this)
        generation++
        main.removeCallbacksAndMessages(null)
        stopProcess()
        modelRequestActive = false
    }

    fun close() {
        visible = false
        FleetSnapshotStore.removeObserver(this)
        generation++
        main.removeCallbacksAndMessages(null)
        stopProcess()
        modelRequestActive = false
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
            NativeViewMode.Native -> applyViewMode(NativeViewMode.ManualTerminal, persist = true)
            NativeViewMode.AutomaticTerminal, NativeViewMode.ManualTerminal -> applyViewMode(NativeViewMode.Native, persist = true)
        }
    }

    fun showNative() {
        if (enabled) applyViewMode(NativeViewMode.Native, persist = true)
    }

    fun showPendingQuestion() {
        if (!enabled) return
        val pending = activePendingAction(uiState.value.items)?.takeIf { it.kind == "question" } ?: return
        applyViewMode(NativeViewMode.Native)
        uiState.value = uiState.value.copy(
            focusQuestionId = pending.id,
            focusQuestionSerial = uiState.value.focusQuestionSerial + 1
        )
    }

    private fun updateComposerState() {
        val pendingAction = activePendingAction(uiState.value.items)
        val pendingQuestion = pendingAction?.takeIf { it.kind == "question" }?.id.orEmpty()
        val native = enabled && uiState.value.viewMode == NativeViewMode.Native
        AgentFleetComposer.updateNativeState(
            composerTarget,
            uiState.value.interactionMode,
            pendingQuestion,
            native,
            uiState.value.items,
            uiState.value.revision,
            uiState.value.liveEventSerial
        )
        activity.setAgentFleetNativeView(
            enabled,
            native,
            uiState.value.viewMode == NativeViewMode.AutomaticTerminal,
            aiComposer && pendingAction == null
        )
    }

    private fun applyViewMode(mode: NativeViewMode, persist: Boolean = false) {
        val previousMode = uiState.value.viewMode
        uiState.value = uiState.value.copy(viewMode = mode)
        if (persist && workspaceSessionId.isNotBlank() && mode != NativeViewMode.AutomaticTerminal) {
            DrawerSessionStore(activity.nativeContext.applicationContext).setSurface(
                workspaceSessionId,
                if (mode == NativeViewMode.Native) DrawerSessionSurface.Native else DrawerSessionSurface.Terminal
            )
        }
        val native = enabled && mode == NativeViewMode.Native
        composeView.visibility = if (native) View.VISIBLE else View.GONE
        terminalChromeView?.visibility = if (enabled && !native) View.VISIBLE else View.GONE
        updateComposerState()
        if (enabled && !localSession) {
            if (mode == NativeViewMode.Native) {
                if (shouldRunStream()) startStream()
            } else if (previousMode == NativeViewMode.Native) {
                generation++
                stopProcess()
            }
        }
    }

    fun isManagedSession(): Boolean = enabled

    fun isNativeViewVisible(): Boolean = enabled && uiState.value.viewMode == NativeViewMode.Native

    private fun shouldRunStream(): Boolean = shouldRunConversationStream(
        visible, enabled, localSession, uiState.value.viewMode
    )

    private fun observeFleet() {
        FleetSnapshotStore.observe(activity.nativeContext.applicationContext, this) { state ->
            if (!visible || !enabled) return@observe
            when (state) {
                is FleetLoadState.Ready -> applyFleetSnapshot(state.snapshot)
                is FleetLoadState.Unavailable -> if (uiState.value.attention == null) {
                    uiState.value = uiState.value.copy(attentionError = state.reason)
                }
                FleetLoadState.Loading -> Unit
            }
        }
    }

    private fun applyFleetSnapshot(snapshot: FleetSnapshot) {
        fleetSnapshot = snapshot
        val sessionId = "${uiState.value.hostId}:${uiState.value.internalSession}"
        val hiddenId = dismissedAttentionId
        if (hiddenId != null && snapshot.attention.none { it.id == hiddenId }) dismissedAttentionId = null
        val attention = activeAttentionForSession(snapshot, sessionId, dismissedAttentionId)
        val sameAttention = attention?.id == uiState.value.attention?.id
        uiState.value = uiState.value.copy(
            attention = attention,
            attentionBusy = if (sameAttention) uiState.value.attentionBusy else false,
            attentionError = if (sameAttention) uiState.value.attentionError else null
        )
        refreshModelControl(includeCatalog = false, showLoading = false)
    }

    private fun currentFleetSession(): FleetSession? = fleetSnapshot?.sessions?.firstOrNull {
        it.hostId == uiState.value.hostId && it.internalName == uiState.value.internalSession
    }

    private fun refreshModelControl(includeCatalog: Boolean, showLoading: Boolean) {
        val session = currentFleetSession() ?: return
        if (session.tool !in setOf("codex", "claude", "copilot") || modelRequestActive) return
        modelRequestActive = true
        if (showLoading) uiState.value = uiState.value.copy(modelControlLoading = true, modelControlError = null)
        val token = generation
        thread(name = "native-session-model-get", isDaemon = true) {
            val result = runCatching { fleetRuntime.getSessionModel(session, includeCatalog) }
            main.post {
                modelRequestActive = false
                if (token != generation || !visible) return@post
                result.onSuccess { next ->
                    val previous = uiState.value.modelControl
                    val merged = if (next.catalog == null && previous?.catalog != null) {
                        next.copy(catalog = previous.catalog, customAllowed = previous.customAllowed)
                    } else next
                    uiState.value = uiState.value.copy(
                        modelControl = merged, modelControlLoading = false, modelControlError = null
                    )
                }.onFailure { error ->
                    if (showLoading) uiState.value = uiState.value.copy(
                        modelControlLoading = false,
                        modelControlError = error.message ?: "Model options could not be loaded."
                    )
                }
            }
        }
    }

    private fun setModelControl(modelId: String, effortId: String, custom: Boolean, acknowledged: Boolean) {
        val session = currentFleetSession() ?: return
        val current = uiState.value.modelControl ?: return
        if (modelRequestActive) return
        modelRequestActive = true
        uiState.value = uiState.value.copy(modelControlLoading = true, modelControlError = null)
        val token = generation
        thread(name = "native-session-model-set", isDaemon = true) {
            val result = runCatching {
                fleetRuntime.setSessionModel(
                    session, modelId, effortId, custom, current.configRevision, acknowledged
                )
            }
            main.post {
                modelRequestActive = false
                if (token != generation || !visible) return@post
                result.onSuccess { next ->
                    uiState.value = uiState.value.copy(
                        modelControl = next.copy(
                            catalog = next.catalog ?: current.catalog,
                            customAllowed = if (next.catalog == null) current.customAllowed else next.customAllowed
                        ),
                        modelControlLoading = false,
                        modelControlError = null
                    )
                    main.postDelayed({ refreshModelControl(includeCatalog = false, showLoading = false) }, 1_000)
                }.onFailure { error ->
                    uiState.value = uiState.value.copy(
                        modelControlLoading = false,
                        modelControlError = error.message ?: "The model change could not be queued."
                    )
                }
            }
        }
    }

    private fun cancelModelControl() {
        val session = currentFleetSession() ?: return
        val current = uiState.value.modelControl?.takeIf { it.pending != null } ?: return
        if (modelRequestActive) return
        modelRequestActive = true
        uiState.value = uiState.value.copy(modelControlLoading = true, modelControlError = null)
        val token = generation
        thread(name = "native-session-model-cancel", isDaemon = true) {
            val result = runCatching { fleetRuntime.cancelSessionModel(session, current.configRevision) }
            main.post {
                modelRequestActive = false
                if (token != generation || !visible) return@post
                result.onSuccess { next ->
                    uiState.value = uiState.value.copy(
                        modelControl = next.copy(catalog = current.catalog, customAllowed = current.customAllowed),
                        modelControlLoading = false,
                        modelControlError = null
                    )
                }.onFailure { error ->
                    uiState.value = uiState.value.copy(
                        modelControlLoading = false,
                        modelControlError = error.message ?: "The queued change could not be cancelled."
                    )
                }
            }
        }
    }

    private fun closeSession() {
        FleetSnapshotStore.removeObserver(this)
        activity.closeAgentFleetSessionTab()
    }

    private fun killSession() {
        if (localSession) {
            closeSession()
            return
        }
        val snapshot = fleetSnapshot
        val session = snapshot?.sessions?.firstOrNull {
            it.hostId == uiState.value.hostId && it.internalName == uiState.value.internalSession
        }
        if (snapshot == null || session == null) {
            uiState.value = uiState.value.copy(attentionError = "The session changed. Refresh and try again.")
            FleetSnapshotStore.refresh()
            return
        }
        uiState.value = uiState.value.copy(attentionBusy = true, attentionError = null)
        thread(name = "native-session-kill", isDaemon = true) {
            val result = runCatching { fleetRuntime.killSession(snapshot, session) }
            main.post {
                result.onSuccess {
                    FleetSnapshotStore.publish(it)
                    closeSession()
                }.onFailure {
                    uiState.value = uiState.value.copy(
                        attentionBusy = false,
                        attentionError = it.message ?: "The session could not be killed."
                    )
                    FleetSnapshotStore.refresh()
                }
            }
        }
    }

    private fun scheduleLimitContinue(deliverAtEpochMs: Long) {
        val snapshot = fleetSnapshot
        val attention = uiState.value.attention
        val session = snapshot?.sessions?.firstOrNull { it.id == attention?.sessionId }
        if (snapshot == null || attention == null || session == null) {
            uiState.value = uiState.value.copy(attentionError = "This limit action changed. Refresh and try again.")
            FleetSnapshotStore.refresh()
            return
        }
        uiState.value = uiState.value.copy(attentionBusy = true, attentionError = null)
        thread(name = "native-session-limit-schedule", isDaemon = true) {
            val result = runCatching {
                fleetRuntime.scheduleContinue(snapshot, session, deliverAtEpochMs, attention.id)
            }
            main.post { finishAttentionMutation(result) }
        }
    }

    private fun dismissAttention() {
        val snapshot = fleetSnapshot
        val attention = uiState.value.attention
        if (snapshot == null || attention == null) {
            FleetSnapshotStore.refresh()
            return
        }
        dismissedAttentionId = attention.id
        uiState.value = uiState.value.copy(attention = null, attentionBusy = false, attentionError = null)
        val idempotencyKey = UUID.randomUUID().toString()
        thread(name = "native-session-limit-dismiss", isDaemon = true) {
            var result = runCatching { fleetRuntime.dismissAttention(snapshot, attention, idempotencyKey) }
            val failure = result.exceptionOrNull() as? FleetUnavailableException
            if (failure?.code == "stale_revision") {
                result = runCatching {
                    val fresh = fleetRuntime.loadSnapshot()
                    val current = fresh.attention.firstOrNull {
                        it.id == attention.id && it.hostId == attention.hostId && it.sessionId == attention.sessionId &&
                            it.state in setOf("detected", "offering", "offered")
                    }
                    if (current == null) fresh else fleetRuntime.dismissAttention(fresh, current, idempotencyKey)
                }
            }
            main.post { finishAttentionDismiss(result, attention) }
        }
    }

    private fun finishAttentionDismiss(result: Result<FleetSnapshot>, attention: FleetAttention) {
        result.onSuccess { FleetSnapshotStore.publish(it) }.onFailure {
            if (dismissedAttentionId == attention.id) dismissedAttentionId = null
            uiState.value = uiState.value.copy(
                attention = attention,
                attentionBusy = false,
                attentionError = it.message ?: "The limit action could not be completed."
            )
            FleetSnapshotStore.refresh()
        }
    }

    private fun finishAttentionMutation(result: Result<FleetSnapshot>) {
        result.onSuccess { FleetSnapshotStore.publish(it) }.onFailure {
            uiState.value = uiState.value.copy(
                attentionBusy = false,
                attentionError = it.message ?: "The limit action could not be completed."
            )
            FleetSnapshotStore.refresh()
        }
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
        enableTermuxExec(environment(), prefix)
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
        if (!shouldRunStream() || streamProcess != null) return
        val token = generation
        uiState.value = uiState.value.copy(connection = if (retryIndex == 0) "Connecting…" else "Reconnecting…", error = null)
        thread(name = "native-session-stream", isDaemon = true) {
            try {
                val process = environment(ProcessBuilder(conversationCommand("stream", listOf("--limit", HISTORY_PAGE_SIZE.toString())))).start()
                if (token != generation || !shouldRunStream()) {
                    process.destroyForciblyCompat()
                    return@thread
                }
                streamProcess = process
                thread(name = "native-session-stderr", isDaemon = true) {
                    drainErrorStream(process)
                }
                process.inputStream.bufferedReader().use { reader ->
                    while (token == generation) {
                        val line = reader.readLine() ?: break
                        if (line.length > 256 * 1024) {
                            postError(token, "The host sent an oversized conversation frame.")
                            process.destroyForciblyCompat()
                            break
                        }
                        val frame = runCatching { ConversationStreamParser.parseFrame(line) }.getOrNull()
                        if (frame == null) {
                            val message = if (line.contains("\"protocolVersion\":1")) {
                                retryBlocked = true
                                "Native view upgrade required. Update wtmux on this host; Terminal remains available."
                            } else {
                                "The host sent an invalid conversation frame."
                            }
                            postError(token, message)
                            process.destroyForciblyCompat()
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
        if (!shouldRunStream() || token != generation) return
        if (uiState.value.error == null) uiState.value = uiState.value.copy(connection = "Disconnected")
        scheduleRetry(token)
    }

    private fun scheduleRetry(token: Int) {
        if (!shouldRunStream() || retryBlocked || token != generation) return
        val delays = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
        val delay = delays[retryIndex.coerceAtMost(delays.lastIndex)]
        retryIndex = (retryIndex + 1).coerceAtMost(delays.lastIndex)
        main.postDelayed({ if (shouldRunStream() && token == generation && streamProcess == null) startStream() }, delay)
    }

    private fun restartNow() {
        generation++
        stopProcess()
        retryBlocked = false
        retryIndex = 0
        uiState.value = uiState.value.copy(
            error = null,
            olderLoadError = null,
            loadingOlder = false,
            historyLimitReached = false,
            connection = "Connecting…"
        )
        if (shouldRunStream()) startStream()
    }

    private fun stopProcess() {
        val process = streamProcess
        process?.destroy()
        if (process?.isAliveCompat() == true) process.destroyForciblyCompat()
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
                    interactionMode = frame.interactionMode,
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
                updateComposerState()
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
                    updateComposerState()
                }
            }
            is ConversationFrame.Status -> {
                if (frame.session != uiState.value.internalSession) return
                if (frame.status == "reload_required") restartNow()
                else {
                    val update = conversationStatusUpdate(
                        uiState.value.connection,
                        uiState.value.interactionMode,
                        frame.status,
                        frame.interactionMode
                    ) ?: return
                    uiState.value = uiState.value.copy(connection = update.first, interactionMode = update.second)
                }
                updateComposerState()
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
        runOneShot(conversationCommand("stream", listOf("--cursor", cursor, "--limit", requestLimit.toString(), "--no-follow"))) { action ->
            val frame = runCatching { ConversationStreamParser.parseFrame(action.stdout.lineSequence().first { it.isNotBlank() }) }.getOrNull()
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
        updateComposerState()
        runOneShot(conversationCommand("approve", listOf(
            "--approval", value.id,
            "--choice", choice.id,
            "--revision", revision,
            "--idempotency-key", UUID.randomUUID().toString()
        ))) { action ->
            val delivered = action.exitCode == 0 && runCatching { JSONObject(action.stdout.lineSequence().last { it.isNotBlank() }).optString("status") == "delivered" }.getOrDefault(false)
            val updated = value.copy(
                state = if (delivered) "complete" else "error",
                title = if (delivered) "Approval sent" else "Approval not sent",
                text = if (delivered) value.text else actionError(action, "The approval was not accepted. Refresh or open Terminal.")
            )
            uiState.value = uiState.value.copy(items = mergeConversationItems(uiState.value.items, listOf(updated)))
            updateComposerState()
        }
    }

    private fun respondQuestion(value: ConversationItem, answers: List<ConversationAnswer>) {
        val revision = value.revision ?: return
        val payload = JSONObject().put("answers", JSONArray().apply {
            answers.forEach { answer ->
                put(JSONObject().apply {
                    put("questionId", answer.questionId)
                    put("choiceIds", JSONArray(answer.choiceIds))
                    put("text", answer.text)
                })
            }
        }).toString()
        if (payload.toByteArray().size > 32 * 1024) {
            uiState.value = uiState.value.copy(items = mergeConversationItems(
                uiState.value.items,
                listOf(value.copy(state = "error", title = "Answers are too long—shorten them"))
            ))
            updateComposerState()
            return
        }
        val encoded = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val running = value.copy(state = "running", title = "Sending answer…", answers = answers, text = "")
        uiState.value = uiState.value.copy(items = mergeConversationItems(uiState.value.items, listOf(running)))
        updateComposerState()
        runOneShot(conversationCommand("answer", listOf(
            "--question", value.id,
            "--revision", revision,
            "--answers-b64", encoded,
            "--idempotency-key", UUID.randomUUID().toString()
        )), timeoutSeconds = 30) { action ->
            val delivered = action.exitCode == 0 && runCatching {
                JSONObject(action.stdout.lineSequence().last { it.isNotBlank() }).let {
                    it.optString("type") == "question.response" && it.optString("status") == "delivered"
                }
            }.getOrDefault(false)
            val existing = uiState.value.items.firstOrNull { it.id == value.id } ?: value
            if (!delivered && existing.state != "complete") {
                uiState.value = uiState.value.copy(items = mergeConversationItems(
                    uiState.value.items,
                    listOf(existing.copy(
                        state = "error",
                        title = "Answer not sent",
                        text = actionError(action, "The answer was not accepted. Review it, then retry or open Terminal."),
                        answers = answers
                    ))
                ))
                updateComposerState()
            } else if (delivered) {
                main.postDelayed({
                    val current = uiState.value.items.firstOrNull { it.id == value.id }
                    if (current?.state == "running") {
                        uiState.value = uiState.value.copy(items = mergeConversationItems(
                            uiState.value.items,
                            listOf(current.copy(state = "error", title = "Answer unconfirmed—check again"))
                        ))
                        updateComposerState()
                    }
                }, 20_000)
            }
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
        runOneShot(conversationCommand("directory")) { action ->
            val snapshot = runCatching { ConversationStreamParser.parseDirectory(action.stdout.lineSequence().last { it.isNotBlank() }) }.getOrNull() ?: return@runOneShot
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

    private data class ActionResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

    private fun actionError(result: ActionResult, fallback: String): String {
        val structured = result.stdout.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching { JSONObject(line).optJSONObject("error")?.optString("message") }.getOrNull()
        }.lastOrNull { !it.isNullOrBlank() }
        val value = structured ?: result.stderr.lineSequence().firstOrNull { it.isNotBlank() }
            ?: if (result.timedOut) "The host did not confirm the action before it timed out." else fallback
        return value.filterNot { it.isISOControl() }.take(360).ifBlank { fallback }
    }

    private fun runOneShot(command: List<String>, timeoutSeconds: Long = 12, onResult: (ActionResult) -> Unit) {
        val token = generation
        thread(name = "native-session-action", isDaemon = true) {
            val output = runCatching {
                val process = environment(ProcessBuilder(command)).redirectErrorStream(false).start()
                val errorBuffer = ByteArrayOutputStream()
                val errorReader = thread(name = "native-session-action-stderr", isDaemon = true) {
                    process.errorStream.use { input ->
                        val chunk = ByteArray(4 * 1024)
                        while (errorBuffer.size() <= 64 * 1024) {
                            val count = input.read(chunk)
                            if (count < 0) break
                            errorBuffer.write(chunk, 0, count)
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
                val finished = process.waitForCompat(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) process.destroyForciblyCompat()
                if (!finished) process.waitForCompat(2, TimeUnit.SECONDS)
                errorReader.join(1_000)
                ActionResult(
                    if (finished) process.exitValue() else -1,
                    buffer.toString(Charsets.UTF_8.name()),
                    errorBuffer.toString(Charsets.UTF_8.name()),
                    !finished
                )
            }.getOrElse { ActionResult(-1, "", it.message.orEmpty(), false) }
            main.post { if (token == generation) onResult(output) }
        }
    }

    private fun drainErrorStream(process: Process) {
        runCatching {
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
    }
}

internal fun activeAttentionForSession(
    snapshot: FleetSnapshot,
    sessionId: String,
    hiddenAttentionId: String? = null
): FleetAttention? = snapshot.attention.firstOrNull {
    it.sessionId == sessionId && it.id != hiddenAttentionId &&
        it.state in setOf("detected", "offering", "offered")
}
