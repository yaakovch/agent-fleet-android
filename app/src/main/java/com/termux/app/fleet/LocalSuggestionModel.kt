package com.termux.app.fleet

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.termux.app.fleet.LocalSuggestionService.Companion.ACTION_SHUTDOWN
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object LocalSuggestionModel {
    const val DISPLAY_NAME = "Gemma 4 E2B Instruct"
    const val FILE_NAME = "gemma-4-E2B-it.litertlm"
    const val SIZE = 2_588_147_712L
    const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    const val REVISION = "9262660a1676eed6d0c477ab1a86344430854664"
    const val URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/$REVISION/$FILE_NAME?download=true"

    fun directory(context: Context): File = File(context.filesDir, "local-llm")
    fun file(context: Context): File = File(directory(context), FILE_NAME)
    fun partial(context: Context): File = File(directory(context), "$FILE_NAME.part")
    fun isReady(context: Context): Boolean = file(context).let { it.isFile && it.length() == SIZE }
}

data class LocalModelUiState(
    val enabled: Boolean = false,
    val ready: Boolean = false,
    val busy: Boolean = false,
    val progressBytes: Long = 0L,
    val detail: String = "Model not installed",
    val error: String = ""
) {
    val progress: Float get() = (progressBytes.toDouble() / LocalSuggestionModel.SIZE).coerceIn(0.0, 1.0).toFloat()
}

object LocalSuggestionPreferences {
    private const val NAME = "agent_fleet_local_suggestions"
    private const val ENABLED = "enabled"

    fun enabled(context: Context): Boolean = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        .getBoolean(ENABLED, false) && LocalSuggestionModel.isReady(context)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
        if (!enabled) LocalSuggestionRuntime.shutdown(context)
    }
}

object LocalSuggestionRuntime {
    fun shutdown(context: Context) {
        val intent = Intent(context, LocalSuggestionService::class.java).setAction(ACTION_SHUTDOWN)
        runCatching { context.startService(intent) }
        runCatching { context.stopService(intent) }
    }
}

