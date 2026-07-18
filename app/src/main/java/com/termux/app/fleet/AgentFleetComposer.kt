package com.termux.app.fleet

import android.content.Intent
import android.net.Uri
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.app.TermuxActivity
import java.io.File

object AgentFleetComposer {
    private val attachments = mutableStateListOf<String>()
    private var uploading by mutableStateOf(false)
    private var uploadError by mutableStateOf<String?>(null)
    private var currentTarget = ""
    private var nativeTarget by mutableStateOf("")
    private var interactionMode by mutableStateOf("unknown")
    private var pendingQuestion by mutableStateOf("")

    @JvmStatic
    fun updateNativeState(target: String, mode: String, pendingQuestionId: String) {
        nativeTarget = target
        interactionMode = mode
        pendingQuestion = pendingQuestionId
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
                val density by AgentFleetDisplayDensityStore.observe(activity).collectAsState()
                var text by rememberSaveable { mutableStateOf("") }
                var composerActions by rememberSaveable { mutableStateOf(false) }
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (nativeTarget == target && pendingQuestion.isNotBlank()) {
                            Button(
                                onClick = activity::showAgentFleetPendingQuestion,
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = DenseButtonPadding,
                                shape = RoundedCornerShape(14.dp)
                            ) { Text("Answer needed · Tap to open", fontSize = density.nativeBodySp.sp) }
                        }
                        val composed = buildAgentFleetComposerText(text, attachments)
                        val hasContent = composed.isNotEmpty()
                        val planMode = nativeTarget == target && interactionMode == "plan"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = activity::pickAgentFleetImages,
                                enabled = !uploading && attachments.size < MAX_ATTACHMENTS,
                                contentPadding = DenseButtonPadding
                            ) { Text(if (uploading) "Wait…" else "Attach", fontSize = density.nativeMetadataSp.sp) }
                            OutlinedTextField(
                                value = text,
                                onValueChange = { if (it.length <= MAX_MESSAGE_CHARS && '\u0000' !in it) text = it },
                                modifier = Modifier.weight(1f).semantics {
                                    contentDescription = if (planMode) "Plan mode message input" else "Message input"
                                },
                                placeholder = { Text(if (planMode) "Plan message…" else "Message…", fontSize = density.nativeBodySp.sp) },
                                minLines = 1,
                                maxLines = 3,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = density.nativeBodySp.sp),
                                trailingIcon = {
                                    Box {
                                        TextButton(
                                            onClick = { composerActions = true },
                                            modifier = Modifier.semantics { contentDescription = "Composer actions" },
                                            contentPadding = PaddingValues(0.dp)
                                        ) { Text("⋮", fontSize = 18.sp) }
                                        DropdownMenu(
                                            expanded = composerActions,
                                            onDismissRequest = { composerActions = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Ctrl+C") },
                                                onClick = {
                                                    composerActions = false
                                                    activity.sendAgentFleetControlC()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Shift+Tab") },
                                                onClick = {
                                                    composerActions = false
                                                    activity.sendAgentFleetKey("SHIFT_TAB")
                                                }
                                            )
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = if (planMode) OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PlanAmber,
                                    unfocusedBorderColor = PlanAmber,
                                    focusedPlaceholderColor = PlanAmber,
                                    unfocusedPlaceholderColor = PlanAmber
                                ) else OutlinedTextFieldDefaults.colors()
                            )
                            TextButton(
                                onClick = {
                                    if (activity.sendAgentFleetComposerText(composed, false)) {
                                        text = ""
                                        attachments.clear()
                                    }
                                },
                                enabled = !uploading && hasContent,
                                contentPadding = DenseButtonPadding
                            ) { Text("Insert", fontSize = density.nativeMetadataSp.sp, textAlign = TextAlign.Center) }
                            Button(
                                onClick = {
                                    if (activity.sendAgentFleetComposerText(composed, true)) {
                                        text = ""
                                        attachments.clear()
                                    }
                                },
                                enabled = !uploading,
                                contentPadding = DenseButtonPadding,
                                shape = RoundedCornerShape(12.dp)
                            ) { Text(agentFleetPrimaryActionLabel(hasContent), fontSize = density.nativeMetadataSp.sp, textAlign = TextAlign.Center) }
                        }
                        if (attachments.isNotEmpty()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                attachments.take(3).forEach { path ->
                                    AssistChip(onClick = { attachments.remove(path) }, label = { Text("✓ ${path.substringAfterLast('/')}") })
                                }
                            }
                        }
                        uploadError?.let { Text(it, color = Color(0xFFFFB86B), fontSize = 14.sp) }
                    }
                }
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

    private const val MAX_MESSAGE_CHARS = 32_768
    private const val MAX_ATTACHMENTS = 8
    private val DenseButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
private val ComposerColors = darkColorScheme(
        primary = Color(0xFFAFC6FF),
        surface = Color(0xFF111318),
        onSurface = Color(0xFFE6E8EE)
)
private val PlanAmber = Color(0xFFFFB74D)
}

internal fun buildAgentFleetComposerText(text: String, attachments: List<String>): String = buildString {
    append(text.trimEnd())
    if (attachments.isNotEmpty()) {
        if (isNotEmpty()) append("\n\n")
        attachments.forEach { append(it).append('\n') }
    }
}.trimEnd()

internal fun agentFleetPrimaryActionLabel(hasContent: Boolean): String = if (hasContent) "Send" else "Enter"
