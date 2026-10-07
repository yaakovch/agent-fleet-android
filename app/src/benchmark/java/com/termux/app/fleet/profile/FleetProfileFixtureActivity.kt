package com.termux.app.fleet.profile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.app.AgentFleetTheme
import com.termux.app.fleet.*
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.view.TerminalView
import java.io.File
import java.security.MessageDigest

/** Release-like renderer workload. Synthetic data; no transport or owner session. */
class FleetProfileFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var terminal by mutableStateOf(false)
        var messages by mutableStateOf((0 until 80).map(::message))
        setContent { AgentFleetTheme { Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row {
                TextButton(onClick = { terminal = !terminal }) { Text(if (terminal) "Native fixture" else "Terminal fixture") }
                TextButton(onClick = { startActivity(Intent(this@FleetProfileFixtureActivity, FleetProfileFileActivity::class.java)
                    .putExtra("hostId", "profile-host").putExtra("sessionId", "profile-session").putExtra("internalName", "profile-session")
                    .putExtra("reference", "/profile/fixture.html")) }) { Text("Open file preview") }
            }
            if (terminal) AndroidView(modifier = Modifier.fillMaxSize(), factory = { context -> TerminalView(context, null).apply {
                setTerminalViewClient(com.termux.shared.terminal.TermuxTerminalViewClientBase())
                setTextSize(28)
                mEmulator = TerminalEmulator(object : TerminalOutput() {
                    override fun write(data: ByteArray?, offset: Int, count: Int) {}
                    override fun titleChanged(oldTitle: String?, newTitle: String?) {}
                    override fun onCopyTextToClipboard(text: String?) {}
                    override fun onPasteTextFromClipboard() {}
                    override fun onBell() {}
                    override fun onColorsChanged() {}
                }, 80, 32, 1, 1, 5000, null).also { emulator ->
                    val data = (0 until 2000).joinToString("\r\n") { "Synthetic terminal row $it · Unicode שלום 😀" }.toByteArray()
                    emulator.append(data, data.size)
                }
            } }) else NativeSessionScreen(
                NativeSessionUiState("Profile fixture", "profile-host", "profile-session", adapter = "codex", connection = "Live",
                    revision = "profile", items = messages, hasMore = false,
                    providerState = ProviderState("verified", "PROFILE_FIXTURE", "profile", 0, ProviderComponent("fixture", "1"), ProviderComponent("fixture", "1"), false, "read_only_native")),
                aiComposer = true, onToggleTerminal = { terminal = true }, onRetry = {}, onLoadOlder = {},
                onApproval = { _, _ -> }, onQuestion = { _, _ -> }, onShellCommand = {}, onShellKey = {},
                onDirectory = {}, onRefreshDirectory = {}, onControlC = {}, onCloseSession = {}, onKillSession = {},
                onScheduleContinue = {}, onDismissAttention = {}, localSuggestionsAvailableOverride = false,
                onOpenHostFile = { startActivity(Intent(this@FleetProfileFixtureActivity, FleetProfileFileActivity::class.java)
                    .putExtra("hostId", "profile-host").putExtra("sessionId", "profile-session").putExtra("internalName", "profile-session")
                    .putExtra("reference", "/profile/fixture.html")) })
        } } }
    }

    private fun message(index: Int) = ConversationItem("profile-message-$index", "message", "2026-10-07T00:00:00Z",
        if (index % 2 == 0) "user" else "assistant", "", "## Synthetic message $index\n\n" +
            ("A **Markdown** paragraph with Unicode שלום 😀 and [profile file](/profile/fixture.html).\n\n".repeat(8)),
        "", "complete", "", emptyList(), emptyList(), messagePurpose = if (index % 2 == 0) "user" else "final")
}

/** Production file viewer and sandbox, backed by deterministic private bytes. */
class FleetProfileFileActivity : HostFilePreviewActivity() {
    override fun createFileSource() = object : HostFilePreviewSource {
        private val body = "<!doctype html><meta charset=utf-8><h1>Profile preview</h1><button onclick='this.textContent=\"Clicked\"'>Tap fixture</button>".toByteArray()
        private val digest = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        override fun selectSession(id: String, hostId: String, internalName: String, cancellation: FleetDownloadCancellation) =
            FleetSession(id, hostId, internalName, "Profile fixture", "", "profile", "codex", "linux", "idle", false, null, 0)
        override fun inspect(session: FleetSession, reference: String, cancellation: FleetDownloadCancellation) =
            HostFileMetadata("fixture.html", body.size.toLong(), "2026-10-07T00:00:00Z", digest, "html")
        override fun fetch(session: FleetSession, reference: String, metadata: HostFileMetadata, cancellation: FleetDownloadCancellation,
            directory: File, onProgress: (FleetDownloadState) -> Unit): FleetDownloadState {
            val file = File(directory, metadata.name).apply { writeBytes(body) }
            return FleetDownloadState(metadata.name, reference, "completed", metadata.size, metadata.size, file.path, "Verified", digest)
        }
    }
}
