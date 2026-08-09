package com.termux.app.fleet

import android.content.Intent
import android.net.Uri
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.app.TermuxActivity
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object AgentFleetComposer {
    private var currentTarget = ""
    private var nativeState by mutableStateOf(AgentFleetComposerNativeState())
    private val targetStates = linkedMapOf<String, ComposerTargetState>()
    private val targetRoutes = mutableMapOf<String, ComposerTargetRoute>()
    private val uploadOwner = AgentFleetComposerUploadOwner()
    private val externalImageRequests = AgentFleetExternalImageRequestOwner()

    private class ComposerTargetState {
        val attachments = mutableStateListOf<String>()
        var uploading by mutableStateOf(false)
        var uploadError by mutableStateOf<String?>(null)
    }

    @JvmStatic
    fun updateNativeState(
        target: String,
        mode: String,
        pendingQuestionId: String,
        visible: Boolean,
        items: List<ConversationItem>,
        revision: String,
        liveEventSerial: Long
    ) {
        nativeState = AgentFleetComposerNativeState(
            target = target,
            interactionMode = mode,
            pendingQuestionId = pendingQuestionId,
            visible = visible,
            items = items,
            revision = revision,
            liveEventSerial = liveEventSerial
        )
    }

    internal fun nativeStateForTest(): AgentFleetComposerNativeState = nativeState

    @JvmStatic
    fun bind(activity: TermuxActivity, view: ComposeView, enabled: Boolean) {
        if (!enabled) {
            clearCurrentTarget()
            view.visibility = View.GONE
            activity.terminalToolbarViewPager.visibility = if (activity.preferences.shouldShowTerminalToolbar()) View.VISIBLE else View.GONE
            return
        }
        val route = ComposerTargetRoute(
            activity.intent.getStringExtra(AgentFleetContract.EXTRA_HOST_ID).orEmpty(),
            activity.intent.getStringExtra(AgentFleetContract.EXTRA_PROJECT).orEmpty(),
            activity.intent.getStringExtra(AgentFleetContract.EXTRA_INTERNAL_SESSION).orEmpty()
        )
        val target = agentFleetComposerTarget(route.host, route.project, route.session)
        val targetState = activateTarget(target, route)
        activity.intent.getStringArrayListExtra(AgentFleetContract.EXTRA_SHARED_IMAGES)?.takeIf { it.isNotEmpty() }?.let { values ->
            activity.intent.removeExtra(AgentFleetContract.EXTRA_SHARED_IMAGES)
            view.post { handleImageUris(activity, target, values.map(Uri::parse)) }
        }
        activity.terminalToolbarViewPager.visibility = View.GONE
        view.visibility = View.VISIBLE
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            MaterialTheme(colorScheme = ComposerColors) {
                AgentFleetComposerContent(
                    target = target,
                    nativeState = nativeState,
                    attachments = targetState.attachments,
                    uploading = targetState.uploading,
                    uploadError = targetState.uploadError,
                    onShowPendingQuestion = activity::showAgentFleetPendingQuestion,
                    onAttach = activity::pickAgentFleetImages,
                    onCamera = activity::pickAgentFleetCamera,
                    onRemoveAttachment = targetState.attachments::remove,
                    onComposerText = { text, appendEnter ->
                        activity.sendAgentFleetComposerText(text, appendEnter).also { sent ->
                            if (sent) targetState.attachments.clear()
                        }
                    }
                )
            }
        }
    }

    @JvmStatic
    fun handleImageResult(
        activity: TermuxActivity,
        data: Intent,
        request: AgentFleetExternalImageRequest?
    ) {
        val uris = buildList {
            data.data?.let(::add)
            data.clipData?.let { clip ->
                repeat(clip.itemCount) { index -> add(clip.getItemAt(index).uri) }
            }
        }
        externalImageRequests.consume(request)?.let { target ->
            handleImageUris(activity, target, uris)
        }
    }

    @JvmStatic
    fun handleCapturedImage(
        activity: TermuxActivity,
        uri: Uri,
        sourcePath: String,
        request: AgentFleetExternalImageRequest?
    ) {
        externalImageRequests.consume(request)?.let { target ->
            handleImageUris(activity, target, listOf(uri), sourcePath)
        } ?: deleteOwnedCameraSource(activity, sourcePath)
    }

    @JvmStatic
    @Synchronized
    fun beginExternalImageRequest(camera: Boolean): AgentFleetExternalImageRequest? {
        val target = currentTarget
        if (target.isBlank()) return null
        return externalImageRequests.begin(target, camera)
    }

    @JvmStatic
    fun restoreExternalImageRequest(request: AgentFleetExternalImageRequest?): Boolean =
        externalImageRequests.restore(request)

    @JvmStatic
    fun cancelExternalImageRequest(request: AgentFleetExternalImageRequest?) {
        externalImageRequests.cancel(request)
    }

    fun uploadWorkspaceImages(
        context: Context,
        sourceUris: List<Uri>,
        session: FleetSession,
        maxCount: Int,
        cancellation: AgentFleetUploadCancellation,
        onComplete: (AgentFleetWorkspaceImageUploadResult) -> Unit
    ) {
        val main = Handler(Looper.getMainLooper())
        Thread({
            val result = agentFleetUploadSequentially(
                sourceUris.distinct(),
                maxCount.coerceIn(0, MAX_ATTACHMENTS),
                shouldContinue = cancellation::isActive
            ) { uri ->
                    val local = copyImage(context, uri)
                    try {
                        sendImage(context, local, session.hostId, session.project, session.internalName)
                    } finally {
                        local.delete()
                    }
                }
            main.post {
                onComplete(
                    AgentFleetWorkspaceImageUploadResult(
                        uploadedPaths = result.completed,
                        error = result.error,
                        cancelled = result.cancelled
                    )
                )
            }
        }, "agent-fleet-workspace-image-upload").start()
    }

    private fun handleImageUris(
        activity: TermuxActivity,
        target: String,
        sourceUris: List<Uri>,
        ownedCameraSource: String? = null
    ) {
        val state = stateFor(target)
        val uris = sourceUris.distinct().take((MAX_ATTACHMENTS - state.attachments.size).coerceAtLeast(0))
        if (uris.isEmpty()) {
            ownedCameraSource?.let { deleteOwnedCameraSource(activity, it) }
            return
        }
        val route = synchronized(this) { targetRoutes[target] }
        if (route == null || route.host.isBlank() || route.project.isBlank() || route.session.isBlank()) {
            state.uploadError = "This terminal tab is missing its fleet image target."
            ownedCameraSource?.let { deleteOwnedCameraSource(activity, it) }
            return
        }
        val ticket = uploadOwner.begin(target)
        state.uploading = true
        state.uploadError = null
        Thread({
            val uploaded = mutableListOf<String>()
            var failure: String? = null
            try {
                for (uri in uris) {
                    val localResult = runCatching { copyImage(activity, uri) }
                    if (localResult.isFailure) {
                        failure = localResult.exceptionOrNull()?.message ?: "Image import failed."
                        break
                    }
                    val local = localResult.getOrThrow()
                    val remote = try {
                        runCatching {
                            sendImage(activity, local, route.host, route.project, route.session)
                        }.getOrElse { error ->
                            failure = error.message ?: "Image upload failed. Refresh the session and retry."
                            null
                        }
                    } finally {
                        local.delete()
                    }
                    if (remote == null) break
                    uploaded += remote
                }
            } finally {
                ownedCameraSource?.let { deleteOwnedCameraSource(activity, it) }
            }
            activity.runOnUiThread {
                if (uploadOwner.finish(ticket)) {
                    state.attachments += uploaded
                    state.uploadError = failure
                    state.uploading = false
                }
            }
        }, "agent-fleet-image-upload").start()
    }

    @Synchronized
    private fun stateFor(target: String): ComposerTargetState {
        targetStates.remove(target)?.let { existing ->
            targetStates[target] = existing
            return existing
        }
        while (targetStates.size >= MAX_TARGET_STATES) {
            val removableTarget = agentFleetComposerTargetToEvict(
                targetStates.map { (key, value) ->
                    AgentFleetComposerRetentionState(
                        target = key,
                        uploading = value.uploading,
                        hasAttachments = value.attachments.isNotEmpty()
                    )
                },
                protectedTargets = externalImageRequests.activeTargets() + currentTarget
            ) ?: break
            targetStates.remove(removableTarget)
            targetRoutes.remove(removableTarget)
            uploadOwner.invalidate(removableTarget)
        }
        return ComposerTargetState().also { targetStates[target] = it }
    }

    @Synchronized
    private fun activateTarget(target: String, route: ComposerTargetRoute): ComposerTargetState {
        currentTarget = target
        targetRoutes[target] = route
        return stateFor(target)
    }

    @Synchronized
    private fun clearCurrentTarget() {
        currentTarget = ""
        externalImageRequests.invalidateAll()
    }

    private fun deleteOwnedCameraSource(context: Context, sourcePath: String) {
        runCatching {
            val root = File(context.cacheDir, "agent-fleet-camera").canonicalFile
            val source = File(sourcePath).canonicalFile
            if (source.parentFile == root && source.name.endsWith(".jpg")) source.delete()
        }
    }

    internal fun copyImage(activity: Context, uri: Uri): File {
        val mime = activity.contentResolver.getType(uri).orEmpty()
        val appRoot = activity.filesDir.parentFile ?: error("App data directory is unavailable")
        val directory = File(appRoot, "files/home/.cache/agent-fleet/images")
        return activity.contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw AgentFleetImageException("The selected image is no longer available.")
            importAgentFleetImage(input, mime, directory)
        }
    }

    internal fun sendImage(activity: Context, local: File, host: String, project: String, session: String): String {
        val appRoot = activity.filesDir.parentFile ?: error("App data directory is unavailable")
        val home = File(appRoot, "files/home")
        val prefix = File(appRoot, "files/usr")
        val missingTools = runCatching { EmbeddedRuntimeManager(activity).repairImageUploadTools() }.getOrElse {
            throw AgentFleetImageException("The built-in image tools need repair. Open More, then repair the runtime.")
        }
        if (missingTools.isNotEmpty()) {
            throw AgentFleetImageException(
                "Built-in image tools are missing (${missingTools.take(3).joinToString()}). Open More, then repair the runtime."
            )
        }
        val wtmux = sequenceOf(File(home, ".local/bin/wtmux"), File(prefix, "bin/wtmux")).firstOrNull { it.canExecute() }
            ?: error("wtmux is unavailable")
        val bash = File(prefix, "bin/bash").takeIf { it.canExecute() }
            ?: error("bash is unavailable")
        var lastOutput: AgentFleetProcessOutput? = null
        repeat(2) { attempt ->
            val output = try {
                val process = ProcessBuilder(
                    bash.absolutePath, wtmux.absolutePath, "image", "send", local.absolutePath,
                    "--host", host, "--project", project, "--session", session
                ).directory(home).apply {
                    environment()["HOME"] = home.absolutePath
                    environment()["PREFIX"] = prefix.absolutePath
                    environment()["PATH"] = "${File(home, ".local/bin")}:${File(prefix, "bin")}"
                    enableTermuxExec(environment(), prefix)
                }.start()
                collectAgentFleetProcess(process, 30)
            } catch (failure: AgentFleetImageException) {
                if (attempt == 0 && failure.message.orEmpty().contains("timed out", ignoreCase = true)) return@repeat
                throw failure
            }
            lastOutput = output
            if (output.exitCode == 0) return parseSuccessfulAgentFleetImageOutput(output)
            if (!shouldRetryAgentFleetImageUpload(output, attempt)) {
                throw AgentFleetImageException(agentFleetImageUploadFailure(output.stderr, output.exitCode))
            }
            if (output.exitCode == 127) runCatching { EmbeddedRuntimeManager(activity).repairImageUploadTools() }
            Thread.sleep(150)
        }
        val failure = lastOutput
        throw AgentFleetImageException(
            if (failure == null) "Image upload timed out. Check the connection and retry."
            else agentFleetImageUploadFailure(failure.stderr, failure.exitCode)
        )
    }

}