class LocalSuggestionModelManager(
    context: Context,
    private val onState: (LocalModelUiState) -> Unit
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var canceled = AtomicBoolean(false)
    @Volatile private var state = inspect()

    init { publish(state) }

    fun current(): LocalModelUiState = state

    fun setEnabled(enabled: Boolean) {
        if (enabled && !LocalSuggestionModel.isReady(app)) {
            publish(inspect().copy(error = "Download or import the verified model first."))
            return
        }
        LocalSuggestionPreferences.setEnabled(app, enabled)
        publish(inspect().copy(detail = if (enabled) "Ready for on-device suggestions" else "Off · no model RAM in use"))
    }

    fun download() = start("Preparing download…") { token -> downloadModel(token) }

    fun import(uri: Uri) = start("Importing model…") { token -> importModel(uri, token) }

    fun cancel() {
        canceled.set(true)
        publish(state.copy(detail = "Canceling…"))
    }

    fun remove() {
        cancel()
        LocalSuggestionPreferences.setEnabled(app, false)
        executor.submit {
            LocalSuggestionModel.file(app).delete()
            LocalSuggestionModel.partial(app).delete()
            publish(inspect().copy(detail = "Model removed · no model RAM in use"))
        }
    }

    fun close() { canceled.set(true); executor.shutdownNow() }

    private fun start(detail: String, block: (AtomicBoolean) -> Unit) {
        if (state.busy) return
        canceled = AtomicBoolean(false)
        publish(state.copy(busy = true, detail = detail, error = ""))
        val token = canceled
        executor.submit {
            runCatching { block(token) }
                .onFailure { error ->
                    val message = if (token.get()) "Canceled" else error.message ?: "Model setup failed"
                    publish(inspect().copy(detail = message, error = if (token.get()) "" else message))
                }
        }
    }

    private fun downloadModel(token: AtomicBoolean) {
        val directory = LocalSuggestionModel.directory(app).apply { mkdirs() }
        val partial = LocalSuggestionModel.partial(app)
        var offset = partial.length().takeIf { it in 1 until LocalSuggestionModel.SIZE } ?: 0L
        if (offset == 0L && partial.exists()) partial.delete()
        if (directory.usableSpace < LocalSuggestionModel.SIZE - offset + 512L * 1024L * 1024L) {
            throw IllegalStateException("More free app storage is required to finish the model download.")
        }
        var active = openDownload(LocalSuggestionModel.URL, offset)
        if (offset > 0L && active.responseCode != HttpURLConnection.HTTP_PARTIAL) {
            active.disconnect(); partial.delete(); offset = 0L
            active = openDownload(LocalSuggestionModel.URL, 0L)
        }
        if (active.responseCode !in setOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
            val code = active.responseCode; active.disconnect(); throw IllegalStateException("Model download returned HTTP $code.")
        }
        val append = offset > 0L && active.responseCode == HttpURLConnection.HTTP_PARTIAL
        var written = if (append) offset else 0L
        try {
            FileOutputStream(partial, append).use { output ->
                active.inputStream.use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    var lastPublished = written
                    while (true) {
                        if (token.get()) throw InterruptedException("Canceled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > LocalSuggestionModel.SIZE) throw IllegalStateException("Model download exceeded its pinned size.")
                        output.write(buffer, 0, count)
                        if (written - lastPublished >= 8L * 1024L * 1024L) {
                            lastPublished = written
                            publish(state.copy(progressBytes = written, detail = "Downloading ${formatModelBytes(written)} of ${formatModelBytes(LocalSuggestionModel.SIZE)}"))
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            active.disconnect()
        }
        verifyAndInstall(partial, token)
    }

    private fun importModel(uri: Uri, token: AtomicBoolean) {
        val directory = LocalSuggestionModel.directory(app).apply { mkdirs() }
        if (directory.usableSpace < LocalSuggestionModel.SIZE + 512L * 1024L * 1024L) {
            throw IllegalStateException("At least 3.1 GB of free app storage is required to import the model.")
        }
        val partial = LocalSuggestionModel.partial(app)
        var written = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        val input = app.contentResolver.openInputStream(uri) ?: throw IllegalStateException("The selected model could not be opened.")
        try {
            input.use { source ->
                FileOutputStream(partial, false).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        if (token.get()) throw InterruptedException("Canceled")
                        val count = source.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > LocalSuggestionModel.SIZE) throw IllegalStateException("The selected model is larger than the pinned artifact.")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        if (written % (8L * 1024L * 1024L) < count) publish(state.copy(progressBytes = written, detail = "Importing ${formatModelBytes(written)}"))
                    }
                    output.fd.sync()
                }
            }
            if (written != LocalSuggestionModel.SIZE || digest.digest().toHex() != LocalSuggestionModel.SHA256) {
                throw IllegalStateException("The selected file is not the pinned Gemma 4 E2B model.")
            }
            installVerified(partial)
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    private fun verifyAndInstall(partial: File, token: AtomicBoolean) {
        if (partial.length() != LocalSuggestionModel.SIZE) throw IllegalStateException("Model download is incomplete and can be resumed.")
        publish(state.copy(progressBytes = partial.length(), detail = "Verifying model…"))
        val digest = MessageDigest.getInstance("SHA-256")
        partial.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                if (token.get()) throw InterruptedException("Canceled")
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (digest.digest().toHex() != LocalSuggestionModel.SHA256) {
            partial.delete(); throw IllegalStateException("Model checksum failed; the invalid download was removed.")
        }
        installVerified(partial)
    }

    private fun installVerified(partial: File) {
        val model = LocalSuggestionModel.file(app)
        model.delete()
        if (!partial.renameTo(model)) throw IllegalStateException("The verified model could not be installed.")
        publish(inspect().copy(detail = "Verified model ready"))
    }

    private fun inspect(): LocalModelUiState {
        val ready = LocalSuggestionModel.isReady(app)
        val partial = LocalSuggestionModel.partial(app).length().coerceAtMost(LocalSuggestionModel.SIZE)
        return LocalModelUiState(
            enabled = ready && LocalSuggestionPreferences.enabled(app),
            ready = ready,
            progressBytes = if (ready) LocalSuggestionModel.SIZE else partial,
            detail = if (ready) "Verified model ready" else if (partial > 0L) "Download can resume from ${formatModelBytes(partial)}" else "Model not installed"
        )
    }

    private fun publish(next: LocalModelUiState) {
        state = next
        main.post { onState(next) }
    }

    private fun openDownload(value: String, offset: Long): HttpURLConnection {
        var current = value
        repeat(6) {
            val url = URL(current)
            if (url.protocol != "https") throw IllegalStateException("Model download must remain HTTPS.")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 120_000
                setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
            }
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location") ?: throw IllegalStateException("Model redirect was missing its target.")
            connection.disconnect()
            current = URL(url, location).toString()
        }
        throw IllegalStateException("Model download redirected too many times.")
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun formatModelBytes(value: Long): String = when {
    value >= 1024L * 1024L * 1024L -> "%.1f GB".format(value.toDouble() / (1024L * 1024L * 1024L))
    else -> "%.0f MB".format(value.toDouble() / (1024L * 1024L))
}
