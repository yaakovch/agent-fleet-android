package com.termux.app.fleet

import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

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
                var text by rememberSaveable { mutableStateOf("") }
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        if (nativeTarget == target && pendingQuestion.isNotBlank()) {
                            Button(
                                onClick = activity::showAgentFleetPendingQuestion,
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = CompactButtonPadding,
                                shape = RoundedCornerShape(14.dp)
                            ) { Text("Answer needed · Tap to open", fontSize = 16.sp) }
                        }
                        val composed = buildAgentFleetComposerText(text, attachments)
                        val hasContent = composed.isNotEmpty()
                        val planMode = nativeTarget == target && interactionMode == "plan"
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = text,
                                onValueChange = { if (it.length <= MAX_MESSAGE_CHARS && '\u0000' !in it) text = it },
                                modifier = Modifier.weight(1f).semantics {
                                    contentDescription = if (planMode) "Plan mode message input" else "Message input"
                                },
                                placeholder = { Text(if (planMode) "Plan message…" else "Message…", fontSize = 17.sp) },
                                minLines = 3,
                                maxLines = 6,
                                shape = RoundedCornerShape(14.dp),
                                colors = if (planMode) OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PlanAmber,
                                    unfocusedBorderColor = PlanAmber,
                                    focusedPlaceholderColor = PlanAmber,
                                    unfocusedPlaceholderColor = PlanAmber
                                ) else OutlinedTextFieldDefaults.colors()
                            )
                            Column(
                                modifier = Modifier.width(108.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (activity.sendAgentFleetComposerText(composed, false)) {
                                            text = ""
                                            attachments.clear()
                                        }
                                    },
                                    enabled = !uploading && hasContent,
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = CompactButtonPadding,
                                    shape = RoundedCornerShape(14.dp)
                                ) { Text("Insert", fontSize = 16.sp, textAlign = TextAlign.Center) }
                                Button(
                                    onClick = {
                                        if (activity.sendAgentFleetComposerText(composed, true)) {
                                            text = ""
                                            attachments.clear()
                                        }
                                    },
                                    enabled = !uploading,
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = CompactButtonPadding,
                                    shape = RoundedCornerShape(14.dp)
                                ) { Text(agentFleetPrimaryActionLabel(hasContent), fontSize = 16.sp, textAlign = TextAlign.Center) }
                            }
                        }
                        if (attachments.isNotEmpty()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                attachments.take(3).forEach { path ->
                                    AssistChip(onClick = { attachments.remove(path) }, label = { Text("✓ ${path.substringAfterLast('/')}") })
                                }
                            }
                        }
                        uploadError?.let { Text(it, color = Color(0xFFFFB86B), fontSize = 14.sp) }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { activity.sendAgentFleetControlC() },
                                modifier = Modifier.weight(1f),
                                contentPadding = CompactButtonPadding,
                                shape = RoundedCornerShape(14.dp)
                            ) { Text("Ctrl+C", fontSize = 16.sp) }
                            OutlinedButton(
                                onClick = { activity.sendAgentFleetKey("SHIFT_TAB") },
                                modifier = Modifier.weight(1f),
                                contentPadding = CompactButtonPadding,
                                shape = RoundedCornerShape(14.dp)
                            ) { Text("⇧ Tab", fontSize = 16.sp, maxLines = 1) }
                            OutlinedButton(
                                onClick = activity::pickAgentFleetImages,
                                enabled = !uploading && attachments.size < MAX_ATTACHMENTS,
                                modifier = Modifier.weight(1f),
                                contentPadding = CompactButtonPadding,
                                shape = RoundedCornerShape(14.dp)
                            ) { Text(if (uploading) "Wait…" else "Attach", fontSize = 16.sp) }
                        }
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
                    failure = "Image import failed (${localResult.exceptionOrNull()?.javaClass?.simpleName ?: "unknown error"})."
                    break
                }
                val local = localResult.getOrThrow()
                val remote = runCatching { sendImage(activity, local, host, project, session) }.getOrElse {
                    failure = "Upload failed; the retry copy is at ${local.absolutePath}."
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

    private fun copyImage(activity: TermuxActivity, uri: Uri): File {
        val mime = activity.contentResolver.getType(uri).orEmpty()
        require(mime.startsWith("image/"))
        val extension = when (mime) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "img"
        }
        val appRoot = activity.filesDir.parentFile ?: error("App data directory is unavailable")
        val directory = File(appRoot, "files/home/.cache/agent-fleet/images").apply { mkdirs() }
        val output = File(directory, "${UUID.randomUUID()}.$extension")
        try {
            activity.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                FileOutputStream(output).use { stream ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_IMAGE_BYTES)
                        stream.write(buffer, 0, count)
                    }
                }
            }
        } catch (error: Exception) {
            output.delete()
            throw error
        }
        return output
    }

    private fun sendImage(activity: TermuxActivity, local: File, host: String, project: String, session: String): String {
        val appRoot = activity.filesDir.parentFile ?: error("App data directory is unavailable")
        val home = File(appRoot, "files/home")
        val prefix = File(appRoot, "files/usr")
        val wtmux = sequenceOf(File(home, ".local/bin/wtmux"), File(prefix, "bin/wtmux")).firstOrNull { it.canExecute() }
            ?: error("wtmux is unavailable")
        val bash = File(prefix, "bin/bash").takeIf { it.canExecute() }
            ?: error("bash is unavailable")
        val process = ProcessBuilder(
            bash.absolutePath, wtmux.absolutePath, "image", "send", local.absolutePath,
            "--host", host, "--project", project, "--session", session, "--json"
        ).directory(home).apply {
            environment()["HOME"] = home.absolutePath
            environment()["PREFIX"] = prefix.absolutePath
            environment()["PATH"] = "${File(home, ".local/bin")}:${File(prefix, "bin")}"
            enableTermuxExec(environment(), prefix)
        }.start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("image upload timed out")
        }
        val output = process.inputStream.bufferedReader().readText().take(64 * 1024)
        if (process.exitValue() != 0) error("image upload failed")
        return JSONObject(output.trim().lineSequence().last()).getString("path").also {
            require(it.startsWith(".wtmux/images/") || it.contains("/.wtmux/images/"))
        }
    }

    private const val MAX_MESSAGE_CHARS = 32_768
    private const val MAX_ATTACHMENTS = 8
    private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    private val CompactButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
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