internal data class AgentFleetComposerNativeState(
    val target: String = "",
    val interactionMode: String = "unknown",
    val pendingQuestionId: String = "",
    val visible: Boolean = false,
    val items: List<ConversationItem> = emptyList(),
    val revision: String = "",
    val liveEventSerial: Long = 0
)

internal data class AgentFleetComposerUploadTicket(val target: String, val generation: Long)

private data class ComposerTargetRoute(val host: String, val project: String, val session: String)

data class AgentFleetWorkspaceImageUploadResult(
    val uploadedPaths: List<String>,
    val error: Throwable?,
    val cancelled: Boolean
)

data class AgentFleetExternalImageRequest(
    val target: String,
    val camera: Boolean,
    val generation: Long
)

internal class AgentFleetExternalImageRequestOwner {
    private var nextGeneration = 0L
    private var pickerRequest: AgentFleetExternalImageRequest? = null
    private var cameraRequest: AgentFleetExternalImageRequest? = null

    @Synchronized
    fun begin(target: String, camera: Boolean): AgentFleetExternalImageRequest? {
        require(target.isNotBlank())
        if (current(camera) != null) return null
        val request = AgentFleetExternalImageRequest(
            target = target,
            camera = camera,
            generation = Math.addExact(nextGeneration, 1L)
        )
        nextGeneration = request.generation
        if (camera) cameraRequest = request else pickerRequest = request
        return request
    }

