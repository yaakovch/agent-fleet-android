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

object AgentFleetComposer {
    private val attachments = mutableStateListOf<String>()
    private var uploading by mutableStateOf(false)
    private var uploadError by mutableStateOf<String?>(null)
    private var currentTarget = ""
    private var nativeState by mutableStateOf(AgentFleetComposerNativeState())

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

    @JvmStatic
    fun bind(activity: TermuxActivity, view: ComposeView, enabled: Boolean) {
        if (!enabled) {
            view.visibility = View.GONE
            activity.terminalToolbarViewPager.visibility = if (activity.preferences.shouldShowTerminalToolbar()) View.VISIBLE else View.GONE
            return
        }
        val target = listOf(
            activity.intent.getStringExtra(AgentFleetContract.EXTRA_HOST_ID).orEmpty(),
            activity.intent.getStringExtra(AgentFleetContract.EXTRA_PROJECT).orEmpty(),
            activity.intent.getStringExtra(AgentFleetContract.EXTRA_INTERNAL_SESSION).orEmpty()
        ).joinToString(":")
        if (target != currentTarget) {
            currentTarget = target
            attachments.clear()
            uploadError = null
        }
        activity.intent.getStringArrayListExtra(AgentFleetContract.EXTRA_SHARED_IMAGES)?.takeIf { it.isNotEmpty() }?.let { values ->
            activity.intent.removeExtra(AgentFleetContract.EXTRA_SHARED_IMAGES)
            view.post { handleImageUris(activity, values.map(Uri::parse)) }
        }
        activity.terminalToolbarViewPager.visibility = View.GONE
        view.visibility = View.VISIBLE
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            MaterialTheme(colorScheme = ComposerColors) {
                AgentFleetComposerContent(
                    target = target,
                    nativeState = nativeState,
                    attachments = attachments,
                    uploading = uploading,
                    uploadError = uploadError,
                    onShowPendingQuestion = activity::showAgentFleetPendingQuestion,
                    onAttach = activity::pickAgentFleetImages,
                    onRemoveAttachment = attachments::remove,
                    onComposerText = { text, appendEnter ->
                        activity.sendAgentFleetComposerText(text, appendEnter).also { sent ->
                            if (sent) attachments.clear()
                        }
                    }
                )
            }
        }
    }

    @JvmStatic
    fun handleImageResult(activity: TermuxActivity, data: Intent) {
        val uris = buildList {
            data.data?.let(::add)
            data.clipData?.let { clip ->
                repeat(clip.itemCount) { index -> add(clip.getItemAt(index).uri) }
            }
        }
        handleImageUris(activity, uris)
    }

    @JvmStatic
    fun handleCapturedImage(activity: TermuxActivity, uri: Uri) {
        handleImageUris(activity, listOf(uri))
    }

    fun uploadWorkspaceImages(
        context: Context,
        sourceUris: List<Uri>,
        session: FleetSession,
        onComplete: (Result<List<String>>) -> Unit
    ) {
        val main = Handler(Looper.getMainLooper())
        Thread({
            val result = runCatching {
                sourceUris.distinct().take(MAX_ATTACHMENTS).map { uri ->
                    val local = copyImage(context, uri)
                    try {
                        sendImage(context, local, session.hostId, session.project, session.internalName)
                    } finally {
                        local.delete()
                    }
                }
            }
            main.post { onComplete(result) }
        }, "agent-fleet-workspace-image-upload").start()
    }

    private fun handleImageUris(activity: TermuxActivity, sourceUris: List<Uri>) {
        val uris = sourceUris.distinct().take((MAX_ATTACHMENTS - attachments.size).coerceAtLeast(0))
        if (uris.isEmpty()) return
        val host = activity.intent.getStringExtra(AgentFleetContract.EXTRA_HOST_ID).orEmpty()
        val project = activity.intent.getStringExtra(AgentFleetContract.EXTRA_PROJECT).orEmpty()
        val session = activity.intent.getStringExtra(AgentFleetContract.EXTRA_INTERNAL_SESSION).orEmpty()
        if (host.isBlank() || project.isBlank() || session.isBlank()) {
            uploadError = "This terminal tab is missing its fleet image target."
            return
        }
        uploading = true
        uploadError = null
        Thread({
            val uploaded = mutableListOf<String>()
            var failure: String? = null
            for (uri in uris) {
                val localResult = runCatching { copyImage(activity, uri) }
                if (localResult.isFailure) {
                    failure = localResult.exceptionOrNull()?.message ?: "Image import failed."
                    break
                }
                val local = localResult.getOrThrow()
                val remote = runCatching { sendImage(activity, local, host, project, session) }.getOrElse { error ->
                    failure = error.message ?: "Image upload failed. Refresh the session and retry."
                    null
                }
                if (remote == null) break
                local.delete()
                uploaded += remote
            }
            activity.runOnUiThread {
                attachments += uploaded
                uploadError = failure
                uploading = false
            }
        }, "agent-fleet-image-upload").start()
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
            if (output.exitCode == 0) return parseAgentFleetImagePath(output.stdout)
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

@Composable
internal fun AgentFleetComposerContent(
    target: String,
    nativeState: AgentFleetComposerNativeState,
    attachments: List<String>,
    uploading: Boolean,
    uploadError: String?,
    onShowPendingQuestion: () -> Unit,
    onAttach: () -> Unit,
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
    LaunchedEffect(attachments) { if (attachments.isNotEmpty()) localSuggestions.clear() }
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