    @Synchronized
    fun restore(request: AgentFleetExternalImageRequest?): Boolean {
        if (request == null || request.target.isBlank() || request.generation <= 0L) return false
        val active = current(request.camera)
        if (active != null) return active == request
        replace(request.camera, request)
        nextGeneration = maxOf(nextGeneration, request.generation)
        return true
    }

    @Synchronized
    fun cancel(request: AgentFleetExternalImageRequest?): Boolean {
        if (request == null || current(request.camera) != request) return false
        replace(request.camera, null)
        return true
    }

    @Synchronized
    fun consume(request: AgentFleetExternalImageRequest?): String? {
        if (!cancel(request)) return null
        return request?.target
    }

    @Synchronized
    fun activeTargets(): Set<String> =
        listOfNotNull(pickerRequest?.target, cameraRequest?.target).toSet()

    @Synchronized
    fun invalidateAll() {
        pickerRequest = null
        cameraRequest = null
    }

    @Synchronized
    internal fun activeRequestCountForTest(): Int =
        listOfNotNull(pickerRequest, cameraRequest).size

    private fun current(camera: Boolean): AgentFleetExternalImageRequest? =
        if (camera) cameraRequest else pickerRequest

    private fun replace(camera: Boolean, request: AgentFleetExternalImageRequest?) {
        if (camera) cameraRequest = request else pickerRequest = request
    }
}

internal data class AgentFleetPartialUploadResult<T>(
    val completed: List<T>,
    val error: Throwable?,
    val cancelled: Boolean
)

class AgentFleetUploadCancellation {
    private val active = AtomicBoolean(true)

    fun cancel() {
        active.set(false)
    }

    fun isActive(): Boolean = active.get()
}

internal fun <S, T> agentFleetUploadSequentially(
    sources: List<S>,
    maxCount: Int,
    shouldContinue: () -> Boolean = { true },
    upload: (S) -> T
): AgentFleetPartialUploadResult<T> {
    val completed = mutableListOf<T>()
    var failure: Throwable? = null
    var cancelled = false
    for (source in sources.take(maxCount.coerceAtLeast(0))) {
        if (!shouldContinue()) {
            cancelled = true
            break
        }
        try {
            completed += upload(source)
        } catch (error: Exception) {
            failure = error
            break
        }
    }
    if (!shouldContinue()) cancelled = true
    return AgentFleetPartialUploadResult(completed, failure, cancelled)
}

internal data class AgentFleetComposerRetentionState(
    val target: String,
    val uploading: Boolean,
    val hasAttachments: Boolean
)

internal fun agentFleetComposerTargetToEvict(
    states: List<AgentFleetComposerRetentionState>,
    protectedTargets: Set<String>
): String? {
    val candidates = states.asSequence().filter {
        !it.uploading && it.target !in protectedTargets
    }
    return candidates.firstOrNull { !it.hasAttachments }?.target
        ?: states.firstOrNull {
            !it.uploading && it.target !in protectedTargets
        }?.target
}

private fun agentFleetTargetKey(vararg fields: String): String =
    fields.joinToString(separator = "") { "${it.length}:$it" }

internal fun agentFleetComposerTarget(host: String, project: String, session: String): String =
    agentFleetTargetKey(host, project, session)

internal fun agentFleetWorkspaceTarget(session: FleetSession): String = agentFleetTargetKey(
    session.id,
    session.hostId,
    session.physicalHostId,
    session.executionTargetId,
    session.backend,
    session.project,
    session.internalName,
    session.tool
)

internal class AgentFleetComposerUploadOwner {
    private var nextGeneration = 0L
    private val activeGenerations = mutableMapOf<String, Long>()

    @Synchronized
    fun begin(target: String): AgentFleetComposerUploadTicket {
        require(target.isNotBlank())
        val generation = Math.addExact(nextGeneration, 1L)
        nextGeneration = generation
        activeGenerations[target] = generation
        return AgentFleetComposerUploadTicket(target, generation)
    }

    @Synchronized
    fun finish(ticket: AgentFleetComposerUploadTicket): Boolean {
        if (activeGenerations[ticket.target] != ticket.generation) return false
        activeGenerations.remove(ticket.target)
        return true
    }

    @Synchronized
    fun invalidate(target: String) {
        activeGenerations.remove(target)
    }

    @Synchronized
    internal fun activeUploadCountForTest(): Int = activeGenerations.size
}

@Composable
internal fun AgentFleetComposerContent(
    target: String,
    nativeState: AgentFleetComposerNativeState,
    attachments: List<String>,
    uploading: Boolean,
    uploadError: String?,
    onShowPendingQuestion: () -> Unit,
    onAttach: () -> Unit,
    onCamera: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit,
    onComposerText: (String, Boolean) -> Boolean,
    localSuggestionsAvailableOverride: Boolean? = null,
    localSuggestionModeOverride: LocalSuggestionMode? = null,
    localSuggestionDebugFakeOutput: String? = null
) {
    val context = LocalContext.current
    val density by AgentFleetDisplayDensityStore.observe(context).collectAsState()
    var text by rememberSaveable(target) { mutableStateOf("") }
    val localSuggestions = remember(target, localSuggestionsAvailableOverride, localSuggestionModeOverride, localSuggestionDebugFakeOutput) {
        NativeLocalSuggestionState(context, localSuggestionsAvailableOverride, localSuggestionDebugFakeOutput, localSuggestionModeOverride)
    }
    DisposableEffect(localSuggestions) { onDispose { localSuggestions.close() } }
    LaunchedEffect(
        nativeState.target,
        nativeState.visible,
        nativeState.revision,
        nativeState.liveEventSerial
    ) {
        localSuggestions.clear()
    }

    val nativeForTarget = nativeState.target == target && nativeState.visible
    val planMode = nativeState.target == target && nativeState.interactionMode == "plan"
    var observedLiveSerial by remember(target) { mutableStateOf(nativeState.liveEventSerial) }
    var observedSuggestionKey by remember(target) { mutableStateOf("") }
    val automaticTarget = LocalSuggestionTarget("composer")
    val automaticKey = if (nativeForTarget && attachments.isEmpty() && canSuggestForComposer(nativeState.items, text)) {
        localSuggestionRevision(nativeState.items, automaticTarget)
    } else ""
    LaunchedEffect(nativeState.revision) {
        observedLiveSerial = nativeState.liveEventSerial
        observedSuggestionKey = automaticKey
    }
    LaunchedEffect(nativeState.liveEventSerial) {
        val start = shouldStartAutomaticSuggestion(
            observedSuggestionKey, automaticKey,
            nativeForTarget && localSuggestions.mode == LocalSuggestionMode.AUTOMATIC && nativeState.liveEventSerial > observedLiveSerial,
            historicalFrame = false
        )
        observedLiveSerial = nativeState.liveEventSerial
        observedSuggestionKey = automaticKey
        if (start) localSuggestions.request(nativeState.items, automaticTarget, automatic = true)
    }
    LaunchedEffect(attachments.toList()) { if (attachments.isNotEmpty()) localSuggestions.clear() }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (nativeState.target == target && nativeState.pendingQuestionId.isNotBlank()) {
                Button(
                    onClick = onShowPendingQuestion,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = DenseButtonPadding,
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Answer needed · Tap to open", fontSize = density.nativeBodySp.sp) }
            }
            val composed = buildAgentFleetComposerText(text, attachments)
            val hasContent = composed.isNotEmpty()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.Top
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        if (it.length <= MAX_MESSAGE_CHARS && '\u0000' !in it) {
                            text = it
                            if (it.isNotBlank()) localSuggestions.clear()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 148.dp)
                        .testTag("agent-fleet-message-input")
                        .semantics {
                            contentDescription = if (planMode) "Plan mode message input" else "Message input"
                        },
                    placeholder = { Text(if (planMode) "Plan message…" else "Message…", fontSize = density.nativeBodySp.sp) },
                    minLines = 4,
                    maxLines = 7,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = density.nativeBodySp.sp),
                    shape = RoundedCornerShape(14.dp),
                    colors = if (planMode) OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PlanAmber,
                        unfocusedBorderColor = PlanAmber,
                        focusedPlaceholderColor = PlanAmber,
                        unfocusedPlaceholderColor = PlanAmber
                    ) else OutlinedTextFieldDefaults.colors()
                )
                Column(
                    modifier = Modifier.widthIn(min = 70.dp, max = 82.dp).testTag("agent-fleet-composer-action-stack"),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TextButton(
                        onClick = onAttach,
                        enabled = !uploading && attachments.size < MAX_ATTACHMENTS,
                        modifier = Modifier.fillMaxWidth().testTag("agent-fleet-composer-attach"),
                        contentPadding = DenseButtonPadding
                    ) { Text(if (uploading) "Wait…" else "Attach", fontSize = density.nativeMetadataSp.sp) }
                    TextButton(
                        onClick = onCamera,
                        enabled = !uploading && attachments.size < MAX_ATTACHMENTS,
                        modifier = Modifier.fillMaxWidth().testTag("agent-fleet-composer-camera"),
                        contentPadding = DenseButtonPadding
                    ) { Text("Camera", fontSize = density.nativeMetadataSp.sp) }
                    TextButton(
                        onClick = {
                            if (onComposerText(composed, false)) {
                                text = ""
                                localSuggestions.clear()
                            }
                        },
                        enabled = !uploading && hasContent,
                        modifier = Modifier.fillMaxWidth().testTag("agent-fleet-composer-insert"),
                        contentPadding = DenseButtonPadding
                    ) { Text("Insert", fontSize = density.nativeMetadataSp.sp) }
                    Button(
                        onClick = {
                            if (onComposerText(composed, true)) {
                                text = ""
                                localSuggestions.clear()
                            }
                        },
                        enabled = !uploading,
                        modifier = Modifier.fillMaxWidth().testTag("agent-fleet-composer-send"),
                        contentPadding = DenseButtonPadding,
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(agentFleetPrimaryActionLabel(hasContent), fontSize = density.nativeMetadataSp.sp) }
                }
            }
            if (nativeForTarget && localSuggestions.targetKey == "composer") {
                LocalSuggestionChoices(
                    localSuggestions,
                    onUse = { suggestion -> text = suggestion; localSuggestions.clear() },
                    onRegenerate = { localSuggestions.request(nativeState.items, automaticTarget, automatic = localSuggestions.automatic) }
                )
            } else if (
                nativeForTarget &&
                localSuggestions.available &&
                canSuggestForComposer(nativeState.items, text)
            ) {
                TextButton(
                    onClick = { localSuggestions.request(nativeState.items, automaticTarget) },
                    modifier = Modifier.align(androidx.compose.ui.Alignment.End).testTag("local-suggest-composer"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) { Text(if (localSuggestions.mode == LocalSuggestionMode.AUTOMATIC) "Regenerate" else "Suggest locally", fontSize = density.nativeMetadataSp.sp) }
            }
            if (attachments.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    attachments.take(3).forEach { path ->
                        AssistChip(onClick = { onRemoveAttachment(path) }, label = { Text("✓ ${path.substringAfterLast('/')}") })
                    }
                }
            }
            uploadError?.let { Text(it, color = Color(0xFFFFB86B), fontSize = 14.sp) }
        }
    }
}

private const val MAX_MESSAGE_CHARS = 32_768
private const val MAX_ATTACHMENTS = 8
private const val MAX_TARGET_STATES = 32
private val DenseButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
private val ComposerColors = darkColorScheme(
    primary = Color(0xFFAFC6FF),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE6E8EE)
)
private val PlanAmber = Color(0xFFFFB74D)

internal fun buildAgentFleetComposerText(text: String, attachments: List<String>): String = buildString {
    append(text.trimEnd())
    if (attachments.isNotEmpty()) {
        if (isNotEmpty()) append("\n\n")
        attachments.forEach { append(it).append('\n') }
    }
}.trimEnd()

internal fun agentFleetPrimaryActionLabel(hasContent: Boolean): String = if (hasContent) "Send" else "Enter"

internal fun agentFleetWorkspaceImageCapacity(existingCount: Int): Int =
    (MAX_ATTACHMENTS - existingCount.coerceAtLeast(0)).coerceAtLeast(0)
